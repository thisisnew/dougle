# Mac 설치형 빌드

현재는 개발 중인 **로컬 미리보기 배포**입니다. Apple Developer ID 서명·공증 완료 제품이 아닙니다.

## 구성

```text
Tauri 앱
  → 동봉 Java 21로 Spring JAR 실행
  → 매 실행의 비밀 토큰을 stdin으로 전달
  → 인증된 준비 상태 확인
  → 앱 전용 창에서 React 표시
  → 앱 종료 시 stdin을 닫아 서버·DB 종료
```

사용자의 원본 폴더는 읽기 전용입니다. 앱 내부 복사본과 지식은 앱 데이터 폴더에 따로 저장합니다.
AI는 로그인된 별도 Codex/Claude Code CLI가 필요하며, 시작만으로 호출하지 않습니다.

## 빌드하는 사람

macOS, Xcode Command Line Tools, Java 21 JDK, Node/npm, 저장소 로컬 Rust 도구가 필요합니다.
Rust 버전은 `desktop/rust-toolchain.toml`, Tauri CLI는 `desktop/package-lock.json`에 고정합니다.
Rust 설치 시 `CARGO_HOME=.tools/cargo`, `RUSTUP_HOME=.tools/rustup`를 사용하며 전역 셸 설정을 바꾸지 않습니다.

```bash
bash scripts/setup-desktop-rust.sh
bash scripts/package-macos.sh
```

첫 명령은 공식 Rust 설치 프로그램을 내려받아 저장소의 `.tools/`에만 설치합니다. sudo나 셸 프로필 변경은 하지 않습니다. 이미 설치된 고정 버전은 재사용합니다.

기본 명령은 기존 UI/백엔드 테스트와 JAR smoke 후 런타임을 동봉하고 앱/DMG를 빌드합니다.
앱은 Tauri, DMG는 macOS `hdiutil`로 만듭니다. Finder 창 꾸미기 자동화에 의존하지 않습니다.
이미 같은 소스로 검증했다면 `--skip-verify`로 반복 검증만 생략할 수 있습니다.
`--stage-only`는 런타임과 JAR만 준비하며 설치 파일을 만들지 않습니다.

산출물은 `desktop/src-tauri/target/release/bundle/`, 배포 복사본과 SHA-256은 `release/`입니다.
빌드 결과는 Git에 넣지 않습니다. `APPLE_SIGNING_IDENTITY=-`의 ad-hoc 서명만 사용하며 공증 자격 증명을 사용하지 않습니다.
빌드한 호스트와 Java의 아키텍처가 일치해야 합니다. Intel 검증이 Apple Silicon 검증을 대신하지 않습니다.

## 동봉 런타임 검증

```bash
FORGE_SMOKE_DESKTOP_RESOURCES="$PWD/desktop/src-tauri/resources" node scripts/smoke.mjs
bash scripts/desktop-cargo.sh test --locked
bash scripts/verify-macos-package.sh
node scripts/native-host-smoke.mjs
```

첫 명령은 설치형 세션/임의 포트/stdin 종료를 사용하는 실제 동봉 Java로 수집·검색·문서·답변·재시작을 검사합니다.
AI는 테스트용 가상 CLI입니다. 네이티브 창의 설치/다운로드/종료 검증을 대신하지 않습니다.

2026-10-22 동봉 런타임 smoke 통과. [측정과 검사 목록](performance-desktop-runtime.json).
네이티브 React 및 DMG 설치 복사본 검증도 통과했습니다. [최신 보고서](desktop-report.md).

초기 재빌드에서 읽기 전용 Java 라이선스 복사본 때문에 실패했습니다. 생성한 스테이징/캐시 파일의 소유자 쓰기 권한만 보정했습니다. 설치 JDK나 사용자 자료는 바꾸지 않았습니다.
Tauri DMG의 Finder 창 꾸미기 AppleScript도 실패하여, 앱과 Applications 바로가기를 담는 `hdiutil` 경로로 변경했습니다. 보안 권한을 우회하거나 Finder 제어 권한을 추가하지 않습니다.

## 아직 남은 배포 확인

- 실제 앱 창의 파일 다운로드·인증 SSE·종료·중복 실행·장애 복구.
- Java/Node 개발 도구가 없는 환경의 실행과 Finder의 CLI 경로 탐색.
- 앱 종료 상태의 백업/복구 및 기존 개발 데이터 이관 절차.
- Apple Silicon 빌드/검증, Developer ID 서명·공증. Windows는 후속 지원.

참고: [Tauri Mac 번들](https://v2.tauri.app/distribute/macos-application-bundle/),
[공증·서명](https://v2.tauri.app/distribute/sign/macos/).
