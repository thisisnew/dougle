# Mac 설치·사용 — 짧은 안내

**Intel과 Apple Silicon용 파일이 다릅니다.** Apple 공증은 사용자 요청으로 제외했습니다.
Intel은 설치 복사본 실행 검증을 통과했습니다. Apple Silicon은 교차 빌드·정적 검사만 통과했으며 **실제 ARM Mac 실행은 미검증**입니다.
Windows용 설치 파일은 없습니다. [Intel 검증](release-0.1.1-report.md) · [ARM 검증](apple-silicon.md).

## 설치

Apple 메뉴 → **이 Mac에 관하여**에서 칩/프로세서를 확인하세요.

| 내 Mac | 받을 파일 | 다운로드 |
|---|---|---|
| Intel 프로세서 | `Knowledge.Forge_0.1.1_x86_64.dmg` + `SHA256SUMS` | [Intel 미리보기](https://github.com/thisisnew/knowledge-forge/releases/tag/v0.1.1-preview.1) |
| Apple M 시리즈 칩 | `Knowledge.Forge_0.1.1_arm64.dmg` + `SHA256SUMS-arm64` | [Apple Silicon 실험적 미리보기](https://github.com/thisisnew/knowledge-forge/releases/tag/v0.1.1-arm-preview.1) |

DMG와 해당 체크섬 파일을 같은 폴더에 받습니다.
Intel은 `shasum -a 256 -c SHA256SUMS`, ARM은 `shasum -a 256 -c SHA256SUMS-arm64`로 확인할 수 있습니다.
이 설치 파일은 DB v21 기준이며 Source 자동 갱신·조건부 수집·관련 자료·CLI 경로 설정을 포함합니다.

1. 제공된 DMG와 SHA-256을 확인합니다. 모르는 사람이 다시 올린 파일은 사용하지 마세요.
2. DMG를 열고 **Knowledge Forge.app**을 Applications로 복사합니다.
3. 복사한 앱을 엽니다. Java는 앱에 들어 있으므로 별도로 설치하지 않습니다.
4. 첫 체험은 가상 자료로 합니다. 실제 회사 자료는 회사의 허용 범위부터 확인하세요.

기존 설치가 있다면 **앱 종료 → 데이터 폴더 전체 백업 → 앱 교체** 순서입니다.
Intel/ARM 앱은 같은 데이터 위치를 사용하므로 동시에 실행하지 마세요. 다른 PC에서는 Source·CLI 절대 경로도 다시 확인합니다.

미공증 앱은 macOS가 막을 수 있습니다. 출처와 파일 무결성을 직접 확인한 경우에만
시스템 설정 → 개인정보 보호 및 보안의 해당 앱 허용 절차를 검토하세요.
보안 기능 전체 끄기나 격리 속성 일괄 삭제는 권장하지 않습니다.
[Apple 공식 설명](https://support.apple.com/en-au/102445).

## 처음 할 일

1. Workspace를 만듭니다.
2. 파일을 올리거나 **Source 등록 → 수동 동기화**를 실행합니다.
3. 단어를 검색해 원문·출처를 확인합니다.
4. 지식 문서는 초안으로 만들고, 사람이 확인한 뒤 명시적으로 게시합니다.
5. AI 답변은 **근거 묶음 → 요청 확인 → 승인 → 실제 전송** 순서입니다.

Codex/Claude Code는 따로 설치·로그인해야 합니다. 현재 코딩 세션을 앱이 공유하는 것은 아닙니다.
Finder에서 CLI를 찾지 못하면 상단 **CLI 경로 설정**에 실행 파일의 절대 경로를 저장하고 앱을 재시작하세요. [설정 안내](cli-launch-settings.md).
AI 사용 전까지 파일 보관·수동 지식·검색은 외부 AI가 필요하지 않습니다.
GitHub 수집에는 Git 실행 환경이 필요합니다. 자동 폴링은 Source별 기본 OFF이며 켠 경우 앱이 실행 중일 때만 동작합니다.

자세한 기능은 [쉬운 사용법](how-it-works.md), 문맥 이어 묻기는 [별도 안내](context-requests.md)를 보세요.

## 데이터와 백업

데이터는 보통 다음 폴더에 있습니다. 앱이나 원본 폴더 안이 아닙니다.

```text
~/Library/Application Support/dev.knowledgeforge.desktop
```

- **앱을 완전히 종료한 뒤** Finder의 이동 → 폴더로 이동에서 위 경로를 엽니다.
- 폴더 전체를 다른 위치에 복사합니다. DB 파일 하나만 복사하지 마세요.
- 복구할 때도 앱을 종료합니다. 현재 폴더를 먼저 다른 이름으로 보관한 뒤 백업 폴더를 복사합니다.
- 새 버전에서 연 데이터를 구버전 앱으로 열지 마세요. 현재 DB는 v21이며 역마이그레이션은 없습니다.
- 개발용 저장소의 `.data`는 자동으로 가져오지 않습니다. 실행 중 데이터 이동은 금지합니다.

앱 삭제는 Applications의 앱만 휴지통으로 옮깁니다. 데이터는 남겨 둡니다.
데이터까지 지우는 작업은 필요한 백업을 만든 뒤 사용자가 별도로 결정하세요.

## 문제가 생기면

- 시작 실패: 다른 Knowledge Forge 창을 닫고 저장 공간을 확인합니다.
- 서버 종료 안내: 앱을 닫았다 다시 엽니다. 이전 AI 요청을 자동 재전송하지 않습니다.
- 답변 중단: 창 닫기 대신 **답변 중단**을 누릅니다. 이미 외부로 보낸 내용은 회수할 수 없습니다.
- 오래된 근거: 새 동기화/검색 묶음을 준비합니다. 사람 편집 문서를 자동으로 덮어쓰지 않습니다.
