"""Execute shipped scanner against catalog fixtures. Never publishes or accesses real secrets."""

import hashlib
import json
import subprocess
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CATALOG = json.loads((ROOT / "docs/scenarios/catalog.json").read_text())
OUT = ROOT / ".tools/scenario-run-20261107"
PAYLOADS = {
    "Slack 봇 토큰": "xoxb-" + "1" * 12 + "-" + "A" * 24,
    "Slack 앱 토큰": "xapp-" + "A" * 30,
    "GitHub 토큰": "ghp_" + "A" * 36,
    "API 키": "sk-" + "C" * 45,
    "AWS 액세스키": "AKIA" + "B" * 16,
    "개인키": "-----BEGIN OPENSSH PRIVATE KEY-----\nSYNTHETIC_ONLY",
    "비밀번호 대입": "password=synthetic-test-value",
    "Bearer 인증": "Authorization: Bearer " + "D" * 40,
    "DB 연결 문자열": "mysql://synthetic:fake-value@db.invalid/test",
    "쿠키": "Cookie: session=synthetic-session-value",
    "이메일": "synthetic.person@example.invalid",
    "전화번호": "010-1234-5678",
    "주민번호 형태": "900101-1222222",
    "서명 URL": "https://example.invalid/item?X-Amz-Signature=" + "A" * 40,
    "Google 서명 URL": "https://example.invalid/item?X-Goog-Signature=" + "A" * 40,
    "접근 토큰 URL": "https://example.invalid/item?access_token=" + "A" * 40,
    "새로고침 토큰 URL": "https://example.invalid/item?refresh_token=" + "A" * 40,
    "HTML 이스케이프 URL": "https://example.invalid/item?q=1&amp;sig=" + "A" * 40,
    "원시 운영 행": "| customer_id | status |\n|---|---|\n| SYNTHETIC-CUSTOMER-42 | DELAYED |",
    "회사 기밀 의미": "비공개 계약: 가상 협력사에만 다음 분기 단가를 17% 인하합니다. 외부 공유 금지.",
}


def scan(path):
    start = time.monotonic()
    proc = subprocess.run(
        [
            "/bin/sh",
            str(ROOT / "skills/dougle-publish/scripts/check-sensitive.sh"),
            str(path),
        ],
        text=True,
        capture_output=True,
        timeout=60,
        check=False,
    )
    return proc, json.loads(proc.stdout), time.monotonic() - start


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    results = []
    for case in CATALOG["cases"]:
        if case["area"] != "SENSITIVE":
            continue
        kind, location = case["title"].split(" / ")
        payload = PAYLOADS[kind]
        with tempfile.TemporaryDirectory(prefix="dougle-catalog-scanner-") as tmp:
            stage = Path(tmp)
            doc = stage / "topic.md"
            doc.write_text("Ordinary synthetic domain knowledge.\n")
            before = None
            if location == "검사 이후 변경":
                _, before, _ = scan(stage)
                assert before["status"] == "PASS"
            text = {
                "본문": payload,
                "근거": "Evidence locator: " + payload,
                "코드 블록": "```text\n" + payload + "\n```",
                "제목": "# " + payload,
                "참조 파일": payload,
                "검사 이후 변경": payload,
            }[location] + "\n"
            if location == "참조 파일":
                doc.write_text("See [evidence](evidence.md).\n")
                (stage / "evidence.md").write_text(text)
            else:
                doc.write_text(text)
            proc, report, duration = scan(stage)
            leaked = payload in proc.stdout or payload in proc.stderr
            semantic = kind in ("원시 운영 행", "회사 기밀 의미")
            passed = (
                proc.returncode == 1 and report.get("status") == "BLOCK" and not leaked
            )
            results.append(
                {
                    "id": case["id"],
                    "layer": "production_scanner",
                    "result": "PASS"
                    if passed
                    else (
                        "REQUIRES_SEMANTIC_GATE" if semantic and not leaked else "FAIL"
                    ),
                    "case_complete": False,
                    "observed": {
                        "exit": proc.returncode,
                        "scanner_status": report.get("status"),
                        "finding_count": report.get("findingCount"),
                        "value_leaked": leaked,
                        "initial_clean_scan": before is not None,
                        "duration_seconds": round(duration, 5),
                    },
                    "fixture_sha256": hashlib.sha256(text.encode()).hexdigest(),
                    "remaining": "Host semantic review and final publish dispatch gate are separate; no actual remote write was attempted.",
                }
            )
    (OUT / "scanner-results.json").write_text(
        json.dumps(results, ensure_ascii=False, indent=2) + "\n"
    )
    print(
        json.dumps(
            {
                s: sum(r["result"] == s for r in results)
                for s in sorted({r["result"] for r in results})
            }
        )
    )


if __name__ == "__main__":
    main()
