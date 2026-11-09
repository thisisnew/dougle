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

4. **사용 범위:** 내부 확인 담당자 Slack 사용자 ID(`U…`)와 받을 채널 URL/ID(`C…` 또는 `G…`), 평소 Claude 명령을 받습니다. 토큰과 달리 이 값들은 비밀 입력이 필요하지 않습니다. 채널 추가에는 새 토큰이 필요하지 않습니다. `/invite @dougle`은 해당 채널에서 실행하며, 비공개 채널도 봇을 초대해야 합니다.

설정 명령은 실제 입력을 안전하게 인자로 전달합니다. 셸 문자열에 사용자 입력을 그대로 이어 붙이거나 eval하지 않습니다.

```text
scripts/bot.sh setup --approver U승인자ID --channel 채널URL또는ID --claude claude
scripts/bot.sh doctor --online
```

doctor는 AI를 실행하거나 채널에 메시지를 쓰지 않습니다. Slack 조회 성공과 실제 Claude 답변 성공은 별개입니다. 설치된 개인 스킬 이름을 쓰는 경우 config.toml의 engine.ask_skill/style_skill/plain_language_skill을 호스트에 등록된 정확한 이름으로 설정합니다. 기본은 Claude 플러그인의 dougle:dougle-ask / dougle:i-have-adhd / dougle:plain-language입니다. plain-language는 함께 설치되는 내부 스킬이며 사용자 명령 목록에는 표시하지 않습니다. 이전 설정에 새 항목이 없어도 기본값이 적용됩니다.

모든 준비가 끝나면 **1. 시작 / 2. 상태 확인 / 3. 중지 / 4. 채널 추가**를 보여 줍니다. 최초 설정만으로 봇을 시작하지 않습니다.

## 연결 실패 안내

- `missing_scope`, `not_in_channel`, `channel_not_found`: Slack 앱 권한·설치와 채널 초대를 확인하도록 안내합니다.
- `wrong_token_type`, `keychain_access_failed`: 사용자 터미널의 Keychain 등록/권한 확인을 안내합니다. 값을 출력하지 않습니다.
- `required_skill_not_activated`: Dougle 플러그인을 업데이트·리로드하고 다음 질문에서 실제 스킬 실행을 확인합니다. 파일 존재만으로 해결됐다고 말하지 않습니다.
- `polite_language_required`: 초안 말투 검사를 통과하지 못했습니다. 승인 또는 자동 게시로 우회하지 않습니다.
- `post_unknown`: 전송됐을 가능성이 있어 재게시가 잠겨 있습니다. `scripts/bot.sh reconcile 문의번호`는 Slack을 읽어 명확한 게시 증거만 확인합니다. 조회에서 안 보인다는 이유로 다시 보내지 않습니다.
- `thread_history_limit`: 현재 설정 범위보다 긴 스레드입니다. 새 스레드에서 필요한 맥락으로 질문하거나 검토 후 thread_history_limit을 늘립니다. 잘린 이력을 전체라고 표시하지 않습니다.

회사 환경에서 처음 사용할 때는 개인 시험 채널에서 멘션 → 자동 답변 → 내부 확인 필요 문의의 DM → 결과 입력 → 같은 스레드 답변 → 중지 → 재시작 순으로 확인합니다. 시뮬레이션 검증을 실제 회사 연동 성공으로 표시하지 않습니다.

## 업데이트한 봇 적용하기

플러그인 업데이트·리로드는 스킬 파일만 갱신합니다. 이미 실행 중인 봇 프로그램은 재시작해야 합니다.

1. `scripts/bot.sh status`에서 새 실행 환경 준비가 필요하면 `scripts/bot.sh prepare`를 실행합니다. 기존 설정·키체인·지식은 재등록하지 않습니다.
2. 실행 중이면 업데이트 적용을 위해 중지하고 다시 시작할 것을 안내합니다. 사용자가 업데이트 적용을 요청했다면 `scripts/bot.sh stop` 후 실제 중지를 확인합니다.
3. `scripts/bot.sh start` 후 `status`에서 `healthy=true`, `update_required=false`, 실행 버전 `0.1.27`를 확인합니다. 멈추는 중이면 시작하지 않습니다.

0.1.27의 기본 문의 동시 처리 수는 4입니다. `[engine]`에 `parallelism_version`이 없고 정수 `max_parallel = 1`인 이전 설정은 읽을 때 **메모리에서만 4로 적용**합니다. 이 읽기만으로 기존 config.toml을 다시 쓰지 않습니다. 한 문의씩 처리하려면 다음 두 값을 함께 명시합니다. 표식이 있는 새 설정의 1은 그대로 존중하며, 정상 설정 저장 시 표식도 저장합니다.

```toml
[engine]
max_parallel = 1
parallelism_version = 1
```

기존 `[engine]` 항목 안에서 두 값을 설정하며 같은 표를 중복 추가하지 않습니다. 문의별 지식 변경은 임시 영역을 거쳐 백그라운드에서 순차 반영하므로 답변 게시와 지식 저장 성공을 같은 상태로 안내하지 않습니다. 이번 버전의 최종 검증 결과는 main의 결과 기록 후 확정합니다.

명시적 검토 모드의 `preview_updated_review_again`은 업데이트된 게시 미리보기를 다시 확인하라는 뜻입니다. 기존 카드를 누르자마자 예전 형식의 답변을 보내지 않습니다. `publication_changed_regenerating`이면 새 초안을 준비하므로 새 카드에서 다시 승인합니다.

## 선택형 Graphify 코드 탐색

