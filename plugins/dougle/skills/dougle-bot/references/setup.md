# 최초 설정과 채널 추가

첫 실행에서만 아래 순서로 안내합니다. 완료한 항목은 반복하지 않습니다. 일반 Dougle 지식 스킬 사용에는 이 런타임이 필요하지 않습니다.

1. **실행 환경:** M Mac에서 기존 Claude 실행 명령을 확인합니다. 사용자가 평소 `claude-work` 같은 별칭을 사용하면 그 이름을 받습니다. 임의로 실행 경로를 추측하거나 로그인 정보를 바꾸지 않습니다. `uv`가 있으면 번들 `scripts/bot.sh prepare`로 격리된 봇 런타임을 설치합니다. 없으면 회사에서 허용하는 설치 방법을 안내합니다. Homebrew를 쓰는 환경에서는 `brew install uv`를 사용할 수 있습니다. Python은 uv가 별도 관리하며 기존 Python 환경을 바꾸지 않습니다. 설치 실패를 완료로 표시하지 않습니다.
2. **Slack 앱:** 사용자가 이미 앱을 만들고 채널에 초대했다면 다시 만들지 않습니다. 새 앱은 번들 [manifest](../assets/slack-manifest.yaml)를 Slack의 Create New App → From a manifest에 붙여 생성하도록 안내합니다. Socket Mode를 사용하며, App-Level Token에는 connections:write가 필요합니다. 설치된 bot token과 app token을 준비합니다. 회사의 앱 설치 승인이 필요한 경우 우회하지 않습니다.
3. **키체인:** 먼저 `scripts/bot.sh doctor`의 존재 여부만 확인합니다(설정 전에는 아래 명령 안내만 가능). 같은 앱의 토큰을 이미 저장했다면 그대로 사용합니다. 없을 때만 사용자가 **별도 Mac 터미널**에서 실행하게 합니다. 토큰을 대화창에 입력하게 하거나 에이전트가 입력/읽게 하지 않습니다.

```sh
security add-generic-password -U -a dougle-bot -s dougle-bot.slack-bot-token -w
security add-generic-password -U -a dougle-bot -s dougle-bot.slack-app-token -w
```

`-w` 뒤에 토큰 값을 적지 않습니다. macOS가 직접 묻는 비공개 입력에 각각 xoxb 봇 토큰과 xapp 앱 토큰을 입력합니다. 에이전트는 `security find-generic-password ... -w`를 실행하지 않습니다. 재설정이 필요하면 `scripts/bot.sh secrets set bot` 또는 `... set app`을 **사용자 터미널에서** 실행하면 종류 검증까지 수행합니다.

4. **사용 범위:** 승인자 Slack 사용자 ID(`U…`)와 받을 채널 URL/ID(`C…` 또는 `G…`), 평소 Claude 명령을 받습니다. 토큰과 달리 이 값들은 비밀 입력이 필요하지 않습니다. 채널 추가에는 새 토큰이 필요하지 않습니다. `/invite @dougle`은 해당 채널에서 실행하며, 비공개 채널도 봇을 초대해야 합니다.

설정 명령은 실제 입력을 안전하게 인자로 전달합니다. 셸 문자열에 사용자 입력을 그대로 이어 붙이거나 eval하지 않습니다.

```text
scripts/bot.sh setup --approver U승인자ID --channel 채널URL또는ID --claude claude
scripts/bot.sh doctor --online
```

doctor는 AI를 실행하거나 채널에 메시지를 쓰지 않습니다. Slack 조회 성공과 실제 Claude 답변 성공은 별개입니다. 설치된 개인 스킬 이름을 쓰는 경우 config.toml의 engine.ask_skill/style_skill을 호스트에 등록된 정확한 이름으로 설정합니다. 기본은 Claude 플러그인의 dougle:dougle-ask / dougle:i-have-adhd입니다.

모든 준비가 끝나면 **1. 시작 / 2. 상태 확인 / 3. 중지 / 4. 채널 추가**를 보여 줍니다. 최초 설정만으로 봇을 시작하지 않습니다.

## 연결 실패 안내

- `missing_scope`, `not_in_channel`, `channel_not_found`: Slack 앱 권한·설치와 채널 초대를 확인하도록 안내합니다.
- `wrong_token_type`, `keychain_access_failed`: 사용자 터미널의 Keychain 등록/권한 확인을 안내합니다. 값을 출력하지 않습니다.
- `required_skill_not_activated`: Dougle 플러그인을 업데이트·리로드하고 다음 질문에서 실제 스킬 실행을 확인합니다. 파일 존재만으로 해결됐다고 말하지 않습니다.
- `polite_language_required`: 초안 말투 검사를 통과하지 못했습니다. 승인 또는 자동 게시로 우회하지 않습니다.
- `post_unknown`: 전송됐을 가능성이 있어 재게시가 잠겨 있습니다. `scripts/bot.sh reconcile 문의번호`는 Slack을 읽어 명확한 게시 증거만 확인합니다. 조회에서 안 보인다는 이유로 다시 보내지 않습니다.
- `thread_history_limit`: 현재 설정 범위보다 긴 스레드입니다. 새 스레드에서 필요한 맥락으로 질문하거나 검토 후 thread_history_limit을 늘립니다. 잘린 이력을 전체라고 표시하지 않습니다.

회사 환경에서 처음 사용할 때는 개인 시험 채널에서 멘션 → 승인 DM → 게시 → 같은 스레드 후속 질문 → 중지 → 재시작 순으로 확인합니다. 시뮬레이션 검증을 실제 회사 연동 성공으로 표시하지 않습니다.

## 업데이트한 봇 적용하기

플러그인 업데이트·리로드는 스킬 파일만 갱신합니다. 이미 실행 중인 봇 프로그램은 재시작해야 합니다.

1. `scripts/bot.sh status`에서 새 실행 환경 준비가 필요하면 `scripts/bot.sh prepare`를 실행합니다. 기존 설정·키체인·지식은 재등록하지 않습니다.
2. 실행 중이면 업데이트 적용을 위해 중지하고 다시 시작할 것을 안내합니다. 사용자가 업데이트 적용을 요청했다면 `scripts/bot.sh stop` 후 실제 중지를 확인합니다.
3. `scripts/bot.sh start` 후 `status`에서 `healthy=true`, `update_required=false`, 실행 버전 `0.1.1`을 확인합니다. 멈추는 중이면 시작하지 않습니다.

`preview_updated_review_again`은 업데이트된 게시 미리보기를 다시 확인하라는 뜻입니다. 기존 카드를 누르자마자 예전 형식의 답변을 보내지 않습니다. `publication_changed_regenerating`이면 새 초안을 준비하므로 새 카드에서 다시 승인합니다.
