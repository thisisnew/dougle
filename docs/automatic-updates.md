# 앱 업데이트

0.1.3부터 앱이 시작될 때 새 버전을 확인합니다. 새 버전이 있으면 안내에 따라 다운로드하고, 작성 중인 내용을 저장한 뒤 **설치 후 다시 시작**을 누르세요.
Mac 상단 메뉴 **Knowledge Forge → 업데이트 확인…**에서도 확인할 수 있습니다.

0.1.2 이하에는 이 기능이 없으므로 최신 설치 파일을 한 번 직접 설치해야 합니다. 0.1.3 이상에서는 앱 안에서 업데이트합니다.
인터넷이 없거나 업데이트를 미루어도 기존 앱과 자료는 그대로 사용할 수 있습니다.

## 업데이트하는 순서

1. 앱이 버전 정보만 확인합니다. 원본 자료나 AI 대화는 보내지 않습니다.
2. **다운로드**를 선택하면 Mac 종류에 맞는 업데이트를 받습니다. 진행률은 창 제목에 표시합니다.
3. 서명과 버전을 확인합니다. 파일이 바뀌었거나 버전이 맞지 않으면 설치하지 않습니다.
4. **설치 후 다시 시작**을 누르면 저장 서비스를 종료하고 앱을 교체합니다. 자료 폴더는 별도로 보존합니다.

Apple 공증과 업데이트 서명은 별개입니다. 현재 Apple 공증은 제외합니다. 쓰기 권한이 필요한 설치 위치에서는 macOS가 권한을 요청할 수 있습니다.

## 개발·배포

```mermaid
flowchart LR
  A[앱 시작 또는 업데이트 메뉴] --> B[공개 버전 정보 확인]
  B --> C[사용자가 다운로드 선택]
  C --> D[Mac 종류에 맞는 파일]
  D --> E[공개 키로 서명과 버전 검증]
  E --> F[사용자가 설치 확인]
  F --> G[저장 서비스 종료]
  G --> H[앱만 교체하고 다시 시작]
  H -. 그대로 보존 .-> I[별도 자료 폴더]
```

공식 [Tauri Updater](https://v2.tauri.app/plugin/updater/)를 Rust에서만 사용합니다. 원격 화면에 새 IPC 권한을 열지 않습니다.
공개 키를 앱에 고정하고 `requireSignedVersion`을 사용합니다. 앱은 프로젝트의 GitHub HTTPS 패키지만 받습니다.
기본 버전 비교를 유지하여 구버전 자동 설치를 막습니다. [API 계약](https://docs.rs/tauri-plugin-updater/2.14.0/tauri_plugin_updater/struct.Update.html).

- 개인 서명 키: `~/.config/knowledge-forge/updater.key`. 현재 Mac에만 저장하며 Git이나 설치 파일에 넣지 않습니다.
- 이 키는 별도 안전한 위치에 백업해야 합니다. 다른 빌드 PC에서는 같은 키를 안전하게 옮기고 `FORGE_UPDATER_PRIVATE_KEY`로 경로를 지정합니다. 기존 키를 지우거나 새 키로 덮어쓰지 마세요.
- `scripts/package-macos.sh`는 DMG·서명된 `.app.tar.gz`·`.sig`·체크섬을 만들고 공개 키와 서명된 버전을 재검증합니다.
- 두 CPU의 빌드 후 `node scripts/update-manifest.mjs`로 `release/latest.json`을 만듭니다.
- `bash scripts/verify-macos-update.sh release/Knowledge.Forge_버전_CPU.app.tar.gz 버전 CPU`로 서명과 버전, 압축 해제한 앱의 CPU·ad-hoc 서명·현재 JAR 일치를 검사합니다. CPU는 `x86_64` 또는 `arm64`입니다.
- 검증된 파일을 새 GitHub Release에 공개한 다음 `node scripts/update-manifest.mjs --publish`를 실행합니다. 이 명령은 공개 자산의 해시/서명을 확인한 뒤 `updates/preview.json`을 만듭니다.
- 마지막으로 공개 피드 파일을 커밋·푸시합니다. 아직 공개되지 않은 다운로드를 앱에 알리지 않도록 이 순서를 지킵니다. GitHub Actions는 사용하지 않습니다.

개발 실행은 자동 확인하지 않습니다. `node scripts/updater-smoke.mjs`는 임시 키·가상 앱·로컬 서버로 설치와 변조 차단을 검사하며, 실제 설치 앱을 교체하지 않습니다.
