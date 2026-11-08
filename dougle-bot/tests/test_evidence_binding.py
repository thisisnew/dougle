"""Synthetic receipts and isolated validators; no model, service, or user-store access."""

import copy
import json

import pytest

from dougle_bot.evidence import (
    ReadReceipt,
    explicit_query_requirements,
    failure_summary,
    read_kinds,
    read_metadata,
    read_receipts,
    validate_verification,
)
from dougle_bot.safety import BotError


def observed(name="Read", args=None, body="class Order {}", *, error=False):
    events = [
        {
            "type": "assistant",
            "message": {
                "content": [
                    {
                        "type": "tool_use",
                        "id": "read1",
                        "name": name,
                        "input": args
                        if args is not None
                        else {"file_path": "/synthetic/main/Order.java"},
                    }
                ]
            },
        },
        {
            "type": "user",
            "message": {
                "content": [
                    {
                        "type": "tool_result",
                        "tool_use_id": "read1",
                        "content": body,
                        "is_error": error,
                    }
                ]
            },
        },
    ]
    return read_receipts("\n".join(json.dumps(item) for item in events))


def output(kind="code", location="/synthetic/main/Order.java", category=None):
    return {
        "status": "answered",
        "body": "확인한 조건에 따라 처리됩니다.",
        "lookup": None,
        "sources": [{"title": "합성 출처", "location": location}],
        "verification": {
            "question_kind": category
            or {"code": "implementation", "knowledge": "domain", "live": "current_state"}[kind],
            "required": [kind],
            "missing": [],
            "checks": [{"kind": kind, "receipt": "read1", "source_index": 0}],
        },
    }


@pytest.mark.parametrize(
    "actual,cited",
    [
        ("/synthetic/feature/Other.java", "/synthetic/main/Order.java"),
        ("/synthetic/feature/Order.java", "/synthetic/main/Order.java"),
        ("/synthetic/main/Order.java", "Different.java:12"),
        ("/synthetic/main/Order.java", "../other/Order.java"),
    ],
)
def test_successful_read_cannot_be_attached_to_demonstrably_different_file(actual, cited):
    reads = observed(args={"file_path": actual})
    with pytest.raises(BotError, match="required_read_not_verified"):
        validate_verification(output(location=cited), reads)


@pytest.mark.parametrize(
    "cited",
    [
        "/synthetic/main/Order.java",
        "/synthetic/main/./Order.java:12",
        "/synthetic/main/temp/../Order.java#L12-L15",
        "main/Order.java:12-15",
        "Order.java",
        "`/synthetic/main/Order.java:12`",
        "`/synthetic/main/Order.java`:12",
        "/synthetic/main/Order.java:12 (confirmOrder)",
        "file:///synthetic/main/Order.java#L12",
    ],
)
def test_normalized_paths_and_optional_line_suffixes_are_compatible(cited):
    validate_verification(output(location=cited), observed())


def test_actual_numbered_output_bounds_citation_but_requested_limit_is_not_coverage():
    reads = observed(
        args={"file_path": "/synthetic/main/Order.java", "offset": 10, "limit": 100},
        body="    10→class Order {\n    11→  boolean allowed;\n    12→}",
    )
    assert reads["read1"].metadata["line_range"] == [10, 12]
    assert reads["read1"].metadata["requested_range"] == [10, 109]
    validate_verification(output(location="Order.java:10-12"), reads)
    with pytest.raises(BotError, match="required_read_not_verified"):
        validate_verification(output(location="Order.java:11-13"), reads)
    unnumbered = observed(
        args={"file_path": "/synthetic/main/Order.java", "offset": 10, "limit": 100}
    )
    assert "line_range" not in unnumbered["read1"].metadata


def test_unrelated_hits_do_not_invent_contiguous_coverage():
    reads = observed(body="10→first\n99→last")
    assert "line_range" not in reads["read1"].metadata
    assert "line_range" not in observed(body="1→first\n999999999→last")["read1"].metadata


