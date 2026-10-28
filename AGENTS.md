# Dougle 개발 지침

## 지속 배포 — 2026-10-28 사용자 지시

앞으로 작업 완료 시 테스트·패키지 검증을 마친 최신 소스를 GitHub에 푸시하고 Intel/Apple Silicon 설치 파일을 공개 릴리스한다. 다운로드 안내도 같은 버전을 가리키게 한다. 검증 실패를 숨기거나 미검증 실기기 인수를 통과로 표시하지 않는다. 현재 MVP는 파일·로컬 폴더 중심이며 웹 URL·GitHub URL 등록/동기화 및 GitHub 인증 화면은 제거했다. 기존 수집 자료는 보존한다. 아래 날짜별 기록은 당시 상태이며 최신 범위를 뒤집지 않는다.

## 공개 상태 — 2026-10-28

사용자 명시 요청으로 [0.1.31 Preview](https://github.com/thisisnew/dougle/releases/tag/v0.1.31-preview.1)를 공개했다. README 다운로드도 이 버전으로 연결한다. 아래 초안/공개 보류 설명은 공개 전 이력이다. 자동 업데이트 피드는 기존0.1.16을 유지하며, ARM 실기기·새 Mac 인수·공증 미완료와 Node 검사1건 시간 초과는 그대로 공개한다. 이번 변경은 배포 상태와 문서뿐이며 설치 파일을 재빌드하지 않았다.


## 대화 화면 정리 — 0.1.31 / 2026-10-28

대화의 자료 더 찾아보기·브라우저 화면 조사 수동 진입 버튼을 제거한다. 자동 검색과 기존 계획/승인 결과는 유지하며 외부 조사를 자동 실행하도록 바꾸지 않는다. UI760개 통과. 백엔드 코드는 변경하지 않았으며 검색 속도 개선은 아직 측정 전이다. 최신 설치·GitHub 초안 파일과 검사 한계는 [검증 기록](docs/evaluations/chat-actions-20261028.md)을 따른다. 아래0.1.30은 이전 이력이다. 공개/피드는 보류한다.


## 최신 확인 — 2026-10-28 / 0.1.30

검색19·대용량5·연속 대화5의 기존 실패 원인을 수정했다. 전체 Java1099통과(25 opt-in생략), UI760·Node172·검색150·파일100 통과. 실제 AI70회 추가(누적181/200): 한 변형은20회 후속, 네 변형은10회 후속이므로 전체 긴 대화 인수는 미완료다. 누적 PARTIAL950/FAIL0/NOT_RUN25/EXCLUDED25/PASS0이며 제외는 Slack·Datadog25건이다.

Intel0.1.30 로컬 설치, 이전 앱/정지 DB 백업, DB30·HTTP200/비인증403·서명·DMG/JAR 일치를 확인했다. JAR SHA-256: `2315f6343980550e3adbe68d07e302f672216c4b727e19b19341f6598da1f673`. Intel/ARM 설치 파일은 갱신했지만 ARM 실행·다른 Mac25·회사 서비스·전체 E2E는 미검증이다. 공개 릴리스/피드는 보류한다. 예전 잘린 업로드는 재분석/재업로드, 연결 자료는 다음 동기화가 필요하다. 아래 날짜별 이전 상태는 당시 이력이다.

[수정·검증 기록](docs/evaluations/remaining-fixes-20261027.md) · [설치 증거](docs/evaluations/local-install-0.1.30-20261028.json).


## Slack·Datadog 기능 제거 — 2026-10-27

최신 사용자 요청으로 Slack 문의·수신/알림·연결과 Datadog 연결·조회 API·네트워크 어댑터를 제거한다. 기존 DB 이력과 이미 저장된 대화·자료는 보존한다. 과거 지원/조회 전용 설명은 현재 지원 기능이 아니다. 로그 범위 실패5건은 해결로 집계하지 않으며 해당 기능의 인수25건(T0901–T0925)은 범위에서 제외한다. 검색19·대용량5·대화 문맥5 실패는 별도 수정 대상으로 남는다. Intel0.1.29 로컬 설치·백업·JAR/DMG 일치·서명·HTTP200/비인증403·DB29 확인 완료. JAR 4b9a60bb4bf0549b77e784da31e258277341be17869134b2e076f2d5f56bbd15. 공개/피드는 보류한다. docs/evaluations/local-install-0.1.29-20261027.json 참고. 아래0.1.28 이하는 이전 기록이다.

- 최신 로컬 설치는 Intel **0.1.28**, JAR `a2255ce7135ddecc8803b84e20906f7156cdecf52de1d8ebafe45a626d0208ed`, DB v29다. 수집/취소/부분 실패/Git/HTML/근거 발췌/가림 표시 인용 검증 수정을 포함한다. 이전0.1.27 앱과 정지된 SQLite 백업, 최종DMG·앱 서명·JAR일치·HTTP200/비인증403 확인. `docs/evaluations/local-install-0.1.28-20261027.json` 참고. 인수 ledger는 PARTIAL941/FAIL34/NOT_RUN25이며 전체 완료가 아니다. 실제AI 예산은111/200회 예약, 기존 journal을 유지한다. 공개/피드는 보류한다. 아래0.1.27 이하는 이전 설치 기록이다.

- 현재 로컬 설치는 Intel **0.1.27**, JAR `b647ff9815037ddef3780246d712a944d4534a43060f881cdcbab8c2e6f3a612`, DB v29다. 검색 캡처 질문/버튼 중복 수정과 영속1회 후속 계획 예산을 포함한다. 이전0.1.26 앱/정지된 DB 백업·서명·HTTP200/비인증403 확인. `docs/evaluations/local-install-0.1.27-20261027.json`을 따른다. 실제 HTTP/가상 CLI 대화2턴·이력·재시작 검증은 실제 AI 의미 평가가 아니다. 전체50개/회사 서비스/ARM 실기기 인수는 미완료이며 공개·피드는 보류한다. 아래 버전은 이전 기록이다.

- 현재 로컬 설치는 Intel **0.1.26**, JAR `f88d6f01b115c5951a98c35dd6723b447c4bce1da03843666a8ad5f64f368093`다. 제한형 팀 설정 UI 제거와 대화 계획/검색/읽기 전용 조회 연결을 포함한다. 이전0.1.25 앱/정지된 DB를 백업했고 v28·비인증403을 확인했다. `docs/evaluations/local-install-0.1.26-20261027.json`과 소스 검증 문서를 따른다. 전체 export/전문가50개/실제 회사 서비스/ARM 실기기 인수는 미완료이며 공개·피드는 보류한다. 아래0.1.25 및 설치 전 표현은 이전 기록이다.

- 2026-10-26 최신 사용자 결정: 팀 설정의 구성 JSON 공유와 제한형 자료·대화 이전은 요구와 다르므로 제거한다. 사용자가 원하는 것은 **자료 전체 내보내기**이며 이를 지원하지 못하면 기능을 제공하지 않는다. 최신 소스에서 메뉴·화면을 삭제했고 기존 자료와 내부 호환 API는 보존한다. 제한형 화면을 되살리거나 전체 export로 이름만 바꾸지 않는다. 기존 0.1.25 설치본 반영 여부는 별도 검증한다.

- 현재 로컬 설치는 Intel **0.1.25**, JAR `5cf78b6441a99f893ecbaf6e81babd3e17e01e46b154f3c772e43abe138ca22a`다. bounded conversation retrieval을 포함하며 기존0.1.24 앱/정지된 DB를 백업했다. 설치 후 v28·비인증403을 확인했다. `docs/evaluations/local-install-0.1.25-20261026.json`을 따른다. 새 `PendingOperationalScope` 계약 소스는 설치본에 포함되지 않고 자동 운영 조사도 미완료다. 아래0.1.24는 이전 설치 기록이다. 공개/업데이트 피드 승격은 보류한다.

- 현재 로컬 설치는 Intel **0.1.24**, JAR `b5b59534ed02f1ad342a64d55f6e40cff03947997af2c07340b5b9bd85473d47`다. 수동 운영 조회 UI 제거·기존 네 가지 오류 수정을 포함한다. 사용자 요청으로0.1.22 앱/DB를 백업하고 교체했으며 v28·비인증403을 확인했다. `docs/evaluations/local-install-0.1.24-20261026.json`을 따른다. 다음 bounded conversation retrieval 소스는 이 설치본에 포함되지 않는다. 공개/피드 승격은 보류한다. 아래0.1.22 설치 상태는 이전 기록이다.

- 2026-10-26 최신 사용자 결정: 수동 `운영 자료 조회` 화면을 제거한다. 대화나 설정에서 사용자가 DB·로그 조건을 직접 입력하는 별도 조회 화면을 다시 노출하지 않는다. 플러그인 연결 설정과 내부 읽기 전용 어댑터는 유지한다. 이후 대화형 조사 통합은 별도 구현·검증이 필요하며 수동 화면 제거를 자동 조사 완성으로 보고하지 않는다. 아래 수동 운영 조회 UI 설명은 이전 구현 이력이다.

- 2026-10-26 최신 설치 상태: 사용자 명시 요청으로 부모가 Intel 0.1.22를 `/Applications/Knowledge Forge.app`에 로컬 시험 설치했다. 설치 후 DB 메타데이터 v28·비인증 API 403을 확인했다(부모 보고). 공개 릴리스·업데이트 피드 승격은 계속 보류하며 실제 AI 시간 초과 원인과 전체 전문가 인수는 미완료다. 아래 설치 금지/미설치/v25–v27 작업 중 표현은 당시 이력이며, 정책 반영 전 0.1.21 설치 금지는 유지한다. 로컬 설치는 외부 쓰기나 추가 AI 호출 승인이 아니다. 백업·해시는 `docs/macos-install.md`의 현재 설치 기록을 따른다.

- 2026-10-26 최우선 사용자 정책: Slack을 포함한 모든 외부 업무 시스템은 조회 전용이다. DB·문서·원본 파일 생성/수정/삭제, Slack 메시지 게시/수정/삭제를 금지하며 사용자 승인이나 설정으로 해제하지 않는다. 앱 내부 대화·색인·설정 저장과 조회용 인증/연결 수립은 허용한다. Slack 답변은 로컬 초안·복사까지만 허용한다. 아래 답장 전송 구현/검증은 과거 기록이며 신규 실행 경로를 유지할 근거가 아니다. 정책 반영 전 0.1.21 스냅샷은 설치·공개하지 않는다.

- 최신 소스는 DB v28 작업 중이다. `slack_reply`는 명시적 일회 전송 동의/영수증을 저장하고 시작 시 미완료 SENDING을 UNCERTAIN으로 바꿔 자동 재전송하지 않는다. 전송 중 Workspace 삭제는 원자적으로 거부한다. 아래 v27은 앞서 검증한 JAR 범위이며 v28 공개·설치·실제 Slack 전송 검증을 뜻하지 않는다.

- 최신 전문가 작업본은 DB v27이다. `browser_investigation`은 별도 동의한 격리 브라우저 조회 범위/진행을 저장한다. `investigation_plan`은 별도 일회 동의된 계획 AI 1회·로컬 검색 1회를 저장하며 새 답변은 새 불변 근거/승인으로 실행한다. V3 계획 미리보기는 NO_EVIDENCE 질문도 검색을 재시도할 수 있다. `PlanModel` 전용 출력 schema를 사용하고 답변용 schema를 재사용하지 않는다. `team_settings_import_receipt`는 새 Workspace 가져오기의 요청/계획 중복을 막는다. 기존 v25 근거 JSON·답변 승인은 유지한다. 최신 검사·미배포 범위는 `HANDOFF.md`와 `docs/expert-implementation.md`를 우선하고 아래 DB v25/v26 유지 문구는 이전 단계로 읽는다.

- 전문가 구현 작업본은 `docs/expert-implementation.md`를 우선한다. 최신 사용자 요구는 실제 연속 대화이며 아래 독립 질문/자동 이전 문맥 미전송은 이전 정책이다. thread/parent/request ID와 제한된 최근 문맥을 불변 근거 JSON에 저장하고 DB v25·기존 승인/일회 소비를 유지한다. 단위/가상 검사를 실제 모델·서비스·ARM 인수 통과로 합산하지 않는다. 50개 판정은 `docs/expert-evaluation-status.json`과 `node scripts/expert-evaluation.mjs gate`로 확인한다. 새 작업본은 기존 draft 0.1.20 패키지와 다르며 미배포다.

- 최신 검색 작업본은 DB v25입니다. Mac 패키지에 CPU별 다국어 검색 엔진을 포함하고 자동 준비합니다. 준비/오류 중 새 질문은 UI/API에서 잠급니다. 768자 조각을 재평가한 뒤 문서 중복을 제거하고, 근거 고정 시 짧은 원문/앞뒤 문맥을 확장합니다. Notion MCP JSON 본문·제목을 해제하며 기존 자료에는 재동기화가 필요합니다. 질문 기록은 체크박스 일괄 삭제입니다. 최신 검증과 남은 한계는 `docs/ingestion-retrieval-upgrade.md`를 우선합니다. 아래 선택형 다운로드·v24 설명은 이전 이력입니다.

- 최신 플러그인은 설정 내부에 있습니다. Notion·Jira/Confluence Cloud는 공식 MCP의 DCR/PKCE·기본 브라우저 승인·로컬 콜백·취소/만료·암호화 보관·refresh 회전을 구현합니다. 읽기 도구만 허용하고 Workspace에 등록한 개별 링크를 수집합니다. MySQL 접속 폼/스키마 수집과 이전 내부 토큰 API는 유지합니다. 실제 외부 계정 승인·자료 선택 검색창·복수 계정·공간 전체 수집·DB 행/SQL IDE는 미검증/미구현 구분을 `docs/plugins.md`에서 확인합니다.

- 최신 연속 대화/수집 UX: 같은 화면의 질문·답변은 bundle ID별 패널을 유지하며 아래로 누적합니다. 각 질문은 독립 저장되며 자동 이전 문맥 전송은 하지 않습니다. 폴더·웹 링크·GitHub는 등록 직후 첫 동기화, 파일은 업로드 중 분석·색인 갱신을 수행합니다. 자료 변경 중 App의 탭·설정·Workspace 선택/생성을 잠그고 실제 자료 단위 작업 내역과 최근 성공 시간을 표시합니다. 예상 시간/가짜 파일 로그는 만들지 않습니다.

- 최신 설정 배치: 사이드바 하단에는 낮은 높이의 설정 버튼만 두고 업데이트 확인은 설정 화면 내부에 표시합니다. 다른 화면에서도 업데이트 상태 조회는 유지하며 새 버전은 설정 버튼의 점으로 알립니다. 빈 시작 화면에는 외곽 박스/설명 없이 Dougle 로고와 Workspace 만들기 버튼을 표시합니다. 대화에도 화면 제목을 표시하고 Workspace가 없어도 질문 기록 탭은 열립니다.

- 최신 Workspace UI: 기본 OS select 대신 아래로 펼치는 앱 목록을 사용합니다. 신규 Workspace 이름은 UI/API에서 최대 20자로 제한하며 기존 긴 이름은 변경하지 않습니다. 목록은 한 줄 말줄임·원문 title, 관리 창은 이름/삭제와 갱신 설정을 두 줄로 구분합니다. 전환은 새 자료 로딩까지 스켈레톤/선택 잠금을 유지하고 상단 중복 이름은 표시하지 않습니다. 공통 로고·Dock·시작/오류창의 `o`는 캐릭터 얼굴이며 오른쪽 캐릭터 중복 배치는 하지 않습니다.

- 0.1.20 사용자 최신 결정: 질문 전송이 관련 자료의 선택 AI 전송/실행 동의입니다. 대화에서 별도 체크박스·답변 준비 단계를 제거합니다. 업로드/동기화/이력 열기만으로 AI를 호출하지 않습니다. 내부 미리보기 검증·CAS 승인·고정 모델·일회 소비·동일 요청 ID 재확인은 유지합니다. 대화는 진행 표시·스트림 답변·아래 출처만, 삭제는 질문 기록 화면에서 공통 확인창으로 제공합니다. 아래 건별 동의 UI 설명은 이전 정책입니다.

- 0.1.14: GitHub 저장소는 `thisisnew/dougle`입니다. 새 피드는 새 저장소를 사용하지만 업데이트 자산 URL은 구버전 allowlist 호환을 위해 기존 `thisisnew/knowledge-forge` 리디렉션을 유지합니다. 기존 이름을 재사용하지 않습니다. 앱 ID·저장소 폴더·번들 이름은 유지합니다.
- 현재 작업본 DB v24: Workspace 자동 갱신 주기·CAS·새 자료 상속 및 Source 등록 날짜. 과거 미기록 날짜는 NULL로 보존합니다. 토글만 바꾸면 기존 개별 주기를 보존하고, 주기를 선택하면 Workspace 자료에 일괄 적용합니다. [화면/정책](docs/library-simplification.md). 아래 DB v23/v22/v21은 과거 단계 기록입니다.
- 0.1.17 하단 업데이트는 공개 GitHub Release의 `latest.json`을 탐색하고 기존 피드를 fallback으로 사용합니다. 자동 확인 시 팝업/다운로드를 띄우지 않고 하단 배지만 바꿉니다. main 창·현재 로컬 origin의 인자 없는 상태/수동 확인 IPC만 허용하며 패키지 서명·CPU·버전 검사를 유지합니다. [릴리스 게시](docs/automatic-updates.md).
- 시작 대기 문구와 실제 연결 오류를 구별합니다. 정상 시작 status 창은 계속 숨기고 오류 때만 표시합니다. 네이티브 호스트에도 다색 Dougle 로고를 적용합니다.

- 최신 브랜드는 **Dougle · 더글**입니다. `Wordmark` 공통 컴포넌트·Avenir Next·다색 글자·화이트/블루 톤을 사용합니다. 입력 안내는 ‘더글한테 물어보기’. 아래 CLUE/입체 로고는 과거 기록입니다. 앱 ID·자료·인증·업데이트 호환 이름은 유지합니다. [현재 디자인](docs/dougle-brand.md).

- 0.1.12 UX: 자료 추가는 파일/폴더/웹 링크/GitHub 한 곳, 폴더는 Tauri 폴더 전용 선택기만 사용합니다. 자동 갱신·Workspace 관리는 설정, 질문 기록은 카드 그리드→기존 ChatPanel입니다. 수트 탐정·Condensed 인디고 입체 워드마크가 최신이며 아래 Helvetica Neue 기록은 이전 버전입니다. [설계·제약](docs/folder-settings.md).

- 질문 첨부는 `attachment` 모듈·DB v22이며 전역 자료/색인과 독립적입니다. `docs/question-attachments.md`의 용량·수명주기·승인 해시·Codex 이미지 입력을 유지합니다. 실제 사진은 테스트에 사용하지 않습니다. 아래 v21 유지 기록은 첨부 이전 단계입니다. 모델 선택은 실제 표시 이름만, CLUE 워드마크는 Helvetica Neue입니다.

- 사용자용 브랜드는 CLUE, 돋보기를 든 안경 없는 탐정 캐릭터입니다. [브랜드·호환성](docs/clue-brand.md). 원본 프로필 사진은 커밋/동봉하지 않습니다. 앱 ID·DB·인증·업데이트 URL·기존 번들/배포 파일명은 호환성을 위해 유지합니다.

- 정상 시작 시 status 창은 숨기고 메인 화면만 표시합니다. 실패 안내·사용자 확인을 제거하지 않습니다. `runtime_smoke` 등 GUI 검사는 실제 임시 창을 띄우므로 사전에 알리고 불필요하게 반복하지 않습니다. 시작 창 회귀는 `native-host-smoke.mjs <앱 경로> --startup-window`로 별도 확인합니다.

- 2026-10-24 최신 UX: 지식 문서 메뉴·원본의 DraftForm 진입점을 제거하고 기본 질문은 `includeKnowledge=false`로 원본 추출문/심볼/Lucene 색인을 사용합니다. 기존 편집형 문서·API는 보존하되 기본 검색에 넣지 않습니다. 이전 지식 포함 미완료 질문은 원본으로 다시 질문하며 완료 답변은 보존합니다. 자동 AI 요약·의미 검증을 구현한 것으로 보고하지 않습니다. [내부 검색 설계](docs/internal-search.md).

- 0.1.11 기본 화면은 `ChatPanel`과 `QuestionHistory`, 자료 탭은 `SourcePanel(manageOnly)`입니다. [대화 중심 설계](docs/chat-first.md)를 따릅니다. 연결 전에는 AI 질문만 잠그며 자료와 이력은 보존합니다. 기본 질문은 독립 요청이고 자동으로 이전 문맥을 전송하지 않습니다. 선택 모델은 미리보기·승인에 고정하며 연결 성공을 계정의 모델 이용 성공으로 표시하지 않습니다. 모델 프리셋은 공식 문서 기반 예시로, 실제 계정 목록이 아닙니다. 쿨 화이트·차콜·인디고와 안경 없는 아이콘을 유지합니다.

- 0.1.7 Mac 실행기는 앱 데이터의 `codex-profile`을 지정한다. `CodexProfile`의 일반 프로필/설정/링크/권한 차단을 유지하고 인증 내용을 읽거나 복사하지 않는다. 연결·로그인·초안·스트리밍이 같은 전용 인증을 사용하며 승인 binding에 프로필을 포함한다. [사용법](docs/codex-managed-login.md). 기본 변경으로 앱에서 한 번 새 로그인이 필요하다.

- 사용자 최신 범위: 필수 마무리만 진행한다. 실제 AI·Claude 간편 연결·ARM 실기기·설치 앱 업데이트 외의 확장은 사용 피드백 전까지 보류한다. Codex 전용 인증·중간 SSE는 0.1.7에서 확인했다. `docs/essential-verification.md`의 Claude 호환성·화면 제어 설정 장애를 구분한다. 외부 AI 검사는 가상 자료만 명시적 opt-in으로 실행한다.
- `scripts/published-update-smoke.mjs`는 공개 앱 복사본을 임시 경로에서만 교체한다. 실제 공개 서명/기본 버전 비교·교체 후 실행 검증을 유지하고 사용자 Applications 앱/DB·업데이트 메뉴 클릭 검증으로 확대하지 않는다. [범위](docs/published-update-check.md). 검사만 변경하면 기존 설치 파일을 유지한다.

- 0.1.6 Source 이름·분류 편집은 `docs/source-metadata.md`를 따른다. `pollingRevision`은 폴링과 메타데이터 편집의 공통 CAS 버전이다. 수집 중 수정 차단·Source/루트 이름/검색 큐 원자성·기존 불변 근거 보존을 유지한다. 위치/종류 변경은 지원하지 않는다. DB v21 유지.

- 매 작업 종료 시 GitHub 문서를 실제 상태로 갱신·푸시한다. `docs/phase-plan.md`를 현재 상태·우선순위 요약으로 유지하고 HANDOFF·관련 사용/모델/검증 문서를 동기화한다. 기존 전체 테스트와 후속 회귀 재검사 수를 중복 합산하지 않는다.

- `node scripts/crash-recovery-smoke.mjs`는 자신이 만든 임시 데이터와 POSIX 프로세스 그룹만 SIGKILL합니다. `docs/crash-recovery.md`의 가상 CLI·자동 재전송 방지·수동 편집 보호 검사를 유지합니다. 실제 정전·ARM·외부 AI·네이티브 감독자 검증으로 확대하지 않습니다. 테스트 도구만 바뀌고 앱 입력이 동일하면 검증한 기존 설치본을 유지합니다.

- 0.1.5부터 내장 Git을 사용한다. `docs/bundled-git.md`의 CPU별 빌드·고정 소스 해시·GPL/전체 소스/빌드 스크립트 동봉을 유지한다. `supervisor → forge.git-home → GitRunner`는 누락 시 시스템 Git으로 대체하지 않는다. Git 전용 환경 격리·기존 수집 제한을 유지하며 `FORGE_VERIFY_PUBLIC_GITHUB=true` 설치 복사본 검사로 PATH 없는 실제 공개 예제 수집을 확인한다.

- 전체 E2E 범위·재현·발견한 오류는 `docs/e2e-report.md`를 따른다. `scripts/browser-e2e.mjs`는 동봉 Java/JAR와 가상 CLI·임시 데이터만 쓴다. 실제 AI 테스트로 확대 해석하지 않는다. 네이티브 창 검사는 DOM 존재만 보지 말고 `[open]`과 레이아웃을 기다린 뒤 40px 검사를 유지한다. 실패 진단용 Workspace 이름에도 경로 구분자를 넣지 않는다.

- 0.1.3부터 네이티브 서명 업데이트를 지원한다. `docs/automatic-updates.md`의 배포 순서와 키 보호를 따른다. `~/.config/knowledge-forge/updater.key`를 Git/앱/로그에 넣거나 임의로 재생성하지 않는다. 공개 자산 해시·서명 검증 뒤에만 `updates/preview.json`을 승격한다. 피드/문서만 바뀌면 실행 파일 재빌드는 불필요하다.
- 설치 UI 회귀 검사는 실제 React 버튼과 `mode: same-origin`의 WebKit 요청으로 검증한다. `no-referrer` 정책은 변경 요청 Origin을 null로 만들 수 있으므로 보안 헤더/전송 계약을 함께 확인한다. Origin/session 검증을 완화해 해결하지 않는다. 한글 거부 응답의 UTF-8도 검사한다.

- GitHub 소개 페이지(README)는 비개발자 고객 기준의 짧은 소개·실제 화면·설치법·기술 스택·상세 문서 링크만 담는다. '현재 한계'나 긴 내부 설명은 README에서 제외하고 설치 안내/검증 문서에 보존한다. 미완료 기능을 광고하지 않는다. 사용자 요청 순서가 있으면 소개글 작성을 마친 다음 디자인을 변경한다.

- 사용자 상시 요청: 개발·수정 완료 시 설치 파일도 갱신한다. Intel/Apple Silicon별 빌드·해당 검증·GitHub Release 업로드·체크섬 확인까지 완료 기준에 포함한다. README 소개/사용법이 달라지면 함께 수정하고 UI 변경에는 가상 자료로 실제 화면 스크린샷을 제공한다. 소스만 푸시한 상태를 배포 완료라고 보고하지 않는다. 문서만 수정한 경우에는 실행 파일 재빌드가 불필요한지 명시한다. 공증 제외·ARM 실기기 미검증·외부 AI 별도 승인 원칙은 유지한다.

- 최신 재점검은 `docs/model-audit.md`와 `scripts/model-audit.mjs`를 따른다. 전체 파일 목록·구조 검사와 수동 의미 검토/실행 커버리지는 구분한다. 선택 문맥 고정·승인·실행은 이미 구현되어 있다.
- 사용자는 CLI 설치/경로 입력 없는 제공자 선택·연결 UX를 요청했다. 0.1.2부터의 선택·인증·로그인과 Codex 동봉 빌드는 `docs/provider-connection.md`, 0.1.3 요청 오류 수정은 최신 릴리스 보고서를 따른다. 도구 준비·인증·원격 이용 가능성을 분리하며, 실제 계정·Claude 동봉·배포 완료는 각 검증 전까지 미완료로 표시한다.

- Apple 공증은 사용자 요청으로 현재 범위에서 제외한다. ARM64 지원·교차 빌드·실제 기기 검증 상태는 `docs/apple-silicon.md`를 따른다. Intel 호스트에서 ARM 실행 성공으로 보고하지 않는다.
- `scripts/package-macos.sh`는 `FORGE_MACOS_TARGET`/`FORGE_TARGET_JAVA_HOME`을 지원한다. 공유 `resources/runtime`은 마지막 타깃으로 교체되므로 병렬 패키징하지 않는다. 설치 검증은 명시적 DMG 경로를 사용하며 ARM 정적 검사는 `--static-only`로 구분한다.
- 사용자용 문서 목차는 `docs/README.md`다. 앱 소개·설치·사용·설계·모델·검증 상태를 함께 갱신하며 과거 보고서와 현재 상태를 구별한다.

- 시작할 때 `HANDOFF.md`, `docs/architecture.md`, `docs/phase-plan.md`를 읽는다.
- 쉬운 모델링/매뉴얼은 `docs/modeling.md`, `docs/how-it-works.md`다. 기능 변경 시 실제 코드와 대조해 구현/예정을 구분한다. 사용자 체험은 `docs/desktop-preview.md`의 Mac 설치형 기준 이후다.
- 이어서 개발할 때 `docs/backlog.md`의 미완료 항목과 인수 조건을 확인하고 완료 증거와 함께 갱신한다.
- Phase 1·2, P3-1, P3-2 CLI AI 초안/전송 승인이 구현되어 있다. `docs/cli-ai.md`의 검증/남은 범위를 확인한다. 사람 검토 후 명시적 게시 경로는 `docs/publication.md`를 확인한다. 자동 의미 검증은 없다.
- 사용자 요청으로 Ollama 대신 로그인된 Codex/Claude Code CLI를 사용한다. 현재 코딩 대화가 아닌 새 요청이며 외부 전송은 건별 승인한다.
- SQLite v4 생성 작업 상태·취소·멱등·재시작 복구가 추가되었다. `docs/generation-jobs.md`를 확인한다. smoke의 fake CLI는 테스트 전용이며 실제 AI 성공으로 보고하지 않는다.
- 모델 고정·승인 바인딩·CLI 설치 진단은 `docs/cli-settings.md`를 확인한다. 설치 버전 확인을 로그인/원격 연결 성공으로 표시하지 않는다.
- 건별 모델 선택·브라우저 선택 저장·요청 모델 작업 이력은 `docs/model-selection.md`를 확인한다. v5에서 도입했으며 과거 모델 미기록 값은 null로 보존한다.
- 후속 CLI 관측 이력은 `docs/cli-observation.md`를 확인한다. CLI 관측은 v6에서 도입했으며 실행 전 관측 버전을 정확한 생성 바이너리 버전으로 단정하지 않는다.
- 현재 스키마는 v21이다. CLI 경로 저장·재시작 적용·승인 binding은 `docs/cli-launch-settings.md`를 따른다. URL 조건부 요청은 `docs/url-conditional.md`, Source opt-in 자동 갱신은 `docs/source-polling.md`를 따른다. v0.1.0-preview.1 DMG는 v18 기준이며 더 최신 DB로 다운그레이드 실행하지 않는다. 문맥 고정 요청은 `docs/context-requests.md`를 따른다. 검토/게시 이력은 불변 DRAFT와 별도이며 변경 근거·편집/게시 충돌을 차단한다.
- AI 결과 비교는 `docs/generation-comparison.md`를 확인한다. 생성 당시/현재/최초 결과를 읽기 전용 조회한다. 거절·재검토 이력은 `docs/proposal-decisions.md`에 구현했다. 명시적 전체 채택은 `docs/proposal-acceptance.md`에 구현했다. 동일 근거의 수동 부분 반영은 `docs/proposal-merge.md`를 따르며, 현재 수집본과 일치하는 근거의 번호 통합은 `docs/evidence-merge.md`에 구현했다. 오래된 근거를 새 원본으로 교체하는 재연결은 아직 없다.
- P3-4 출처 탐색 연결은 `docs/evidence-navigation.md`를 확인한다. 원본 해석은 Source ID+경로 또는 업로드 ID이며 독립 지식 폴더·카드 배치는 `docs/knowledge-folders.md`를 확인한다.
- 기능 하나마다 유스케이스·포트·어댑터와 실제 테스트를 완성한다.
- 사용자는 완성까지 연속 진행하도록 요청했다. 기능마다 멈추지 말고 검증·문서·푸시 후 다음 항목으로 이어간다. 외부 AI 실제 전송과 배포 자격 증명은 별도 승인 경계다.
- 이전 문맥의 읽기 전용 미리보기는 `docs/conversation-context.md`, 명시적 고정 저장·승인·실행은 `docs/context-requests.md`를 따른다. 저장 전 문맥 해시로 기존 단일 질문을 승인할 수 없다.
- Source 파일 ID 보존·변경 분석은 `docs/incremental-snapshots.md`, Git 변경 blob 재사용은 `docs/git-incremental.md`, 설치형 토큰/수명주기는 `docs/desktop-backend.md`를 따른다. 영속 Git fetch/diff 캐시나 실제 GitHub 성능 검증으로 확대 해석하지 않는다.
- Intel Mac 미리보기 패키지와 검증은 `docs/desktop-report.md`, 재현은 `docs/macos-build.md`를 따른다. 공증·Apple Silicon·실제 AI 검증으로 확대 해석하지 않는다. 빌드 중인 JAR/앱 번들을 실행 검증과 동시에 덮어쓰지 않는다.
- 질문별 이력 조회는 `docs/answer-history.md`, v17 완료 답변 보관함은 `docs/conversations.md`를 따른다. 보관함의 정리 순서를 실제 문맥 전송 턴으로 취급하지 않는다. 보관함 조회 자체는 새 AI 호출·승인·이전 문맥 전송을 하지 않는다. 이어 질문은 별도 `docs/context-requests.md` 경로다.
- Codex 스트림은 `docs/codex-streaming.md`의 전용 프로필 opt-in 실행기까지 답변 작업에 연결했다. 기본 exec/지식 생성 경로는 유지하며 실패 시 자동 재전송하지 않는다. 가상 CLI 테스트를 실제 서비스 생성 성공으로 보고하지 않는다. 선택 문맥 기반 다중 대화는 `docs/context-requests.md`에 구현되어 있다.
- 지식 Markdown 표시 보안 경계는 `docs/markdown-rendering.md`를 따른다. 링크/이미지 자동 로딩이나 raw HTML 실행을 추가하지 않는다.
- Java 21, Spring Boot 4.1.x, React/TypeScript/Vite, SQLite JDBC를 유지한다.
- 도메인 모델은 Spring, HTTP, JDBC를 참조하지 않는다.
- 후속 검색은 Lucene 10 + Nori 및 심볼/관계 인덱스를 사용한다. 외부 DB 서버는 추가하지 않는다.
- `docs/relations.md`의 관련 자료는 동일 선언 이름/종류·공통 인용 위치 연결이다. 함수 호출·의미 관계 또는 자동 답변 근거 확장으로 보고하지 않는다.
- 원본 검색 미리보기의 본문 위치/행 범위는 `docs/search-evidence.md`를 따른다. 확정 인용이나 최신성 보증으로 취급하지 않는다. 게시 지식 통합 검색·v13 큐·초안 제외·도메인/최신성 규칙은 `docs/published-search.md`를 따른다.
- 답변용 근거 묶음의 v14 로컬 보관·불변 인용·해시·제외 규칙·삭제 정책은 `docs/evidence-bundles.md`를 따른다. 저장은 전송 승인이 아니며 후속 AI 전송 전 재검증을 생략하지 않는다.
- 읽기 전용 재검증·모델별 답변 요청 미리보기는 `docs/bundle-validation.md`를 따른다. 미리보기 digest는 승인이 아니다. v15 건별 승인·철회·만료는 `docs/answer-approval.md`, v16 실제 답변 작업·일회 소비·재검증·중단은 `docs/answer-jobs.md`를 따른다. 요청문은 V2이며 오래된 승인은 재확인한다. 선택 문맥의 이어 질문은 `docs/context-requests.md`를 따른다. fake CLI 성공을 실제 외부 모델 검증으로 보고하지 않는다.
- 후속 읽기 전용 작업 관측 SSE API는 `docs/answer-events.md`, 화면은 `docs/answer-events-ui.md`, Claude 미검증 임시 출력은 `docs/partial-answer.md`를 따른다. 임시 글은 메모리만 사용하고 실패·중단 때 폐기한다. 재연결로 생성/전송하지 않으며 관측 종료와 명시적 작업 취소를 구분한다.
- 특정 업무 도메인을 하드코딩하지 않는다. 테스트는 `fixtures`의 가상 자료만 사용한다.
- 원본과 지식 수명주기를 분리하고, 검증 후 게시와 수동 편집 보호 원칙을 지킨다.
- 수집은 읽기 전용, 폴링은 Source별 opt-in, 외부 LLM 전송은 명시적 승인으로 제한한다.
- GitHub Actions Workflow를 생성·수정하지 않는다.
- 자격 증명, 실제 회사 자료, 로컬 DB, 빌드 캐시를 커밋하지 않는다.
- 백엔드 검증: `bash scripts/maven.sh test`
- 프런트엔드 검증: `cd frontend && npm test && npm run build`
- 통합 빌드: `bash scripts/build.sh`
- 패키지 검증: `node scripts/smoke.mjs` (빌드된 JAR 필요, 임시 로컬 포트 사용)
- 기능 완료 후 `HANDOFF.md`, API/아키텍처 문서, 테스트 결과를 실제 상태로 갱신한다.
