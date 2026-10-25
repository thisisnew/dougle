# 문서 안내 — 어디부터 읽으면 되나요?

현재 제품은 **Dougle · 더글**입니다. [5분 사용법](how-it-works.md) · [수집·검색 개선과 검증 상태](ingestion-retrieval-upgrade.md) · [현재 디자인](dougle-brand.md). 과거 릴리스/검증 문서의 화면과 브랜드는 당시 기록이며 현재 사용법과 구분합니다.

[플러그인 목록·Notion 연결 안내와 남은 구현](plugins.md)

[실제 Notion 문서 40개 질문 검증·기본 제공 검색·이력 일괄 삭제](notion-search-verification.md)

[0.1.20 즉시 답변·스트리밍·이력 삭제](release-0.1.20-report.md)

[0.1.19 업로드 동기화·자연어 질문 검색](release-0.1.19-report.md)

[0.1.18 파일 추가 진행 표시·입력 UI 통일](release-0.1.18-report.md)

[0.1.17 자동 갱신 주기·왼쪽 아래 업데이트](release-0.1.17-report.md) · [업데이트 릴리스 게시 방법](automatic-updates.md)

[통합 자료 목록·전체 동기화·설정](library-simplification.md) · [0.1.16 자료 추가·오류 안내 정리](release-0.1.16-report.md)

[질문에 이미지·문서 첨부하기](question-attachments.md) · [Dougle 로고·디자인](dougle-brand.md)

0.1.11: [대화 중심 화면·모델 선택·코드 대조](chat-first.md) · [검증 및 배포 상태](release-0.1.11-report.md).

최신 변경: [앱이 관리하는 내부 검색](internal-search.md). 이전 0.1.10: [필요한 기능만 먼저 보기](simple-workflow.md) · [설치본 검증](release-0.1.10-report.md).

Dougle은 **내 자료를 모으고, AI에게 질문하고, 출처를 확인하는 Mac 앱**입니다. [새 이름·기존 자료 호환성](dougle-brand.md).
검색용 추출문·심볼·색인은 앱이 관리합니다. 서버나 벡터 DB를 별도로 설치하지 않습니다.

| 궁금한 것 | 읽을 문서 |
|---|---|
| 공개 업데이트 파일로 앱 교체가 되는지 | [실제 앱 복사본 교체·재실행 검사](published-update-check.md) |
| 앱에서 Codex 로그인·스트리밍 연결 | [0.1.7 검증·배포 기록](release-0.1.7-report.md) · [쉬운 사용법](codex-managed-login.md) |
| 필수 마무리와 실제 AI 검사 결과 | [Codex 실제 검증·남은 조건](essential-verification.md) |
| 현재 어디까지 됐고 무엇이 남았는지 | [최신 현황·필수 마무리 상태](phase-plan.md) |
| 설치하고 처음 써보기 | [Mac 설치·백업·삭제](macos-install.md) → [5분 사용법](how-it-works.md) |
| 내부 구조를 한눈에 보기 | [그림 설계도](design-map.md) |
| 무엇을 어떻게 저장하는지 | [데이터 모델과 실제 코드 대조](modeling.md) |
| 모델과 구현을 다시 조사한 결과 | [전 파일 조사 범위·규칙별 테스트·미구현 차이](model-audit.md) |
| 개발자가 알아야 할 구조 | [아키텍처](architecture.md) · [API](api.md) |
| 개발 실행·수정 후 배포 순서 | [개발 환경과 완료 체크리스트](development.md) |
| 최신 변경과 설치 파일 배포 상태 | [0.1.20 로컬 설치 기록](release-0.1.20-report.md) · [현재 작업 검증](ingestion-retrieval-upgrade.md) |
| GitHub 자료를 별도 설치 없이 가져오기 | [내장 Git 구성과 검증](bundled-git.md) |
| 등록한 자료의 이름·분류 수정 | [자료 설정 편집](source-metadata.md) |
| 전체 사용 흐름 검사와 발견한 문제 | [E2E 점검 결과](e2e-report.md) |
| 작업 중 강제 종료되어도 안전한지 | [중단 복구 검사](crash-recovery.md) |
| 앱에서 다음 버전 설치하기 | [자동 업데이트](automatic-updates.md) |
| Apple Silicon에서 쓰기/빌드 | [ARM 지원과 검증 범위](apple-silicon.md) |
| AI 설치·로그인·경로 설정 | [CLI 설정](cli-launch-settings.md) · [AI 전송 정책](cli-ai.md) |
| 간편 연결 개선 | [제공자 선택·인증 확인·브라우저 로그인](provider-connection.md) |
| Claude가 앱에 포함되지 않은 이유와 다음 작업 | [Claude 간편 연결 준비 상태](claude-readiness.md) |
| 실제로 어디까지 테스트했는지 | [E2E 점검 결과](e2e-report.md) · [0.1.11 검증 보고서](release-0.1.11-report.md) |
| 다음 컴퓨터에서 개발 이어가기 | [HANDOFF](../HANDOFF.md) · [단계별 현황](phase-plan.md) · [상세 백로그](backlog.md) |

## 알아두면 좋은 세 가지

1. **자료 등록 ≠ AI 전송.** 수집·검색은 로컬 처리이고 질문을 전송할 때 관련 자료의 AI 전송/실행에 동의합니다.
2. **AI 초안 ≠ 확정 지식.** 사람이 출처와 내용을 검토한 뒤 게시합니다. 기존 편집을 자동으로 덮어쓰지 않습니다.
3. **최신 상태 ≠ 사실 보증.** CURRENT는 마지막 로컬 수집본과 같다는 뜻입니다.

문서의 “구현”, “교차 빌드 확인”, “실제 기기 실행 확인”은 서로 다른 상태입니다.
과거 Phase 보고서는 당시 기록이며 현재 상태는 HANDOFF와 최신 릴리스 설명을 우선합니다.
## 최신 브랜드

[Dougle · 더글](dougle-brand.md) · [0.1.13 검증·배포](release-0.1.13-report.md)