@pytest.mark.parametrize(
    "actual,cited,accepted",
    [
        (
            "https://raw.githubusercontent.com/org/repo/main/Order.java",
            "https://github.com/org/repo/blob/main/Order.java#L12",
            True,
        ),
        (
            "https://github.com/org/repo/blob/main/Order.java?raw=1",
            "https://raw.githubusercontent.com/org/repo/main/Order.java",
            True,
        ),
        (
            "https://github.com/org/repo/raw/main/Order.java",
            "https://github.com/org/repo/blob/main/Order.java",
            True,
        ),
        (
            "https://github.com/org/repo/blob/feature/Order.java",
            "https://github.com/org/repo/blob/main/Order.java",
            False,
        ),
        (
            "https://github.com/org/repo/blob/main/Order.java",
            "https://github.com/other/repo/blob/main/Order.java",
            False,
        ),
        (
            "https://example.test/a/Order.java?ref=main",
            "https://example.test/a/Order.java?ref=old",
            False,
        ),
    ],
)
def test_url_identity_preserves_revision_and_known_raw_aliases(actual, cited, accepted):
    reads = observed("WebFetch", {"url": actual})
    if accepted:
        validate_verification(output(location=cited), reads)
    else:
        with pytest.raises(BotError, match="required_read_not_verified"):
            validate_verification(output(location=cited), reads)


@pytest.mark.parametrize(
    "credentials",
    [
        "access_token=SECRET",
        "sv=1&sig=SECRET",
        "X-Amz-Credential=SECRET&X-Amz-Signature=SECRET",
        "credential=SECRET&api_key=SECRET&unknown_auth=SECRET",
    ],
)
def test_receipt_metadata_does_not_retain_signed_url_credentials(credentials):
    reads = observed("WebFetch", {"url": f"https://example.test/Order.java?{credentials}&ref=main"})
    assert "SECRET" not in repr(reads["read1"].metadata)
    assert reads["read1"].metadata["locator"]["target"] == "example.test/Order.java?ref=main"


@pytest.mark.parametrize(
    "note",
    [
        "/synthetic/.dougle/knowledge/orders.md",
        "/synthetic/.dougle/workspaces/old/knowledge/orders.md",
        "/synthetic/legacy/orders.md",
    ],
)
def test_knowledge_notes_may_cite_the_recorded_original(note):
    reads = observed(args={"file_path": note}, body="원본 출처와 조건을 기록한 합성 지식")
    validate_verification(output("knowledge", "/synthetic/original/Order.java"), reads)
    with pytest.raises(BotError, match="required_read_not_verified"):
        validate_verification(output("code", "/synthetic/original/Order.java"), reads)


def test_legacy_sets_remain_supported_and_repair_deepcopy_retains_binding():
    value = output()
    validate_verification(value, {"read1": {"code"}})
    reads = observed()
    assert reads == {"read1": {"knowledge", "code"}}
    clone = copy.deepcopy(reads)
    assert isinstance(clone["read1"], ReadReceipt)
    assert clone["read1"].metadata == reads["read1"].metadata
    validate_verification(value, clone)
    with pytest.raises(BotError, match="required_read_not_verified"):
        validate_verification(output(location="Other.java"), clone)
    assert "synthetic" not in json.dumps(failure_summary(value, clone))


@pytest.mark.parametrize("body,error", [("", False), ("Permission denied", False), ("ok", True)])
def test_metadata_is_never_issued_for_failed_reads(body, error):
    assert observed(body=body, error=error) == {}


def test_listing_is_knowledge_but_cannot_substitute_for_code_or_live():
    reads = observed("mcp__example__list_table_ids", {}, '{"tables":["orders"]}')
    assert reads["read1"].metadata["content_type"] == "locator"
    validate_verification(
        output("knowledge", "orders"), reads, question="테이블 목록을 알려주세요."
    )
    for kind in ("code", "live"):
        with pytest.raises(BotError, match="required_read_not_verified"):
            validate_verification(output(kind, "orders"), reads)
    datasets = observed("mcp__example__list_dataset_ids", {}, '{"datasets":["synthetic"]}')
    validate_verification(
        output("knowledge", "synthetic datasets"), datasets, question="데이터셋목록"
    )
    schema = observed("mcp__example__get_table_info", {"table": "orders"}, '{"columns":["id"]}')
    assert schema["read1"].metadata["content_type"] == "schema"
    validate_verification(output("knowledge", "orders"), schema)
    with pytest.raises(BotError, match="required_read_not_verified"):
        validate_verification(output("live", "orders"), schema)


