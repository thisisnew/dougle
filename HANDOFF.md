# Knowledge Forge — 이어서 작업하기

기준: 2026-10-22, SQLite **v21**, Intel Mac 앱 **0.1.1**.
추가 작업: 사용자 요청으로 **공증은 제외**, Apple Silicon용 앱/Java 교차 빌드 경로와 아키텍처 혼합 방지 검사를 추가했습니다. [ARM 검증 현황](docs/apple-silicon.md).
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
4. Developer ID·공증 자격 증명을 확보한 뒤 정식 Mac 배포. ARM은 별도 기기 빌드·검증, Windows는 후속입니다.
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
