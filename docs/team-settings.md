# 팀 설정 내보내기·가져오기

가상 자료로 새 JAR의 13개 런타임 검사와 실제 브라우저 적용 흐름을 확인했다. [적용 계획 화면](screenshots/team-settings-import-plan.png)은 실제 회사 자료가 아닌 합성 예시다. 다른 계정·다른 Mac에서의 검증 및 설치 파일 배포는 별도다.

팀 설정 JSON은 Workspace 이름, 기본 갱신 주기와 자료 이름·종류만 옮깁니다. 문서·대화·근거·계정 인증은 옮기지 않습니다. URL 경로에도 비밀정보가 들어갈 수 있으므로 URL을 포함한 모든 위치는 `null`로 내보냅니다. 공유 전 표시 이름에 비밀정보가 없는지 직접 확인하세요.

## 가져오기

1. 내보낸 버전 1 JSON을 미리보기로 확인합니다. 기존 Workspace 이름은 최대 120자까지 보존하지만 새 이름은 20자 이내여야 합니다.
2. 새 Workspace 이름과 각 폴더·URL·GitHub 자료의 위치를 직접 지정합니다. 기존 Workspace를 덮어쓰지 않습니다. MySQL 위치·자격 증명은 받지 않으며 해당 자료는 생성하지 않습니다. 적용 결과의 재연결 대상은 플러그인에서 다시 연결한 뒤 직접 추가하세요.
3. 가져오기 계획을 확인하고 명시적으로 적용합니다. 위치는 실제 자료 등록 검증을 통과해야 합니다. 적용은 Workspace·자료·갱신 정책·재시도 기록을 한 트랜잭션으로 저장하며 실패 시 전체 취소됩니다.

적용해도 현재 Workspace를 전환하지 않습니다. 자동 갱신은 꺼진 상태이며 동기화·AI를 실행하지 않습니다. URL의 실제 접근 가능 여부나 로그인 상태를 확인하지 않으므로 이후 사용 시 별도 확인이 필요합니다. 자료별 갱신 설정, 분류, 문서 내용, 설치 파일은 이전 대상이 아닙니다.

## API 계약

기존 로컬 API의 세션·Origin·클라이언트 헤더 검사를 사용합니다. JSON 요청은 최대 256 KiB, 자료는 최대 100개입니다.

| 경로 | 요청·결과 |
|---|---|
| `GET /api/team-settings/export/{workspaceId}` | 버전 1 설정 JSON |
| `POST /api/team-settings/import-preview` | 설정 JSON → 충돌·위치 지정·이름 변경 안내; 저장 없음 |
| `POST /api/team-settings/import-plan` | `{settings, workspaceName, mappings:[{sourceIndex,location}]}` → `previewId`, `digest`, `expiresAt`, 생성 수, 재연결 대상 |
| `POST /api/team-settings/import-apply` | `{requestId,previewId,digest}` → `workspaceId`, `sourceIds`, `reconnectSourceIndices`, `pollingEnabled:false` |

`sourceIndex`는 설정 자료 목록의 0부터 시작하는 번호입니다. MySQL 항목에는 매핑을 보내지 않습니다. 새 적용의 `requestId`는 UUID이며 재시도에서는 동일한 요청 전체를 사용하세요. 완료 기록은 재시작 후에도 유지되며 같은 ID에 다른 확인 값을 보내면 거부합니다. 결과 Workspace를 삭제한 뒤 재시도해도 원래 결과만 반환하고 복원하지 않습니다. 적용 전 계획은 10분 뒤 또는 백엔드 재시작 시 만료되므로 다시 확인해야 합니다.

## 재현 가능한 런타임 검사

마이그레이션과 JAR 빌드가 안정된 뒤 저장소 루트에서 실행합니다. 실행 중 JAR를 교체하지 마세요.

```sh
node scripts/team-settings-smoke.mjs
```

Java 21과 `backend/target/knowledge-forge-0.1.0.jar`가 필요합니다. 스크립트는 임시 DB·가상 자료 폴더·독립 JVM 홈을 사용하고 실제 사용자 DB나 인증을 복사하지 않습니다. 외부 AI 실행 경로는 존재하지 않는 임시 경로로 지정합니다. 검색 모델 다운로드·동기화·AI API는 호출하지 않습니다. 모델 준비 오류는 이 메타데이터 검사에 필요한 검색 모델이 없기 때문이며 검색 검증 성공을 뜻하지 않습니다.

내보내기 제외 항목, 미리보기, 실제 적용, 새 Workspace, 갱신 비활성화, 빈 수집 내용·AI 작업 목록, 재시도·재시작, 잘못된 입력 거부, 원자적 취소와 덮어쓰기 방지를 확인합니다. 종료 시 백엔드와 임시 자료를 정리합니다. OS 수준 네트워크 차단은 제공하지 않습니다. 실제 서비스 계정·ARM·사용자 데이터 이전이나 E49/E50 전체 통과를 증명하지 않습니다.

이 스크립트는 작성 시점에 실행하지 않았습니다. 실제 실행 결과를 확인하기 전 런타임 통과로 보고하지 마세요.