@pytest.mark.parametrize(
    "question,expected",
    [
        ("현재 운영에서 처리되지 않은 주문은 몇 건인가요?", {"live"}),
        ("지금 센터 몇개냐??디비좀봐봐", {"live"}),
        ("지금 센터 몇개냐??DB좀봐봐", {"live"}),
        ("현재 파트너 몇개야?디비를 조회해줘", {"live"}),
        ("지금 시험대상 몇개냐? DB에서 봐줘", {"live"}),
        ("지금 DB 테이블에서 몇 개 조회되나요?", {"live"}),
        ("지금 DB 센터 개수가 어떻게 돼?", {"live"}),
        ("지금 DB 테이블의 레코드 건수를 조회해 주세요.", {"live"}),
        ("How many records are in the production database now?", {"live"}),
        ("현재 주문 상태를 확인해 주세요.", {"live"}),
        ("현재 코드를 확인해 주세요.", {"code"}),
        ("소스 코드를 읽어 주세요.", {"code"}),
        ("레코드 조회 조건을 설명해 주세요", set()),
        ("현재 주문은 한 번에 몇 개까지 취소할 수 있나요?", set()),
        ("현재 운영 DB 주문 건수를 조회하고 상태 코드의 의미도 설명해 주세요", {"live"}),
        ("코드는 읽지 말고 전략 패턴 개념을 설명해 주세요", set()),
        ("연결한 소스 목록을 설명해 주세요", set()),
        ("연결한 소스를 확인해 주세요", set()),
        ("자료 출처를 설명해 주세요", set()),
        ("주문 취소 함수의 조건을 코드로 확인해 주세요.", {"code"}),
        ("Inspect the current code for the mail schedule.", {"code"}),
        ("현재 코드에서 주문 건수를 어떻게 계산하나요?", {"code"}),
        ("현재 코드의 메일 발송 일정은 무엇인가요?", {"code"}),
        ("현재 운영 DB 주문 건수를 실제 조회하고 코드도 확인해 주세요.", {"code", "live"}),
        ("메일은 매일 몇 시에 발송되나요?", set()),
        ("현재 메일 발송 일정은 어떻게 되나요?", set()),
        ("현재 DB 주문 건수를 조회하는 쿼리 예시를 작성해 주세요.", set()),
        ("센터 개수를 조회하는 디비 쿼리 예시를 알려줘", set()),
        ("지금 센터 몇 개로 계산하는지 디비 집계 기준의 의미를 설명해줘", set()),
        ("데이터셋목록", set()),
        ("지금 DB의 테이블 몇 개야?", set()),
        ("지금 디비 데이터셋 개수 알려줘", set()),
        ("주문 상태라는 용어는 무엇인가요?", set()),
        ("오류 코드의 뜻을 알려 주세요.", set()),
        ("현재 오류 코드의 의미를 설명해 주세요.", set()),
        ("Explain the current error code.", set()),
        ("현재 운영 DB 건수 조회는 하지 말고 용어의 의미를 알려주세요.", set()),
        (None, set()),
    ],
)
def test_explicit_floor_does_not_turn_concepts_or_schedule_into_live_queries(question, expected):
    assert explicit_query_requirements(question) == expected


@pytest.mark.parametrize(
    "question",
    [
        "현재 운영에서 처리되지 않은 주문은 몇 건인가요?",
        "지금 센터 몇개냐??디비좀봐봐",
    ],
)
def test_domain_classification_cannot_hide_explicit_current_count_request(question):
    reads = observed("mcp__example__get_table_info", {"table": "orders"}, '{"columns":["id"]}')
    wrong = output("knowledge", "orders")
    wrong["body"] = "현재 주문은 3건입니다."
    with pytest.raises(BotError, match="verification_requirements_missing"):
        validate_verification(wrong, reads, question=question)
    validate_verification(
        output("knowledge", "orders"), reads, question="주문 테이블의 필드는 무엇인가요?"
    )


