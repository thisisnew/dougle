# 문서 안내 — 어디부터 읽으면 되나요?

Knowledge Forge는 **내 자료를 모아 검색하고, 출처가 있는 지식을 만드는 Mac 앱**입니다.
원본 자료·정리된 지식·AI 답변을 따로 보관합니다. 서버나 벡터 DB를 별도로 설치하지 않습니다.

| 궁금한 것 | 읽을 문서 |
|---|---|
| 앱에서 Codex 로그인·스트리밍 연결 | [0.1.7 검증·배포 기록](release-0.1.7-report.md) · [쉬운 사용법](codex-managed-login.md) |
| 필수 마무리와 실제 AI 검사 결과 | [Codex 실제 검증·남은 조건](essential-verification.md) |
| 현재 어디까지 됐고 무엇이 남았는지 | [최신 현황·우선 마무리할 네 가지](phase-plan.md) |
| 설치하고 처음 써보기 | [Mac 설치·백업·삭제](macos-install.md) → [5분 사용법](how-it-works.md) |
| 내부 구조를 한눈에 보기 | [그림 설계도](design-map.md) |
| 무엇을 어떻게 저장하는지 | [데이터 모델과 실제 코드 대조](modeling.md) |
| 모델과 구현을 다시 조사한 결과 | [전 파일 조사 범위·규칙별 테스트·미구현 차이](model-audit.md) |
| 개발자가 알아야 할 구조 | [아키텍처](architecture.md) · [API](api.md) |
| 개발 실행·수정 후 배포 순서 | [개발 환경과 완료 체크리스트](development.md) |
| 최신 설치 파일에 들어간 변경 | [0.1.7 배포 기록](release-0.1.7-report.md) |
| GitHub 자료를 별도 설치 없이 가져오기 | [내장 Git 구성과 검증](bundled-git.md) |
| 등록한 자료의 이름·분류 수정 | [자료 설정 편집](source-metadata.md) |
| 전체 사용 흐름 검사와 발견한 문제 | [E2E 점검 결과](e2e-report.md) |
| 작업 중 강제 종료되어도 안전한지 | [중단 복구 검사](crash-recovery.md) |
| 앱에서 다음 버전 설치하기 | [자동 업데이트](automatic-updates.md) |
| Apple Silicon에서 쓰기/빌드 | [ARM 지원과 검증 범위](apple-silicon.md) |
| AI 설치·로그인·경로 설정 | [CLI 설정](cli-launch-settings.md) · [AI 전송 정책](cli-ai.md) |
| 간편 연결 개선 | [제공자 선택·인증 확인·브라우저 로그인](provider-connection.md) |
| Claude가 앱에 포함되지 않은 이유와 다음 작업 | [Claude 간편 연결 준비 상태](claude-readiness.md) |
| 실제로 어디까지 테스트했는지 | [E2E 점검 결과](e2e-report.md) · [0.1.6 검증 보고서](release-0.1.6-report.md) |
| 다음 컴퓨터에서 개발 이어가기 | [HANDOFF](../HANDOFF.md) · [단계별 현황](phase-plan.md) · [상세 백로그](backlog.md) |

## 알아두면 좋은 세 가지

1. **자료 등록 ≠ AI 전송.** 수집·검색은 로컬 처리이고 외부 AI는 미리보기와 건별 승인 후 호출합니다.
2. **AI 초안 ≠ 확정 지식.** 사람이 출처와 내용을 검토한 뒤 게시합니다. 기존 편집을 자동으로 덮어쓰지 않습니다.
3. **최신 상태 ≠ 사실 보증.** CURRENT는 마지막 로컬 수집본과 같다는 뜻입니다.

문서의 “구현”, “교차 빌드 확인”, “실제 기기 실행 확인”은 서로 다른 상태입니다.
과거 Phase 보고서는 당시 기록이며 현재 상태는 HANDOFF와 최신 릴리스 설명을 우선합니다.
