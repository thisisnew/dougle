# Knowledge Forge 0.1.1 — Intel Mac 미리보기

자료 수집 → 검색 → 지식 검토·게시 → 근거 기반 질문을 로컬 앱에서 사용합니다.

## 이번 추가

- Source별 자동 갱신: 기본 OFF, 예약·실패 재시도·재시작 보존.
- Git 변경 blob 재사용, 웹 ETag/Last-Modified 조건부 수집.
- 같은 Java 선언·공통 출처를 사용하는 관련 자료 탐색.
- 앱에서 Codex/Claude Code 실행 파일 경로 저장. 재시작 후 적용하며 저장만으로 실행하지 않습니다.
- SQLite v18 → v21 업그레이드와 종료 상태 백업 복원 검증.

## 설치

Intel Mac용 `Knowledge.Forge_0.1.1_x86_64.dmg`와 `SHA256SUMS`를 함께 받으세요.
SHA-256: `2d8b07bcad252e061137c83ee01d1a9d9cf81235d334daae80a9c18b59d6fb19`

Java 21 포함. 기존 앱을 종료하고 데이터 폴더 전체를 백업한 다음 교체하세요.
새 DB를 구버전 앱으로 열지 마세요. [설치 설명서](https://github.com/thisisnew/knowledge-forge/blob/main/docs/macos-install.md).

## 검증과 한계

- 백엔드 350 통과, 실제 외부 AI 테스트 1개 제외. UI 195, Rust 12 통과.
- 실제 JAR·동봉 Java 통합 검사, DMG 설치 복사본의 네이티브 화면 검사 통과.
- 업로드·다운로드·검색·지식 버전/게시·관련 자료·CLI 설정·자동 갱신 OFF를 설치 복사본에서 확인.
- 이번 검증은 가상 자료와 가상 CLI 사용. 실제 외부 AI 호환성·요금·성능을 보증하지 않습니다.
- ad-hoc 서명만 적용. Apple 공증/Developer ID 서명, Apple Silicon·Windows 검증은 없습니다.
- AI는 현재 코딩 대화가 아닌 별도 CLI 요청입니다. 별도 설치·로그인 및 건별 전송 승인이 필요합니다.
- 자동 의미/사실 검증, 사설 GitHub 인증, 영속 Git 캐시, 자동 업데이트는 후속입니다.

세부 증거는 [검증 보고서](https://github.com/thisisnew/knowledge-forge/blob/main/docs/release-0.1.1-report.md), 사용법은 [쉬운 매뉴얼](https://github.com/thisisnew/knowledge-forge/blob/main/docs/how-it-works.md)을 확인하세요.
