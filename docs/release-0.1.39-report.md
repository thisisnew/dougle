# Dougle 0.1.39 — 회사 인증·소스 폴더 동기화

## 상태

검증·패키징·로컬 설치 완료. 배포 대상은0.1.39 Preview이며 공개 결과는 게시 후 확정한다. 회사 계정 SSO·ARM 실기기 실행·Apple 공증은 미검증이다. 자동 업데이트 피드는 유지한다.

## 변경

- Atlassian MCP의 보호 자원 메타데이터에서 인증 서버를 확인한다. 2026-10-30 공개 응답은 `https://mcp.atlassian.com/v2/mcp`의 인증 서버로 `auth.atlassian.com`의 별도 issuer를 안내한다. 기존 앱의 고정된 MCP 호스트 인증 경로와 달랐다. 지정된 HTTPS 호스트·메타데이터 경로만 허용하고 resource/issuer/PKCE를 검증하며, 인증 서버가 바뀌면 이전 클라이언트 등록을 재사용하지 않는다. 사용자 토큰 발급은 요구하지 않는다.
- 자료 서비스가 승인 이후401을 반환할 때 그 단계를 명시한다. 회사의 허용 정책·계정 권한·실제 SSO 성공은 로컬 합성 테스트로 보장하지 않는다.
- 로컬 코드 폴더의 중첩 `build` 산출물을 경로 검사 전에 제외한다. `build.gradle`과 실제 소스는 유지하며 원본을 변경하지 않는다. 기존 자료는 다시 동기화해야 새 제외 기준이 적용된다.
- 화면의 폴더·웹 동기화는 작업을 시작하고 짧은 상태 조회로 완료를 기다린다. 긴 수집을 한 HTTP 응답에 묶어 두지 않는다. 실행 중 같은 자료는 같은 작업으로 합류하고 이 작업 API의 동시 작업은2개, 기록은128개로 제한한다. 작업 조회는 Workspace·자료에 묶이며 앱 재시작 후 작업 ID는 유지되지 않는다. 통신 단절 시 수집 요청을 자동 재전송하지 않는다.
- WebKit의 `Load failed`를 앱 내부 연결 안내로 바꾸고 사용자 취소는 그대로 유지한다. 회사 화면의 실제 통신 단절 원인은 로그가 없어 확정하지 않는다. 파일 업로드 자체와 보관 파일 일괄 재분석은 별도 HTTP 경로다.
- 명시한 Java 타입 이름이 후보에서 확인되면 해당 이름을 언급하지 않는 약한 코드 후보를 제외한다. 두 타입 비교·이름 없는 자연어 질문·높은 관련성 근거는 보존한다. 전체 관련성 기준을 일괄 높이지 않는다.

## 인증 정보 확인 근거