사용자가 코드 그래프 사용을 요청한 경우에만 봇 Mac에 `uv tool install graphifyy==0.9.104`를 설치합니다. `~/.dougle/`에 등록된 GitHub 저장소/ref만 `scripts/bot.sh graph-build OWNER/REPO REF --graphify "$(command -v graphify)"`로 색인할 수 있습니다. 완료 결과의 `commit`을 확인하고 봇을 재시작합니다. ref가 이동하면 다시 색인합니다. 그래프는 후보 경로를 찾는 용도이며 답변 근거는 해당 원격 커밋의 `read_file`로 확인해야 합니다. 그래프가 없어도 기존 원격 코드 탐색은 계속됩니다. 원본과 사용자 지식은 변경하지 않습니다. [상세 안내](https://github.com/thisisnew/dougle/blob/main/dougle-bot/README.md#선택형-코드-그래프-탐색)

## 기존 앱에서 이미지 질문 사용하기

Slack 앱 설정 → OAuth & Permissions → Bot Token Scopes에 `files:read`를 추가하고 Reinstall to Workspace를 진행합니다. 회사 승인 필요 시 우회하지 않습니다. 재설치 후 봇 토큰이 바뀐 경우만 사용자 터미널에서 키체인의 bot 값을 다시 등록합니다. 호스트가 토큰을 읽거나 대화로 받지 않습니다. 텍스트 질문은 파일 권한 없이도 기존대로 사용합니다.

지원은 Slack에 직접 올린 PNG/JPEG, 스레드 최대4장, 장당5MiB·합계12MiB입니다. 권한·형식·용량·다운로드 오류는 내부 확인 DM에서 안내하며 해당 이미지를 빠뜨린 채 답하지 않습니다. 이미지만 올리고 멘션할 수도 있지만 원하는 확인 내용을 함께 적는 편이 명확합니다. 같은 스레드의 후속 질문에서는 이전 이미지도 다시 읽습니다. 일반 DM 이미지 입력과 외부 미리보기는 지원하지 않습니다.

## 자동 답변 기본값

0.8.11부터 새 문의는 기본 자동 답변이며, 내부 확인이 필요할 때만 기존 approver_user_id로 DM을 보냅니다. 기존 config에 require_approval이 없으면 새 실행기에서 자동 모드가 됩니다. 설정을 명시적으로 true로 둔 검토 모드는 보존합니다. 이전 승인 대기 답변을 자동 게시하지 않습니다. 상태의 reply_mode를 확인해 안내하세요. 0.1.27의 압축 정책은 기존 설정을 자동 변경하지 않으며, 기존 `[engine]`에 `compact_policy = true`를 명시한 뒤 봇을 재시작하면 자동 답변 모드에서도 사용합니다. 이 설정을 켰으면 실제 계정의 MCP 읽기 접근을 개인 시험 채널에서 확인하고, 시작 점검 성공만으로 커넥터 접근까지 검증됐다고 안내하지 않습니다.


## 시작할 때 자동 점검

`start`와 직접 실행하는 `run` 모두 Slack 인증·채널 읽기, Claude 실행·필수 스킬을 확인한 뒤 문의를 받습니다. 앱 토큰은 실제 Socket Mode 연결로 확인합니다. `doctor --online`은 종전처럼 AI를 호출하지 않는 준비 점검입니다.

Claude 점검은 설정한 `claude_bin`과 `workdir`에서 새 세션으로 한 번 실행합니다. 기본 시간·턴·비용 상한은 없습니다. 사용자가 명시한 설정만 적용합니다. 설정한 호출 한도와 남은 일일 예산을 따릅니다. 추가 자동 재시도는 없습니다. 업무 조회·원본 변경·테스트 댓글은 하지 않습니다. 실제 조회 가능 여부는 질문마다 다시 검증합니다.

| 표시 | 안내할 조치 |
|---|---|
| Slack 확인 실패 | 기존 키체인 등록, 앱 토큰의 connections:write, 채널 초대·앱 권한을 확인합니다. 토큰을 대화로 받지 않습니다. |
| Claude 실행 점검 실패 | 설정한 Claude를 같은 workdir에서 열어 로그인·네트워크·사용 한도를 확인합니다. 실패만으로 인증 만료라고 단정하지 않습니다. |
| 필수 스킬 실행 실패 | Dougle 플러그인을 업데이트하고 ask/style/plain_language 스킬 이름을 확인합니다. |
| 자료 연결 확인 필요 | 실제 표시된 연결을 Claude의 `/mcp`나 기존 제공자 로그인 화면에서 복구합니다. pending은 연결 대기·미확인입니다. |
| 오늘 예산 소진 | 일일 예산 설정을 확인합니다. 시작 점검도 예산에 포함합니다. |

사용자가 미확인 자료 연결을 둔 채 시작하겠다고 선택한 경우에만 `start --allow-unverified-readers`를 실행합니다. 필수 항목은 우회할 수 없으며 이 선택은 다음 시작에 남지 않습니다. 연결 상태가 보고되지 않은 경우는 미확인으로 설명합니다. DB 조회 권한을 확인했다고 표현하지 않습니다.

진행 중에는 status의 `startup.message`를 현재 대화에 보여 줍니다. `healthy=true`가 되기 전에는 시작 완료라고 하지 않습니다. 복구 후 시작을 다시 선택하면 새로 점검하며 사용자 설정·키체인·지식은 보존합니다.

## 업무 용어로 건수 자동 조회

[업무 건수 조회 정의](query-plans.md)를 따릅니다. 회사 원본·스키마와 질문·테이블·필터·기간·채널의 대응을 검토하고 보호된 registries/query-plans.json을 준비한 뒤 `query-plans-check`로 검사합니다. 원래 두 기대 실패 테스트는 이 정의와 완료된 현재 조회를 사용하는 정상 통과 테스트로 바뀌었습니다. 정의가 없거나 다른 문맥의 질문을 추측해 자동 게시하지 않습니다.
