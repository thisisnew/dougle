"""File-reader outputs for actual connect ingestion tests; format extraction is host-owned."""

import re


def make(c):
    n = int(c["id"].split("-")[-1])
    variant = c["title"].split(": ")[-1]
    path = re.search(r"프로젝트의 (.+?)를? 연결", c["steps"][0])
    locator = "/synthetic/project/" + (path.group(1) if path else f"file-{n}.md")
    if variant == "한글·공백 경로":
        locator = locator.replace("/project/", "/팀 자료/입고 설계/")
    fact = f"가상 업무 규칙 R{n:03}: 검수 승인 상태에서만 회수 예약을 허용합니다. 보류 상태이면 예약할 수 없습니다. 운영담당자가 검수 승인 여부를 확인합니다."
    files = {
        "~/.dougle/index.md": "# Common index\nExisting topic -> knowledge/OLD.md\n",
        "~/.dougle/knowledge/OLD.md": "# Existing other topic\nClaim OLD: 보관 기간은 12일입니다.\nSource: OLD\n",
    }
    return {
        "fixture_type": "ingest",
        "variant": variant,
        "files": files,
        "initial_files": dict(files),
        "locator": locator,
        "identity": f"alpha:file:{n}",
        "fact": fact,
        "reads": 0,
        "source_reads": [],
        "user_request": f"{locator} 파일을 업무 지식으로 연결해 주세요.",
        "conversation_context": "제공되는 read_source 도구가 파일 내용을 읽습니다. 청크 커서는 응답에 표시됩니다.",
    }