회사 자료나 인증정보 없이 [공개 보호 자원 정보](https://mcp.atlassian.com/.well-known/oauth-protected-resource/v2/mcp)와 그 정보가 지정한 인증 서버 메타데이터를 직접 조회했다. [Atlassian 공식 설치 안내](https://support.atlassian.com/atlassian-ai-gateway/docs/get-started-with-the-atlassian-remote-mcp-server/) 및 [조직 연결 정책](https://support.atlassian.com/security-and-access-policies/docs/control-atlassian-mcp-server-settings/)을 대조했다. 공개 메타데이터 확인은 회사 계정 인증 성공 증거가 아니다.

새 인증 서버의 계정 확인/갱신 scope와 동적 등록은 [Atlassian 담당자의 안내](https://community.atlassian.com/forums/discussion/3313948/trying-to-connect-atlassian-rovo-mcp-to-gemini-ai)와 대조했다. `read:me read:account offline_access`를 추가하며 업무 자료 쓰기 scope는 요청하지 않는다.

## 동봉 런타임·화면 검사

[실행 기록](evaluations/source-sync-runtime-039.json): 격리 HOME에서 실제 JAR로19개 기존 수집·복구·검색·원본 보존·Workspace 격리 검사를 통과했다. 비동기 동기화4회, 다른 Workspace의 작업 조회 거부, 중첩 build 보고서 검색 제외도 확인했다. 외부 AI 호출0회다.

Ego Chromium에서 같은 Intel JAR의 React 화면으로 동기화를 실행해 작업 시작1회와 상태 조회 후 검색 가능 표시를 확인했다. API 통신 차단 시 기존 자료 유지와 한국어 오류 안내를 확인했다. 이는 합성 자료의 웹 모드 검사이며 네이티브 WebKit/회사 SSO 검사를 대신하지 않는다. [정상 화면](screenshots/company-sync-039.png), [통신 단절 화면](screenshots/company-sync-network-039.png).

## 최초 전체 검사의 실패

[초기 실패 기록](evaluations/portability-0.1.39-initial-failures.json): Java7,947통과·6실패·4생략.5건은 build 산출물 수집을 기대하던 기존 fixture이며, 새 제외 정책과 build.gradle/실제 소스 보존을 함께 검증하도록 수정했다.1건은 격리 Chromium의 CDP 연결 시간 초과다. UI698통과·20실패는 기존 동기화 API fixture를 새 작업 시작/조회 응답으로 수정했다. Intel 네이티브 시작 검사도 빌드·대량 검증 병행 중 시간 초과되어 단독 재검증 대상이다. 이 실패를 통과 수에 합산하거나 회사 연결 성공으로 해석하지 않는다.

## 설치 파일 검사

Intel DMG를 읽기 전용 마운트·복사한 뒤 실제 네이티브 React 화면에서 Workspace, 한글 이름/오류, 업로드·다운로드·검색, 게시·관계·OFF 폴링·Source 메타데이터·CLI 설정을 확인했다. 최초 병행 실행은 시작 시간 초과, 같은 설치본의 단독 실행은 통과했다. 시간 초과의 원인을 부하라고 확정하지 않는다. ARM DMG는 무결성·복사·CPU·검색 모델·서명 정적 검사만 통과했으며 실기기 실행은 아니다. 양쪽 업데이트 아카이브의 서명과 버전도 검사했다. Apple 공증은 하지 않았다.

| 파일 | 크기(bytes) | SHA-256 |
| --- | ---: | --- |
| Dougle_0.1.39_arm64.dmg | 477038317 | 7aab0d9f41a26aa1133581e3f30ed1742c410bd502db8ab6ee5087e0071c8429 |
| Dougle_0.1.39_x86_64.dmg | 486514000 | 0a2c757cf0c212271f3f5f3b99636c260cd2476725af81f49c4374274e3feb97 |

Intel JAR: `91085bf889334e39d85f4e2fe019e2ca475c4a41141dfe78fc08b91965643da3`.
ARM JAR: `b862de81b549b8b3cd844aae80911e1d302cd8a9086e3d2560026d24987dec68`.

## 최종 전체 Java 검사

[케이스별 실행 원장·소스 해시](evaluations/portability-0.1.39-java.json): Java7,953통과·실패0·외부 환경4생략. 이 중 입력 매트릭스5,879개가 모두 통과했다. 합성 Atlassian loopback HTTP50개, 폴더 시나리오50개, 격리 Chromium7개도 포함된다. 앞선 실패 실행과 합산하지 않았다. 생략4개는 실제 AI 생성·사용자 문서 평가·별도 저장소 평가·Notion 실서버 폼 확인이다. 검사 수는 물리 Mac 수나 독립 회사 업무 흐름 수가 아니다.

초기 실패 이후 변경은 동기화/빌드 제외 테스트 fixture다. 브라우저 제품 시간 제한을 늘리거나 실패 항목을 생략하지 않았으며 최종 순차 실행에서 격리 Chromium7개 모두 통과했다. 시간 초과 최초 원인이 부하였다고 확정하지 않는다.

양쪽 패키지 JAR의 제품 클래스520개를 최종 테스트 대상 `backend/target/classes/dev/dougle`와 바이트 단위로 대조해 모두 일치했다. 프런트엔드도 패키징 전에 빌드 산출물7개·1,646,626바이트의 일치를 확인했다.

## 나머지 최종 전체 검사

같은 고정 소스의 순차 실행에서 UI718·Node5,569·Python 집계기5·Rust24개가 모두 통과했다. [요약 기록](evaluations/portability-0.1.39-summary.json). Node 수에는 프로토콜 바이트 분할 조합5,376개가 포함되며 독립 업무 시나리오 수로 해석하지 않는다. 외부 AI 추가 호출은0회다.

## 새 사용자 환경

[별도 HOME6종 실행 기록](evaluations/portability-0.1.39-install.json): 일반 경로와 한글·공백·따옴표 경로 각각에서 도구 없음, 다른 실행 명령 탐색, 회사용 가상 별칭의 선택·즉시 적용·재시작을 모두 통과했다. 동봉 Intel JVM/JAR·검색 모델을 사용했고 외부 AI 호출0회다. 실제 회사 계정 인증과 ARM 실기기 성공을 뜻하지 않는다.

## 로컬 설치

[설치 기록](evaluations/local-install-0.1.39.json): 기존0.1.38 앱과 종료된 SQLite 파일을 비공개 위치에 백업한 뒤 `/Applications/Dougle.app`을0.1.39로 교체했다. 서명·JAR 해시·버전·실행 프로세스·정적 화면HTTP200·비인증 API403을 확인했다. 인증 파일은 복사하지 않았으며 전체 자료 백업으로 표시하지 않는다.
