"""Gate unattested live data-query claims after successful receipt validation.

Call before auditing verification or saving/posting. Generic MCP queries have no
independent production mapping/watermark attestation. Query times, row timestamps
and model assertions do not provide one. Stable knowledge/code use is unaffected.
"""

import copy
import re

from .evidence import ReadReceipt


def convert_unverified_live(output, reads, report=False):
    """Convert a validated answer to a private lookup; otherwise return it unchanged.

    validate_verification must first validate receipts, sources and manual reports.
    No raw result is inspected. Plain-set fake receipts retain compatibility, not
    production attestation. There is deliberately no unused provider adapter API.
    """
    if output["status"] != "answered":
        return output
    checks = output["verification"]["checks"]
    unverified = []
    for check in checks:
        # The validated manual receipt supports only its own check, never another
        # query's freshness. Report validation remains the caller's responsibility.
        if check["receipt"] == "developer_report":
            continue
        receipt = reads.get(check["receipt"])
        if (
            check["kind"] == "live"
            and isinstance(receipt, ReadReceipt)
            and "live" in receipt
            and receipt.metadata.get("content_type") == "data_query"
        ):
            unverified.append(check)
    if not unverified:
        return output
    value = copy.deepcopy(output)
    verification = value["verification"]
    # Verification tracks completeness per kind, not per claim. A partial manual
    # check cannot coexist with missing live under that schema; preserve sources
    # and the caller's report, but mark the overall live requirement unresolved.
    verification["checks"] = [c for c in verification["checks"] if c["kind"] != "live"]
    if "live" not in verification["missing"]:
        verification["missing"].append("live")
    value["status"] = "needs_lookup"
    value["body"] = "조회한 자료가 현재 운영 상태를 반영하는지 추가 확인이 필요합니다."
    targets = []
    for check in unverified:
        location = output["sources"][check["source_index"]].get("location", "")
        # Copy only bounded table identifiers, never URLs, SQL or answer text.
        if (
            isinstance(location, str)
            and re.fullmatch(r"[A-Za-z0-9_-]{1,80}(?:\.[A-Za-z0-9_-]{1,80}){1,2}", location)
            and location not in targets
        ):
            targets.append(location)
    value["lookup"] = {
        "target": " · ".join(targets[:3]) or "문의의 운영 상태 조회 대상",
        "reason": (
            "막힌 이유: 데이터 조회는 성공했으나, 조회 대상의 운영 환경 대응과 "
            "복제 반영 시점을 독립적으로 확인하지 못했습니다.\n\n"
            "확인할 내용: 원래 문의의 대상·조건에 대해 운영 원본 또는 최신성이 확인된 "
            "자료로 상태·건수를 확인해 주세요. 조회 실행 시각과 데이터 반영 시점은 구분해 주세요.\n\n"
            "필요한 결과: 확인한 대상·환경·조건과 상태·건수, 데이터 반영 시점의 "
            "확인 근거를 짧게 알려 주세요. 원본 행이나 인증정보는 필요하지 않습니다."
        ),
        "sql": "",
        "schema_verified": False,
    }
    return value
