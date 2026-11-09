"""Only explicit whole-table questions may use a completed COUNT result."""

import json

import pytest

from dougle_bot.trusted_query import (
    parse_complete_count_result,
    parse_count_all_sql,
    question_asks_full_table_count,
    verified_full_table_count,
)

RELATION = "sample-project.replica.centers"
SQL = f"SELECT COUNT(*) AS count FROM `{RELATION}`"
QUESTION = f"`{RELATION}` 테이블 전체 행 수는 몇 건인가요?"


def complete(count):
    return {"jobComplete": True, "totalRows": "1", "rows": [{"count": count}]}


@pytest.mark.parametrize("sql", [
    SQL,
    f"\nselect count ( * ) as count from `{RELATION}`;\n",
    "SELECT COUNT(*) AS count FROM project1.replica.centers",
])
def test_exact_count_all_sql_identifies_one_physical_table(sql):
    expected = "project1.replica.centers" if "project1" in sql else RELATION
    assert parse_count_all_sql(sql) == expected


@pytest.mark.parametrize("sql", [
    f"{SQL} WHERE FALSE",
    f"{SQL} WHERE tenant_id = 999",
    f"{SQL} LIMIT 1",
    f"{SQL} JOIN `sample-project.replica.other` USING (id)",
    f"WITH x AS ({SQL}) SELECT * FROM x",
    f"{SQL}; SELECT 1",
    f"{SQL} -- comment",
    f"/* comment */ {SQL}",
    f"{SQL} GROUP BY tenant_id",
    f"SELECT count FROM `{RELATION}`",
    "SELECT COUNT(*) AS count FROM replica.centers",
])
def test_filtered_or_noncanonical_sql_cannot_prove_whole_table_count(sql):
    assert parse_count_all_sql(sql) is None
    assert verified_full_table_count(QUESTION, sql, complete(0)) is None


@pytest.mark.parametrize("question", [
    QUESTION,
    f"{RELATION} 테이블에 데이터가 있나요?",
    f"`{RELATION}` 테이블 전체에 데이터가 없나요?",
    f"{RELATION} 전체 건수는 몇 건인가요?",
])
def test_explicit_whole_table_questions_are_accepted(question):
    assert question_asks_full_table_count(question, RELATION)
    assert verified_full_table_count(question, SQL, complete(0)) == 0


@pytest.mark.parametrize("question", [
    "입하 이슈는 몇 건인가요?",
    "sample-project.replica.other 테이블에 데이터가 있나요?",
    f"{RELATION} 테이블에서 오늘 데이터는 몇 건인가요?",
    f"{RELATION} 테이블에서 tenant 999의 건수는 몇 건인가요?",
    f"{RELATION} 테이블의 11월 데이터가 있나요?",
    f"{RELATION} 테이블 전체 행 수는 몇 건인가요? WHERE FALSE",
    f"`{RELATION} 테이블 전체 행 수는 몇 건인가요?",
])
def test_question_must_name_same_unfiltered_table(question):
    assert not question_asks_full_table_count(question, RELATION)
    assert verified_full_table_count(question, SQL, complete(0)) is None


@pytest.mark.parametrize("body", [
    {"rows": [{"count": 0}]},
    {"jobComplete": False, "rows": [{"count": 0}]},
    {"jobComplete": True, "job_complete": False, "rows": [{"count": 0}]},
    {"jobComplete": True, "rows": []},
    {"jobComplete": True, "rows": [{"count": 0}, {"count": 1}]},
    {"jobComplete": True, "rows": [{"count": 0, "secret": "do-not-copy"}]},
    {"jobComplete": True, "rows": [{"count": True}]},
    {"jobComplete": True, "rows": [{"count": 0.0}]},
    {"jobComplete": True, "rows": [{"count": -1}]},
    {"jobComplete": True, "rows": [{"count": "00"}]},
    {"jobComplete": True, "rows": [{"count": "9" * 100}]},
    {"jobComplete": True, "rows": [{"value": 0}]},
    {"jobComplete": True, "rows": [{"count": 0}], "totalRows": "2"},
    {"jobComplete": True, "rows": [{"count": 0}], "nextPageToken": "next"},
    {"jobComplete": True, "rows": [{"count": 0}], "errors": ["failure"]},
])
def test_only_complete_single_integer_count_is_accepted(body):
    assert parse_complete_count_result(body) is None
    assert verified_full_table_count(QUESTION, SQL, body) is None


def test_json_and_single_text_result_are_accepted_without_retaining_rows():
    body = complete("0")
    assert parse_complete_count_result(json.dumps(body)) == 0
    assert parse_complete_count_result([{"type": "text", "text": json.dumps(body)}]) == 0
    assert parse_complete_count_result([{"type": "text", "text": "secret result"}]) is None


def test_new_complete_result_replaces_previous_zero():
    assert verified_full_table_count(QUESTION, SQL, complete(0)) == 0
    assert verified_full_table_count(QUESTION, SQL, complete(1)) == 1


def test_centers_count_cannot_answer_an_issue_question():
    assert verified_full_table_count("현재 입하 이슈는 몇 건인가요?", SQL, complete(0)) is None
