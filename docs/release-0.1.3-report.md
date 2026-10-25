# 0.1.3 — 연결 오류 수정과 앱 업데이트

## 무엇이 달라졌나

- Workspace 생성과 AI 연결 상태 확인이 실제 설치형 창에서 실패하던 요청 정책을 수정했습니다.
- 한글 거부 메시지가 물음표가 되지 않도록 UTF-8을 명시했습니다.
- 선택창·입력창 높이와 테두리·화살표를 통일하고 AI 연결창을 정돈했습니다.
- 시작 시/상단 메뉴에서 새 버전을 확인하고 서명 검증 후 앱을 교체합니다. DB는 v21 그대로입니다.

## 오류 원인과 재현

`desktopTransport`는 `mode: same-origin`으로 변경 요청을 보냅니다. 이전 응답의 `Referrer-Policy: no-referrer`와 조합하면 실제 WebKit의 POST Origin이 문자열 `null`이 되어 보안 필터가 403으로 차단했습니다. 거부 응답의 인코딩도 명시하지 않았습니다.

같은 WebKit smoke에 이전 정책을 적용해 **Origin null·실패**를 재현하고, `same-origin` 정책으로 바꾼 뒤 **정상 Origin·통과**를 확인했습니다. Origin null·외부 Origin·다른 포트·세션 없는 요청은 여전히 차단합니다. 정책 동작은 [Fetch 표준](https://fetch.spec.whatwg.org/#append-a-request-origin-header)과 대조했습니다.

기존 smoke는 직접 API를 기본 fetch 모드로 호출해 실제 UI의 전송 조건을 놓쳤습니다. 이제 **실제 React Workspace 생성·한글 이름 입력·AI 연결 확인 버튼**을 누르고, 설치 복사본의 WebKit에서 결과를 검사합니다.

## 실행 결과

| 검사 | 결과 |
|---|---|
| Maven clean verify | 366건 중 365 통과, 실제 외부 AI 1건 제외 |
| UI 전체 / TypeScript·Vite | 201 통과 / 빌드 성공 |
| Rust 단위 / clippy | 16 통과 / all-targets 경고 없음 |
| WebKit 요청 정책 | 이전 정책 실패 재현, 수정 정책 통과 |
| Intel DMG 설치 복사본 | 실제 React Workspace·AI 버튼·한글 오류·40px 선택창, 업로드/다운로드·검색·지식 검토/게시·원본 관계·OFF 폴링·설정 통과 |
| Intel 네이티브 프로세스 | 격리 HOME·최소 PATH, 동봉 Java·세션 보호·중복 실행 거부·종료·같은 DB 재시작 통과 |
| Apple Silicon DMG | 무결성·설치 복사본·앱/Java/Codex CPU 일치·ad-hoc 서명 정적 검사 통과, 실제 ARM 실행 제외 |
| 두 CPU의 실제 업데이트 압축 | 공개 키·서명 버전, 압축 해제한 앱 버전·CPU·ad-hoc 서명·현재 JAR 일치 통과 |
| 업데이트 플러그인 설치 | 임시 서명 키·가상 앱에서 설치, 구버전·누락 CPU·변조·서명 버전 불일치 거부, 잘못된 압축의 기존 앱 보존, 자료 보존 통과 |
| 실제 화면 | 가상 자료로 첫 화면·탐색기·AI 연결 촬영, Codex/Claude 선택과 40px/appearance 확인 |

설치본 첫 UI 검사 실패 후 단계별 진단을 추가했습니다. WebKit이 표준 `appearance` 조회 대신 `-webkit-appearance`를 제공하는 환경이 있어 둘 중 `none`을 확인하도록 검사 코드를 바로잡았습니다. 스타일의 prefixed 선언은 처음부터 포함되어 있었고, 높이 검사는 그대로 유지했습니다.
가상 업데이트 압축의 첫 검사도 macOS tar의 AppleDouble 항목 때문에 실패했으며 fixture에 `COPYFILE_DISABLE=1`을 적용해 다시 통과했습니다. 서명 검증이나 설치 실패 검사를 제외하지 않았습니다.
검증 도구의 상대 경로도 절대 경로로 정규화했습니다. 최종 코드 대조에서 메뉴 항목이 앱 하위 메뉴에 있다는 점을 확인해 상태 표시 조회를 바로잡고, 설치본을 다시 빌드·검사했습니다.

격리 환경 시작 측정은 첫 빌드 **20,832ms**, 최종 메뉴 수정 빌드 **42,853ms**입니다. [최종 측정 원본](native-host-report.json). CPU를 사용하는 교차 빌드와 함께 수행한 개발 Mac의 단일 측정들이므로 일반 성능을 보장하지 않습니다.
전체 코드/빌드 설정 파일 313개(테스트·fixture 87개)의 목록·해시를 재생성했습니다. [목록](model-audit-inventory.json) · [의미/구조 대조 범위](model-audit.md). 파일 열거는 전체 줄 커버리지 증명이 아닙니다.

## 설치 파일과 공개 확인

[0.1.3 설치 파일](https://github.com/thisisnew/dougle/releases/tag/v0.1.3-preview.1). 보통은 내 Mac에 맞는 DMG 하나만 받습니다. `.app.tar.gz`·`.sig`·`latest.json`은 앱 업데이트용입니다.

| 파일 | 크기(bytes) | SHA-256 |
|---|---:|---|
| `Knowledge.Forge_0.1.3_x86_64.dmg` | 289318663 | `1465e1368c36cbb58779fd0d3ccab74d321e7cf597479ac9e45f6dbdebdb9e27` |
| `Knowledge.Forge_0.1.3_arm64.dmg` | 276473763 | `56f20885230fa9b99944f9dc650ef0dd54079a27dc6c21260a82efe10b55f0bc` |
| `Knowledge.Forge_0.1.3_x86_64.app.tar.gz` | 269663581 | `149de36480bd74eadf0e2bc9dfe9a26a22b9e5e1ea92beb638cbd9df5a7d9eb0` |
| `Knowledge.Forge_0.1.3_arm64.app.tar.gz` | 260665921 | `91180a2c954fa773618ba1e3b7667028da5a8c9879ddd22bf7c080ab550d773b` |

두 CPU에 들어간 JAR는 최종 빌드와 같으며 SHA-256은 `d1a4838b9971d6c6e65515296e8179556961520110239577d51a44b414e73f5e`입니다. 이전 공개 릴리스는 보존합니다.
공개 피드는 모든 자산의 서버 SHA-256·크기를 로컬 파일과 확인한 뒤에만 승격합니다. 설치 파일 재빌드 없이 버전 정보만 마지막 커밋에 추가합니다.

공개 결과: **2026-10-22 21:51 UTC**, 릴리스 자산 10개의 크기·서버 SHA-256과 로컬 파일이 모두 일치합니다. 소스 태그는 `b4fa3e85ecd25a1f40a39f18ce8631794ef08d37`입니다. 피드와 이 공개 기록만 후속 커밋에 반영하며 설치 파일의 코드는 바꾸지 않습니다.

## 재현 명령

```bash
bash scripts/build.sh
bash scripts/desktop-cargo.sh test --locked
TAURI_CONFIG='{"bundle":{"resources":[]}}' bash scripts/desktop-cargo.sh clippy --locked --all-targets -- -D warnings
node scripts/updater-smoke.mjs
bash scripts/verify-macos-package.sh release/Knowledge.Forge_0.1.3_x86_64.dmg
bash scripts/verify-macos-package.sh --static-only release/Knowledge.Forge_0.1.3_arm64.dmg
bash scripts/verify-macos-update.sh release/Knowledge.Forge_0.1.3_x86_64.app.tar.gz 0.1.3 x86_64
bash scripts/verify-macos-update.sh release/Knowledge.Forge_0.1.3_arm64.app.tar.gz 0.1.3 arm64
node scripts/native-host-smoke.mjs
node scripts/update-manifest.mjs
```

## 구분해야 할 범위

Intel 실행과 ARM 교차 빌드·정적 검사는 다릅니다. 실제 ARM Mac 실행·실제 AI 계정 인증/생성은 별도입니다. Apple 공증은 요청에 따라 제외했습니다.
업데이트 설치는 임시 가상 앱으로 검사했으며, 사용자의 기존 설치 위치에서 다음 공개 버전으로 바꾸는 체험은 아직 아닙니다.
0.1.2 이하에는 업데이트 기능이 없으므로 이번 0.1.3만 직접 설치해야 합니다. [설치·백업](macos-install.md) · [업데이트 동작/키 관리](automatic-updates.md).
