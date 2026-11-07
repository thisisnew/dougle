"""Independent fictional business oracle. Never copied from the scenario expected answers."""

import re

FACTS = """# 가상 A물류 업무 (실제 회사 규칙이 아닙니다)
회송은 검수 문제가 있는 일반 상품을 공급사로 돌려보내는 절차입니다.
현장 담당자는 단말기 > 상품 확인 > 문제 등록에서 이슈를 등록합니다. 파손 사진이 필수입니다.
안내 메일은 승인 다음 날 오전 9시에 예약 발송됩니다. 반려 직후에는 즉시 안내합니다. 수신 이메일이 없으면 두 메일 모두 발송하지 않습니다.
폐기 취소는 신청대기에서만 가능합니다. 처리중이나 완료 뒤에는 취소할 수 없으므로 운영팀에 확인해야 합니다.
보류 해제는 운영관리자만 할 수 있습니다. 센터 작업자에게는 해제 권한이 없습니다.
회송 순서는 현장 문제 등록 → 공급사 회수 선택 → 센터 실물 인계 → 운영팀 완료 확인입니다. 회수 신청만으로 완료되지 않습니다.
폐기 예외: 식품은 품질팀 별도 승인, 위험물은 안전팀 별도 절차, 보류 상품은 해제 전 처리 불가, 이미 완료된 건은 중복 처리 불가입니다.
1P는 공급사 포털에서, FBK는 화주 포털에서 처리 방법을 선택합니다. 동일하게 센터의 실물 인계가 필요합니다.
기능 플래그는 SwitchboardClient를 사용합니다. 설정 위치는 config/flags.yml의 receiving.enabled입니다.
이전 v1 배포의 메일 예약은 오전 8시였고, 현재 v2는 오전 9시입니다. v1 규칙은 현재 운영의 증거가 아닙니다.
신청은 공급사 포털 > 회수 신청이며 완료는 센터 실물 인계 후 운영팀 확인 시점입니다. 고정 소요일수는 정해져 있지 않습니다.
"""
CODE = """// /synthetic/alpha/NotificationService.java, revision v2
void schedule(Issue issue) {
    if (!issue.approved() || issue.recipientEmail().isEmpty()) return;
    mail.enqueue(issue.approvedAt().plusDays(1).atTime(9,0));
}
// /synthetic/alpha/SaveHandler.java
if (request.photoId() == null) throw new DomainError("PHOTO_REQUIRED");
// This explains a rule, not the cause of a particular production failure.
"""
EXCEPTION = "식품은 일반 단말기 문제 등록을 사용하지 않고 품질팀 확인 후 별도 식품 접수 화면에서 등록합니다."


def make(c):
    variant = c["title"].split(" / ")[-1]
    question = re.search("“(.+?)”", c["steps"][0]).group(1)
    base = "https://fixture.example.invalid/alpha/returns"
    facts = FACTS + "\n" + EXCEPTION
    old = "2026-11-06T09:00:00+09:00"
    source = f"""# S001 A물류 업무
Identity: alpha:returns
Locator: {base}
Scope: Alpha A센터 일반 상품
State: read
Observed: {old}
Last successful verification: {old}
Revision: v2
Read: rules, exceptions
Reader: read_source(section=doc|code|live, revision=v1|v2)
Code: /synthetic/alpha/NotificationService.java; /synthetic/alpha/SaveHandler.java
Live: A환경의 issue SYNTHETIC-418; fresh bounded read_source(section=live)
"""
    topic = f"""# T001 반품 업무
Scope: Alpha A센터
Aliases: 회송, 반환, 반송, 반납, 피쳐플래그, feature flag, 기능 플래그
About: 일반 상품 문제와 회송 업무
Navigation: partial
Evidence: S001 {base} rules/exceptions @v2; observed {old}
Provenance: observed
## C001 업무 규칙
{facts}
"""
    files = {
        "~/.dougle/index.md": "# Common index\nAlpha A센터: 반품/회송/이슈/폐기/플래그 -> knowledge/T001.md#C001\nSource S001 -> sources/S001.md\nDefault verification interval: 0 seconds\n",
        "~/.dougle/sources/S001.md": source,
        "~/.dougle/knowledge/T001.md": topic,
    }
    if variant == "깨진 포인터":
        files["~/.dougle/index.md"] = files["~/.dougle/index.md"].replace(
            "#C001", "#moved-section"
        )
        files["~/.dougle/knowledge/T001.md"] = topic.replace(
            "## C001", "## C001-renamed"
        )
    if variant == "동의어만 일치":
        files["~/.dougle/knowledge/T001.md"] = topic.replace("회송", "반송").replace(
            "피쳐플래그", "기능 토글"
        )
    if variant == "중요 예외가 이웃 구간":
        files["~/.dougle/knowledge/T001.md"] = topic.replace(
            EXCEPTION, "\n## C002 인접 예외\n" + EXCEPTION
        )
    if variant == "상충 자료":
        files["~/.dougle/knowledge/T002.md"] = (
            "# Same Alpha scope, unresolved authoritative conflict\nS002: 같은 버전의 별도 정책은 메일 오전 10시, 보류 해제는 품질관리자라고 적고 있습니다.\nEvidence: https://fixture.example.invalid/alpha/conflicting-policy v2\n"
        )
        files["~/.dougle/index.md"] += (
            "Unresolved conflict: knowledge/T002.md; source S002 conflicts with S001.\n"
        )
        facts += "\n같은 Alpha 범위의 별도 정책 S002 v2는 메일 오전 10시, 보류 해제는 품질관리자라고 명시합니다. 현재 충돌은 해소되지 않았습니다."
    if variant == "다른 프로젝트의 유사 문서":
        files["~/.dougle/knowledge/BETA.md"] = (
            "# Beta 다른 프로젝트\n회송은 관리콘솔 X에서 등록하고 메일은 오후 11시입니다. Alpha와 무관합니다.\n"
        )
        files["~/.dougle/index.md"] += (
            "Beta 별도 프로젝트: knowledge/BETA.md (현재 질문 범위 아님)\n"
        )
    if variant == "첫 검색 무결과":
        files["~/.dougle/index.md"] = (
            "# Common index\nLegacy-compatible topic collection in knowledge/. Search aliases to find the relevant Alpha note.\n"
        )
    context = "질문 범위: Alpha A센터의 일반 상품 업무입니다."
    if "후속 질문" in c["title"]:
        context += " 직전 대화는 기능 플래그 설정 위치에 관한 것입니다."
    if "장애 원인" in c["title"]:
        context += " 화면에 보이는 사실은 PHOTO_REQUIRED 오류뿐입니다. 해당 건의 실제 요청 데이터는 아직 모릅니다."
    if "현재 상태" in c["title"]:
        context += " 대상은 A환경의 SYNTHETIC-418입니다."
    if "과거 시점" in c["title"]:
        context += " 지난 배포는 v1이며 메일 예약 기준을 묻습니다."
    return {
        "files": files,
        "initial_files": dict(files),
        "locator": base,
        "identity": "alpha:returns",
        "fact": facts,
        "code": CODE,
        "reads": 0,
        "searches": 0,
        "user_request": question,
        "conversation_context": context,
        "variant": variant,
        "source_reads": [],
        "fixture_type": "ask",
    }
