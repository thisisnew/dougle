# Knowledge Forge — 이어서 작업하기

최신 사용자 디자인 요청: 2D 도트게임풍 테마를 개발 소스에 적용했습니다. [디자인/검증](docs/retro-design.md). UI 201개 통과, 빌드 성공. 아래 로컬 0.1.2 DMG는 디자인 변경 전이므로 다시 빌드해야 합니다.

후속 개선: 0.1.2 개발 소스에 **AI 연결**(기본 제공자 선택 기억·명시적 인증 확인·브라우저 로그인·중단), Codex 0.163.0 CPU별 동봉 빌드를 추가했습니다. DB v21 유지. [설계/검증/남은 범위](docs/provider-connection.md). 공개 0.1.1은 이 변경 이전입니다. Claude 동봉 조건은 사용자에게 별도 확인 요청했으며 실제 계정 로그인·추론은 실행하지 않았습니다.

개선 검증: 백엔드 전체 362 통과·실제 AI 1 제외, 이후 추가 HTTP 로그인 검사를 포함한 통합 테스트 2건 통과. UI 201·Rust 13 통과. 실제 JAR + 가상 CLI smoke 및 브라우저 로그인 상태 전환·누락 도구 표시 검증 통과. 설치 패키지의 완료 상태는 별도 기록합니다.
Intel 0.1.2 DMG를 로컬 생성하고 설치 복사본의 동봉 Java/실제 React 실행을 검증했습니다. 아직 공개 릴리스는 0.1.1이며, 새 ARM 패키지와 공개 배포가 남습니다. [체크섬·검사 기록](docs/connection-verification.md).

최신 작업: [모델링 재점검](docs/model-audit.md). 코드/테스트/도구 파일 전체 목록·해시와 구조 계약 테스트를 추가했습니다. 개선 전 전체 빌드: 백엔드 353 통과 + 실제 AI 1 제외, UI 195 통과. 사용자 목표인 제공자 선택형 연결은 기존 CLI 설치 확인과 다르므로 별도 개선합니다.

기준: 2026-10-22, SQLite **v21**, Intel Mac 앱 **0.1.1**.
추가 작업: 사용자 요청으로 **공증은 제외**, Apple Silicon용 앱/Java 교차 빌드 경로와 아키텍처 혼합 방지 검사를 추가했습니다. [ARM 검증 현황](docs/apple-silicon.md).
ARM DMG 생성·설치 복사본 정적 검사 통과. [ARM 공개 미리보기](https://github.com/thisisnew/knowledge-forge/releases/tag/v0.1.1-arm-preview.1). 실제 ARM 기기 실행은 미검증입니다.
쉬운 소개·구조·사용법은 [문서 목차](docs/README.md)에서 연결합니다.
사용자는 기능마다 확인을 기다리지 말고 구현·테스트·문서·GitHub 푸시를 계속하길 요청했습니다.
실제 외부 AI 전송과 배포 자격 증명은 별도 승인 경계입니다.

## 어떤 앱인가

파일·폴더·공개 웹·GitHub를 로컬에 수집하고, 검색하고, 근거가 연결된 지식을 만드는 설치형 서재입니다.
특정 회사/업무 도메인을 하드코딩하지 않습니다. 원본과 지식은 독립적으로 관리합니다.
AI는 Ollama 대신 로그인된 Codex/Claude Code CLI를 사용합니다. 현재 코딩 대화 공유가 아닌 새 요청입니다.

## 구현된 것

| 영역 | 현재 상태 |
|---|---|
| Workspace / 원본 | 생성·전환·삭제, 폴더·업로드·미리보기·다운로드 |
| 수집 / 갱신 | 로컬·공개 HTTPS·공개 GitHub, 수동 동기화, 선택적 예약 폴링 |
| 증분 처리 | 변경 파일 분석, Git blob 재사용, URL 조건부 요청, 실패 시 기존 자료 보존 |
| 검색 | Tika·JavaParser·Lucene/Nori, 다중 도메인, 동일 선언·공통 출처 관련 자료 |
| 지식 | 독립 폴더, 불변 초안 버전, 사람 편집 보호, 검토·명시 게시, 출처 최신성 |
| AI | 근거 우선, 요청 미리보기·건별 승인·작업·취소·SSE, 선택 문맥 고정·이어 질문 |
| 설치형 | Tauri 2 + Java 21 동봉 Intel DMG, 로컬 인증·프로세스 수명주기 |
| 설정 | CLI 경로 저장·재시작 적용·이전 승인 바인딩 무효화 |

## 검증 증거

- 백엔드 **350 통과 + 실제 AI 1 제외**, UI **195 통과**, Rust **12 통과**.
- 실제 JAR·동봉 Java 통합 검사와 DMG 설치 복사본 네이티브 UI 검사 통과.
- v18 → v21 자료 보존 업그레이드·종료 상태 전체 백업 복원 통과.
- [최신 보고서](docs/release-0.1.1-report.md), [네이티브 결과](docs/native-host-report.json).
- 설치 산출물: `release/Knowledge.Forge_0.1.1_x86_64.dmg`와 `release/SHA256SUMS` (Git 제외).
- [0.1.1 공개 배포](https://github.com/thisisnew/knowledge-forge/releases/tag/v0.1.1-preview.1)와 서버 자산 SHA-256을 확인했습니다. 0.1.0은 DB v18의 이전 빌드입니다.

## 다음 작업 / 한계

1. 0.1.1 공개 배포·체크섬·README/설치 안내 동기화 완료. 다음은 사용자 설치 체험과 아래 별도 검증입니다.
2. 사용자의 Intel Mac에서 가상 자료로 첫 체험. Finder/Gatekeeper 실제 허용은 별도입니다.
3. 명시 승인 후 실제 Codex/Claude 계정으로 최종 호환성·스트리밍 확인. 가상 CLI 검증을 실제 AI 성공으로 표현하지 않습니다.
4. 공증은 사용자 요청으로 제외. ARM 교차 빌드/공개 패키지는 제공하며 실제 Apple Silicon 기기 실행·설치 회귀가 남습니다. Windows는 후속입니다.
5. 선택적 확장: 사설 저장소 인증, 영속 Git 캐시/브랜치, 더 강한 관계 검색, 오래된 근거 재연결, 자동 업데이트. [전체 백로그](docs/backlog.md).

## 개발과 검사

```bash
bash scripts/build.sh
bash scripts/run.sh
node scripts/smoke.mjs
node scripts/cli-settings-smoke.mjs
node scripts/polling-smoke.mjs
node scripts/relations-smoke.mjs
bash scripts/desktop-cargo.sh test --locked
bash scripts/verify-macos-package.sh
node scripts/native-host-smoke.mjs
```

Java 21·Node 22.12+·Git 필요. 개발 주소 `http://127.0.0.1:8787`.
Mac 빌드 상세는 [빌드 설명서](docs/macos-build.md). 실행 중인 JAR/앱 번들을 재빌드로 덮어쓰지 않습니다.
테스트는 임시 데이터와 `fixtures`만 사용하며 실제 사용자 앱 데이터는 건드리지 않습니다.
새 DB를 구버전 앱으로 열지 않습니다. 먼저 앱 종료 후 데이터 폴더 전체를 백업합니다.
회사 GitHub Actions·외부 저장소는 수정하지 않습니다. 비밀정보·DB·빌드 산출물을 커밋하지 않습니다.

읽기 순서: [쉬운 사용법](docs/how-it-works.md) → [설계도](docs/design-map.md) → [모델/코드 대조](docs/modeling.md) → [API](docs/api.md).
