"""Render only verified whole-table counts; hold other unverified live answers.

A successful BigQuery read can support an answer about its returned data without
claiming that an asynchronously replicated table proves production absence.
Unknown result shapes are never interpreted as empty.
"""

import copy
import re

from .evidence import ReadReceipt
from .trusted_query import question_asks_full_table_count


def _hold_unverified_live(output, reads):
    """Keep unreviewed SQL conclusions off the automatic posting path."""
    checks = output["verification"]["checks"]
    unverified = [
        check for check in checks
        if check["kind"] == "live"
        and check["receipt"] != "developer_report"
    ]
    if not unverified:
        return output
    value = copy.deepcopy(output)
    verification = value["verification"]
    verification["checks"] = [check for check in checks if check["kind"] != "live"]
    if "live" not in verification["missing"]:
        verification["missing"].append("live")
    value["status"] = "needs_lookup"
    value["body"] = "조회 결과와 문의 조건의 일치 여부를 확인 중입니다."
    targets = []
    for check in unverified:
        location = output["sources"][check["source_index"]].get("location", "")
        if (
            isinstance(location, str)
            and re.fullmatch(r"[A-Za-z0-9_-]{1,80}(?:\.[A-Za-z0-9_-]{1,80}){1,2}", location)
            and location not in targets
        ):
            targets.append(location)
    value["lookup"] = {
        "target": " · ".join(targets[:3]) or "문의의 조회 대상",
        "reason": (
            "데이터 조회는 성공했지만 질문의 대상·조건과 조회 결과, 답변의 각 주장 사이의 "
            "대응이 독립적으로 확인되지 않았습니다. 대상·필터·기간과 결과값을 확인해 주세요. "
            "원본 행이나 인증정보는 필요하지 않습니다."
        ),
        "sql": "",
        "schema_verified": False,
    }
    return value


def _verified_whole_table_count(output, reads, question, report):
    """Render only a completed count for a question naming that entire table."""
    verification = output["verification"]
    if (
        verification["question_kind"] != "current_state"
        or not set(verification["required"]) <= {"knowledge", "live"}
        or not isinstance(question, str)
        or bool(report)
    ):
        return None
    live_checks = [check for check in verification["checks"] if check["kind"] == "live"]
    if len(live_checks) != 1:
        return None
    receipt = reads.get(live_checks[0]["receipt"])
    if not isinstance(receipt, ReadReceipt) or "live" not in receipt:
        return None
    if sum("live" in value for value in reads.values()) != 1:
        # An unreferenced later query may contradict the model-selected receipt.
        return None
    metadata = receipt.metadata
    relation = metadata.get("strict_count_all_relation")
    count = metadata.get("complete_count")
    if (
        not isinstance(relation, str)
        or type(count) is not int
        or not question_asks_full_table_count(question, relation)
        or metadata.get("relations") != (relation.lower(),)
        or output["sources"][live_checks[0]["source_index"]].get("location") != relation
    ):
        return None
    value = copy.deepcopy(output)
    value["body"] = (
        "이번 BigQuery 조회에서는 결과가 없습니다. 잠시 후 다시 문의해 주세요."
        if count == 0 else f"이번 BigQuery 조회에서 전체 행은 {count:,}건입니다."
    )
    return value


def convert_unverified_live(output, reads, report=False, *, question=None):
    """After receipt validation, render a proved count or hold unproved live data."""
    if output["status"] != "answered":
        return output
    verified = _verified_whole_table_count(output, reads, question, report)
    return verified if verified is not None else _hold_unverified_live(output, reads)
