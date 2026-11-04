# Dougle Skills 작업 인계 — 2026-11-04

사용자 지시로 전용 앱/웹/터미널 UI 개발에서 **Claude Code·Codex 공용 도메인 지식 스킬**로 전환했다. 현재 버전0.2.0 Preview. `dougle-connect`와 `dougle-ask` 두 개이며 자료 연결은 자연어, 업무 기억은 내부 축적, 원본 자료는 읽기 전용이다.

## 구현

- skill-runtime/dougle.py: JSON stdin/out, 프로젝트별 SQLite/FTS5, 로컬 UTF-8 자료 연결·검색·변경/삭제 반영, 외부 읽기 도구 결과 스냅샷, SQLite 스키마/제한 조회, 출처 기반 기억·사용자 정정·구식 근거 무효화.
- 두 자기완결 스킬에 동일 런타임/계약을 복사한다. scripts/build-skills.py --check로 불일치 차단.
- scripts/install-skills.py: Codex ~/.agents/skills, Claude ~/.claude/skills 설치·업데이트·제거. 미소유/수정된 스킬 덮어쓰기 금지, 상태 보존.
- scripts/package-skills.py와 verify-skills-package.py: 소형 ZIP과 SHA256SUMS, 실제 패키지 새 HOME 인수.
- 현재 PC 양쪽 개인 스킬 디렉토리에 설치했다. 새 호스트 세션에서 스킬을 선택한다.

## 검증과 제한

도구/설치35개·실제 ZIP7개·스킬 형식2개 통과. 최초26개 중 검색 경로 오염/SQLite API 부재2개 실패는 수정 후 재검증했다. docs/skills/verification.md를 따른다. 외부 모델 호출0회, 기존181/200 원장 미변경.

실제 회사 SSO·Confluence·원격 DB·새 M Mac 호스트 AI 세션·모델의 의미 품질은 미검증이다. 기존 호스트의 인증된 읽기 도구가 필요하며 스킬 설치만으로 회사 접근 권한이 생기지 않는다. 원본을 읽지 못하면 pending, 읽은 자료는 snapshot이다. 앱 전체 AI 도구를 전역 샌드박싱하는 기능은 없다.

## 문서와 기존 제품

README/docs/README/AGENTS 및 docs/skills를 새 방향으로 작성했다. 이전 docs Markdown181개와 기존 구성요소 안내에는 과거 앱 기록 표시를 추가했다. 과거 수치와 앱 기능을 현재 지원으로 인용하지 않는다.

이전 backend/frontend/desktop 및 사용자 DB는 보존하며 새 스킬에서 실행하지 않는다. 0.1.41 앱 초안·0.1.40 자동 업데이트 피드는 그대로 두고 스킬 릴리스를 별도로 제공한다. 과거 상세 기록은 docs/desktop-handoff-history.md와 docs/desktop-agent-history.md에 있다.
