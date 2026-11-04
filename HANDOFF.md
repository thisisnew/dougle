# Dougle 0.3.0 — 지침 전용 스킬

사용자 피드백으로0.2.0의 Python/SQLite 실행기를 제거했다. 자료 정리·업무 기억·중복 처리 절차를 스킬에 포함하고 호스트의 기존 도구를 사용한다. 서비스 종류는 제한하지 않으며 도구/로그인/권한/자료 부재/일시 오류를 구분해 안내한다. 설정 후 pending 요청을 재개한다.

정본 skills/, 플러그인 plugins/dougle/, Claude marketplace .claude-plugin/marketplace.json. 공통 organization.md에 작업 공간/자료 동일성/범위/사실·진술·추론/갱신·정정·충돌·동시성 한계가 있다. 새 기억은 ~/.dougle/workspaces의 Markdown이며 이전 SQLite는 자동 이전하지 않는다.

과거 실행기/설치기/테스트를 제거했고 증거는 docs/skills/history-0.2.0에 보존했다. 이전35+7 도구 테스트는 새 스킬의 의미 품질 근거가 아니다. 새 구조/설치 검사 결과와 미검증 범위는 docs/skills/verification.md를 따른다.

사용자가 설치 스킬만으로 쓰길 원하므로 Python/Node/Java 런타임을 제품에 재도입하지 않는다. 개발용 검증 도구와 사용자 실행 의존성을 구분한다. 회사 데이터/원본은 읽기 전용, 호스트의 전역 권한 강제는 제공하지 않는다.
