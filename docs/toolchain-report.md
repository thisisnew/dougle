# 개발 도구 갱신 및 문서 대조 기록

2026-10-21, macOS Intel / Node 24.2.0 / Java 21.0.10.

## 변경

- Vite 7.1.7 → **7.3.7**, Vitest 3.2.4 → **4.1.12**. 정확한 버전과 lockfile을 함께 고정했습니다.
- 기존 npm audit 경고 4개 → **전체 의존성 0개**. `audit fix --force`나 peer 검사 우회는 쓰지 않았습니다.
- 백엔드 API/DB v8, UI 동작은 바꾸지 않았습니다. 기존 테스트를 그대로 실행했습니다.
- [모델링](modeling.md), [쉬운 매뉴얼](how-it-works.md), [설치형 체험 기준](desktop-preview.md)을 추가했습니다.

## 실행 결과

| 확인 | 결과 |
|---|---|
| `npm ci --ignore-scripts` | npm 11.3.0에서 lockfile 재현 설치 성공 |
| `npm audit --json` | 0개 |
| `npm test -- --maxWorkers=2` | 11파일, 60개 통과 |
| `npm test` | 기본 병렬 설정도 11파일, 60개 통과 |
| `npm run build` | TypeScript/Vite 통과, JS 456.91 kB (gzip 143.34 kB) |
| `bash scripts/maven.sh clean verify` | 125개 통과, 실제 CLI opt-in 1개 skip, JAR 빌드 성공 |
| `node scripts/smoke.mjs` | 패키지 UI·수집·검색·재시작·근거/버전/게시/폴더 보존 통과 |
| 문서 링크 검사 | 새 문서의 로컬 링크 22개 대상 존재 확인 |

JAR 측정 (2026-10-21T12:35:41.412Z): 시작 5,705 ms, 재시작 6,269 ms,
목록 p95 4.66 ms, 검색 p95 26.44 ms. [원시 결과](performance-phase3-foundation.json).
100개 가상 파일의 순차 HTTP 결과입니다. 부하가 통제된 비교 실험이 아니므로
이전 대비 속도 차이를 도구 갱신 효과로 단정하지 않습니다. AI 경로는 fake CLI이며 공개 원격 수집은 생략했습니다.

최초 npm 11.3.0의 `install`과 `install --package-lock-only`는 내부 `edgesOut` 오류로 실패했습니다.
`npx --yes npm@11.6.2 install --ignore-scripts`로 해결했고 전역 npm은 바꾸지 않았습니다.
그 후 기존 npm 11.3.0의 `ci`도 통과했습니다. 설치 과정의 whatwg-encoding 사용 중단 경고는 남아 있습니다.

공식 변경 안내: [Vitest 4 마이그레이션](https://v4.vitest.dev/guide/migration.html),
[Vite 7.3.7](https://github.com/vitejs/vite/releases/tag/v7.3.7).
audit은 조회 당시 알려진 npm 경고 검사이며 앱 전체의 안전성이나 Maven 의존성 검사를 뜻하지 않습니다.

## 문서 대조에서 확인한 핵심

- 원본·지식·근거 복사본은 서로 다른 데이터입니다. 원본 삭제가 지식 삭제를 뜻하지 않습니다.
- DRAFT 본문과 검토/게시 이력은 별도입니다. 상태 열만 보면 게시 여부를 잘못 판단할 수 있습니다.
- 자동 폴링 설정 열은 있지만 실행 기능은 없습니다. 색인 갱신 주기와 혼동하지 않도록 명시했습니다.
- Git revision이 다르면 전체 스냅샷 교체입니다. 아직 변경 파일만 갱신하지 않습니다.
- 지식 Markdown은 DB에 저장합니다. `.md` 파일을 자동 내보내는 기능은 없습니다.
- AI 결과 형식 검사를 자동 사실 검증이라고 부르지 않습니다. 사람 검토·게시가 별도입니다.

모델링 문서의 대조표에 실제 서비스/스키마 파일을 연결했습니다.
실제 외부 AI 호출, 브라우저 시각 QA, 설치형 검증은 이번 작업에서 실행하지 않았습니다.
