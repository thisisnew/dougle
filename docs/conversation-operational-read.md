# 대화 운영 조회 연결과 저장 영수증 복구

이 연결은 서버가 보관한 완성된 범위 후보만 사용한다. 클라이언트 SQL·조회 객체는 받지 않는다. 모델/자료의 제안은 실행 승인이 아니며 조회 실행과 로컬 결과 저장에는 각각 표시된 digest의 명시적 동의가 필요하다. 기존 MySQL/Datadog 제한·읽기 전용·취소·비밀정보 제거 동작을 재사용한다.

기본 경로: `/api/workspaces/{workspaceId}/conversation-operational-reads`.

- `POST /preview {scopeId,scopeRevision}`: 서버 범위로 미리보기 생성. `queryPreview`는 한국어 읽기 전용 문자열이며 수동 쿼리 입력 폼이 아니다.
- `POST /{id}/execute {requestId,digest,approved:true}`: 승인한 범위만 실행. `GET /{id}`로 결과 확인, `POST /{id}/cancel`로 취소한다.
- `POST /{id}/capture-preview`: 완료된 결과의 로컬 저장 내용을 고정한다.
- `POST /{id}/capture {requestId,digest,approved:true}`: 첨부 저장 영수증을 반환한다. 이전 답변 승인을 재사용하거나 AI를 호출하지 않는다.
- `GET /{id}/capture-receipt`: **이미 저장된 영수증만** 조회한다. 부모 턴·연결·범위 후보가 바뀌거나 후보가 만료되어도 원래 Workspace 소유권과 저장 첨부의 해시/메타데이터가 일치하면 반환한다. 저장/외부 조회/AI 실행을 다시 하지 않는다. 영수증에는 원래 `threadId`와 `parentBundleId`가 남으며, 새 대화에 자동 첨부하거나 자동 답변해서는 안 된다.

저장 트랜잭션이 끝난 뒤 범위 재검증이 실패하면 capture POST는 오류일 수 있다. bridge는 성공 영수증을 재검증 **전에** 보관하므로 클라이언트는 위 GET으로 복구한다. 영수증이 없거나 프로세스가 재시작되면 404이며, 아직 저장 중일 수도 있으므로 404만으로 미저장을 확정하거나 새 저장을 자동 실행하지 않는다. 첨부가 삭제/변경되면 복구도 거부한다.

실행·저장 조정 상태와 복구 영수증은 **세션 전용**이다. 재시작 복구/영속 멱등성을 주장하지 않는다. 프로세스당 복구 영수증은 최대 64개이며 추가 저장 전에 제한한다. 이미 저장된 첨부 자체는 기존 로컬 저장 정책을 따른다.

각 요청의 authority 확인은 누적 2초 예산과 15초 전체 요청 마감 중 더 짧은 잔여시간을 적용한다. 읽기 도중 checkpoint도 같은 예산을 공유한다. worker는 최대 2개, 대기열은 8개로 제한하며 늦은 확인 결과는 승인에 사용하지 않는다. 이 마감은 authority 확인 경계이며, 기존 provider/로컬 저장 자체 제한을 대체하거나 모든 DB 드라이버의 강제 종료를 보장하지 않는다. pending 전역 monitor 안에서는 DB/외부 제공자 작업을 기다리지 않는다.

## 검증 기록

- 2026-10-27 05:30:28 KST: `bash scripts/maven.sh -Dtest=ConversationOperationalReadServiceTest,ConversationOperationalReadControllerTest,ConversationAuthorityChecksTest test` 성공(16.049초).
- 총 25개 통과, 실패 0, 오류 0, 건너뜀 0: service 19개, controller 3개, authority checks 3개.
- 합성 자료와 mock으로 승인/범위 변경, 취소, monitor 비차단, 저장 후 부모 변경 시 영수증 복구, 소유권/첨부 검증, 누적 확인 예산과 마감을 검사했다. 실제 MySQL/Datadog·외부 AI·패키지 실행 검증은 아니다.
- 실행 로그: `/tmp/dougle-conversation-operational-bridge-focused.log`. Maven test만 실행했으며 JAR 빌드·패키징·설치는 하지 않았다.