@pytest.mark.parametrize(
    "sql,live",
    [
        ("SELECT 1", False),
        ("SELECT CURRENT_TIMESTAMP()", False),
        ("SELECT 'FROM orders'", False),
        ("SELECT 1 -- FROM orders", False),
        ("SELECT 1 /* FROM orders */", False),
        ("SELECT * FROM UNNEST([1,2])", False),
        ("WITH x AS (SELECT 1) SELECT * FROM x", False),
        ("WITH x(n) AS (SELECT 1) SELECT * FROM x", False),
        ("WITH `x` AS (SELECT 1) SELECT * FROM `x`", False),
        ("SELECT COUNT(*) FROM `proj.ds.INFORMATION_SCHEMA.TABLES`", False),
        ("SELECT COUNT(*) FROM information_schema.columns", False),
        ("SELECT COUNT(*) FROM pg_catalog.pg_tables", False),
        ("SELECT * FROM sqlite_master", False),
        ("SELECT * FROM `proj.ds.__TABLES__`", False),
        ("SELECT * FROM `proj.ds.__TABLES_SUMMARY__`", False),
        ("SELECT COUNT(*) FROM orders", True),
        ('SELECT COUNT(*) FROM "orders"', True),
        ('SELECT COUNT(*) FROM "public"."orders"', True),
        ("SELECT COUNT(*) FROM `order-export.analytics.orders`", True),
        ("SELECT COUNTIF(`delete`) FROM `project.ds.orders`", True),
        ('SELECT COUNT(*) FROM "delete"', True),
        ('WITH "x" AS (SELECT 1) SELECT * FROM "x"', False),
        ('WITH "x" AS (SELECT * FROM "orders") SELECT * FROM "x"', True),
        ('SELECT "FROM orders"', False),
        ('SELECT COUNT(*) FROM "information_schema"."tables"', False),
        ("SELECT * FROM orders JOIN information_schema.tables ON TRUE", True),
        ("SELECT * FROM information_schema.tables JOIN orders ON TRUE", True),
        ("SELECT * FROM information_schema.tables JOIN information_schema.columns ON TRUE", False),
        (
            "SELECT * FROM `project.ds.orders` JOIN `project.ds.INFORMATION_SCHEMA.TABLES` ON TRUE",
            True,
        ),
        ("WITH x AS (DELETE FROM orders RETURNING *) SELECT * FROM x", False),
        ("SELECT COUNT(*) FROM `proj.ds.orders` WHERE status = 'pending'", True),
        ("WITH x AS (SELECT * FROM orders) SELECT COUNT(*) FROM x", True),
        ("SELECT COUNT(*) FROM (SELECT * FROM orders) x", True),
        ("SELECT 'INFORMATION_SCHEMA' FROM orders", True),
        ("SELECT * FROM orders; SELECT 1", False),
        ("SELECT COUNT(*) FROM orders;", True),
    ],
)
def test_sql_receipts_require_data_not_connectivity_or_schema_queries(sql, live):
    reads = observed("mcp__example__execute_sql_readonly", {"query": sql}, '{"rows":[{"n":0}]}')
    assert ("live" in reads["read1"]) is live
    assert reads["read1"].metadata["query_time"] is None
    assert reads["read1"].metadata["data_watermark"] is None
    assert reads["read1"].metadata["environment"] is None
    assert sql not in repr(reads["read1"].metadata)
    if live:
        validate_verification(output("live", "orders"), reads)
    else:
        with pytest.raises(BotError, match="required_read_not_verified"):
            validate_verification(output("live", "orders"), reads)


def test_actual_query_does_not_promote_result_row_fields_to_freshness_metadata():
    reads = observed(
        "mcp__example__execute_sql_readonly",
        {"sql": "SELECT COUNT(*) FROM orders"},
        '{"rows":[{"query_time":"now","environment":"production","data_watermark":"now"}]}',
    )
    assert reads["read1"].metadata["data_watermark"] is None


def test_metadata_helper_does_not_weaken_existing_unperformed_read_checks():
    assert read_metadata("Read", {"file_path": "/synthetic/Order.java"})["locator"]
    assert read_kinds("Grep", {"path": "/synthetic", "output_mode": "files_with_matches"}) == set()
    with pytest.raises(BotError, match="required_read_not_verified"):
        validate_verification(output(), {})
