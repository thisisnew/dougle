# Dougle 0.1.41 — Atlassian 회사 사이트 응답 호환

수정·회귀 검사를 완료했고 Apple Silicon 패키징을 진행 중입니다. 회사 계정의 실제 연결 성공은 아직 확인하지 못했습니다. 사용자에게 실패 단계·앱 버전·오류 전체 문구를 요청한 상태입니다.

## 확인한 결함

0.1.40은 사이트 목록의 포장은 여러 형태로 읽지만, 각 사이트의 식별자는 `id`, URL은 `https://...`만 인정했습니다. `cloudId`와 `tenant.atlassian.net` 형태의 응답을 넣으면 브라우저 승인 후에도 유효한 사이트가 없다고 거절했습니다. 새 loopback HTTP 재현3건 모두 수정 전 callback400으로 실패했습니다.

Atlassian 공식 [도구 목록](https://developer.atlassian.com/cloud/rovo-mcp/guides/supported-tools/)은 접근 가능한 사이트와 cloudId 조회를 설명합니다. `cloudId`·호스트·products를 담은 실제 응답 형태는 [직접 수신한 응답을 제시한 공개 제보](https://forum.cursor.com/t/atlassian-mcp-createjiraissue-fails-missing-agent-interface-scopes-edit-read-work/172874/7)에서도 확인했습니다. 해당 제보는 회사 환경의 증거가 아니므로 이번 회사 장애와 동일 원인이라고 단정하지 않습니다.

## 변경

- 서버가 승인한 목록에서 `id`와 `cloudId`를 처리하고, Atlassian Cloud 호스트를 정규화합니다. URL의 호스트 대소문자도 정규화합니다.
- 두 식별자가 충돌하거나 URL에 다른 호스트·사용자 정보·포트·쿼리·fragment·임의 경로가 있으면 거절합니다. 사용자 입력 링크만 보고 사이트 승인을 만들어내지 않습니다.
- 사이트 밖의 Jira·Confluence 조회 차단과 읽기 전용 도구 경계를 유지합니다.
- 서비스 연결 단계의 HTTP404를 삭제된 자료로 안내하던 화면 분류를 수정했습니다. 자료 삭제/재등록을 권하는 대신 서비스 연결 오류와 HTTP 코드를 보여줍니다.

## 검사

- 수정 전 회사 사이트 형식3건: 모두 실패 재현.
- 수정 후 Java120건: 실패0/생략0. 목록 파서35, 실제 loopback OAuth/MCP HTTP61, 자료 읽기5, 읽기 전용 경계7, 연결 상태12.
- 화면 오류 안내·SourcePanel49건: 실패0.
- [검사 원장](evaluations/atlassian-0.1.41.json).
- 회사 SSO, 실제 회사 Jira/Confluence 자료 수집: 미검증. 실패 단계 응답 대기.
- 외부 AI 호출0회. 제품 지원 대상은 M 시리즈 Mac만입니다.

## Release verification pending (2026-10-31)

ARM packaging and original local DMG/update signature, architecture and JAR checks passed. Product commit: 35dd7277aeccea88b931cc696f241414e6ede560 (pushed). Product classes compared: 523.

The draft contains repacked containers whose hashes differ from the original local containers. Download, hash/content verification and local update signing are still required. Incomplete downloads must not be used. Public feed remains 0.1.40; 0.1.41 is NOT published.

GitHub API TLS handshakes and an independent apple.com HTTPS connection timed out. Parallel downloads were stopped. Repack run41101226991 and ARM runtime run41102708128 need final result collection; cancellation of duplicate41101686864 also needs confirmation. Remaining: verify/sign final containers, obtain ARM runtime results, prepare five final assets, publish and promote feed, verify the real public ARM upgrade. Actual company SSO/material access remains unverified.
