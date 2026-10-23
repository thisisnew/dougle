# Apple Silicon 빌드와 확인

최신 0.1.4는 ARM64 Codex 동봉·서명된 앱 업데이트를 유지하고 제공자별 모델 복원 오류를 수정합니다. [현재 설치본·체크섬·검증](release-0.1.4-report.md). 아래 표와 0.1.1 체크섬은 이전 배포의 기록이며 실제 ARM 기기 실행은 여전히 미검증입니다.

공증은 사용자 요청에 따라 이번 범위에서 제외합니다. Intel 앱을 Rosetta로 실행하는 방식이 아니라 **ARM64 앱 + ARM64 Java 21**을 묶습니다.
DB v21과 기능은 Intel 0.1.1과 같습니다. 앱 이름·데이터 위치도 동일하므로 두 앱을 동시에 실행하지 마세요.

## 이번 실제 결과 (2026-10-22)

| 확인 항목 | 결과 |
|---|---|
| Rust ARM 대상 check / release / Tauri 번들 | 통과 |
| ARM JDK 다운로드 무결성 | 공식 SHA-256 일치 |
| ARM 앱·java·libjli·libjvm | 모두 arm64, 일치 검사 통과 |
| SQLite JDBC | JAR 내 Mac/aarch64 네이티브 드라이버 포함·arm64 확인 |
| DMG 무결성·읽기 전용 마운트·설치 복사본·ad-hoc 서명 | 통과 |
| 아키텍처 오류 방어 테스트·Intel DMG 정적 회귀 | 통과 |
| Intel에서 ARM 런타임 검증 요청 | 실행하지 않고 명확한 오류로 거부함을 확인 |
| 기존 네이티브 단위 테스트 | Intel 호스트에서 12개 통과 |
| 실제 Apple Silicon 실행·성능·Gatekeeper | **미검증 — 실제 ARM Mac 필요** |

산출물: `Knowledge.Forge_0.1.1_arm64.dmg`, 164,655,958바이트.
SHA-256: `ff03267107138100f0ee7c3cd0ee0a711298978770a1ee27f86d26b31f118816`.
기존 검증된 UI/JAR를 그대로 사용했으며 백엔드·프런트 기능 코드는 변경하지 않았습니다.
공증 없이 배포하는 실험적 ARM 미리보기이며 정적 검사를 실행 성공으로 표현하지 않습니다.
[ARM 설치 파일과 체크섬](https://github.com/thisisnew/knowledge-forge/releases/tag/v0.1.1-arm-preview.1) · [Mac별 설치 안내](macos-install.md).

## 빌드

Apple Silicon Mac에서는 ARM64 Java 21 JDK를 설치하고 기존 명령을 사용합니다.

```bash
bash scripts/setup-desktop-rust.sh
bash scripts/package-macos.sh
```

Intel에서 교차 빌드할 때는 **같은 버전의** Intel/ARM64 JDK가 필요합니다.
호스트의 jlink가 대상 JDK의 jmods로 ARM 런타임을 만듭니다. ARM Java를 Intel에서 실행하지 않습니다.

```bash
export CARGO_HOME="$PWD/.tools/cargo" RUSTUP_HOME="$PWD/.tools/rustup"
(cd desktop && ../.tools/cargo/bin/rustup target add aarch64-apple-darwin)
FORGE_MACOS_TARGET=aarch64-apple-darwin \
FORGE_TARGET_JAVA_HOME="/absolute/path/to/arm-jdk/Contents/Home" \
bash scripts/package-macos.sh
```

`FORGE_PACKAGE_JAVA_HOME`은 실행할 수 있는 호스트 JDK 경로입니다. 기본값은 설치된 Java 21입니다.
대상과 호스트 버전 또는 대상 아키텍처가 다르면 스테이징 전에 실패합니다.
GitHub Actions는 추가하지 않습니다. 생성된 공용 resources를 사용하므로 두 아키텍처 빌드를 동시에 실행하지 마세요.

## 검증 범위

- `bash scripts/macos-architecture-test.sh`: 실제 Mach-O 테스트 객체로 ARM/Intel 구분, 혼합 JVM·잘못된 아키텍처·universal·없는 파일 거부.
- 패키징 시 Tauri 실행 파일·Java 실행 파일·libjli·libjvm이 같은 아키텍처인지 검사합니다.
- DMG 이름은 빌드 PC가 아니라 **앱 바이너리**의 아키텍처에서 정합니다.
- `bash scripts/verify-macos-package.sh --static-only <ARM-DMG>`: 무결성·서명·읽기 전용 마운트·설치 복사본·아키텍처만 검사합니다. 실행 성공이 아닙니다.
- Apple Silicon Mac에서 `bash scripts/verify-macos-package.sh <ARM-DMG>`를 실행해야 네이티브 화면과 동봉 Java 검증이 됩니다.
- 이어서 `node scripts/native-host-smoke.mjs '<ARM 앱 경로>'`로 시작·중복 실행·종료·재시작을 확인합니다.

## 실제 기기에서 확인할 것

1. 종료 상태에서 기존 데이터 폴더 전체를 백업합니다.
2. ARM64 DMG를 설치하고 Workspace·가상 파일·검색을 확인합니다.
3. 지식 편집·검토·게시 후 앱을 껐다 켜서 자료 보존을 확인합니다.
4. CLI 경로는 해당 Mac에 설치된 실행 파일로 다시 지정합니다. 실제 외부 AI 전송은 별도 승인합니다.

공증·실제 ARM 기기 실행·실제 AI 성공은 각각 별개입니다. 교차 빌드만으로 모두 검증됐다고 표시하지 않습니다.

JDK 출처: [Amazon Corretto 21.0.10.7.1 공식 릴리스](https://github.com/corretto/corretto-21/releases/tag/21.0.10.7.1).
ARM 아카이브 SHA-256: `85541edd1793bce224aab4ac6d1fa80e84bf31bf9b64b5c5384e48b6ef07c76c`.
