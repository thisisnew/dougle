# Dougle Slack 봇

**Slack에서 `@dougle 질문` → 승인자 DM의 초안 확인 → [게시]를 누르면 원래 스레드에 답합니다.**

같은 스레드의 후속 질문은 같은 Claude 세션을 이어서 사용합니다. 업무 지식은 기존 Dougle 스킬이 읽습니다. 봇은 Slack 접수·승인·전송·복구만 담당합니다.

## 평소처럼 스킬로 시작하기

Dougle 플러그인을 업데이트한 뒤 Claude Code에서 실행하세요.

```text
/dougle:dougle-bot
```

처음에는 설치와 설정을 안내합니다. 설정이 있으면 메뉴가 나옵니다.

```text
✦ dougle bot
1. 시작
2. 상태 확인
3. 중지
4. 채널 추가
```

- **시작:** 이후 새 멘션을 받습니다. 승인 전에는 원래 스레드에 답하지 않습니다.
- **상태 확인:** 연결 상태·등록 채널·대기 건수를 보여 줍니다.
- **중지:** 멘션 수신과 새 작업을 멈춥니다. 설정·대기 초안은 남습니다. 중지 중 올라온 질문은 재시작해도 가져오지 않습니다.
- **채널 추가:** URL이나 채널 ID를 받습니다. 같은 앱이면 키체인 토큰을 재사용하고, 해당 채널에 `/invite @dougle`로 초대합니다. 실행 중이면 잠시 중지한 뒤 설정합니다.

이미 접수한 작업은 재시작 시 복구할 수 있습니다. Slack에 보낸 요청의 결과가 불명확하면 다시 보내지 않고 `post_unknown`으로 보류합니다.

## 처음 한 번 준비할 것

M Mac, 기존에 로그인한 Claude CLI, 설치된 Dougle 플러그인, `uv`, 회사에서 승인한 Slack 앱이 필요합니다. 일반 Dougle 스킬 사용자는 봇 런타임을 설치할 필요가 없습니다. 봇만 Python 3.12+와 Slack SDK를 별도로 사용합니다.

1. Slack의 **Create New App → From a manifest**에 [slack-manifest.yaml](slack-manifest.yaml)을 붙여 넣습니다. 이미 만든 앱이 있으면 재사용합니다.
2. 워크스페이스에 앱을 설치하고 받을 채널에서 `/invite @dougle`을 실행합니다. 비공개 채널도 초대해야 합니다.
3. **OAuth & Permissions**의 봇 토큰과 **App-Level Tokens**의 앱 토큰을 준비합니다. 앱 토큰에는 `connections:write` 권한이 필요합니다.
4. **Mac 터미널**에서 아래 명령을 실행하고 macOS가 묻는 입력에 토큰을 넣습니다. 토큰을 Claude 대화창이나 명령 뒤에 붙이지 마세요.

```sh
security add-generic-password -U -a dougle-bot -s dougle-bot.slack-bot-token -w
security add-generic-password -U -a dougle-bot -s dougle-bot.slack-app-token -w
```

같은 service/account로 이미 저장했다면 다시 입력하지 않아도 됩니다. 스킬에서 승인자 사용자 ID, 받을 채널, 평소 Claude 실행 명령을 알려주면 나머지 설정을 진행합니다. 키체인 비밀값은 런타임만 읽고 AI에 전달하지 않습니다.

공개 서버나 URL은 필요 없습니다. **봇이 실행 중인 Mac이 켜져 있고 인터넷에 연결되어 있어야 합니다.**

## 직접 실행하는 경우

저장소에서 실행하려는 개발자용 명령입니다. 일반 사용자는 위 스킬을 사용하세요.

```sh
uv sync --project dougle-bot
uv run --project dougle-bot dougle-bot setup --approver UYOURID --channel CYOURCHANNEL
uv run --project dougle-bot dougle-bot doctor --online
uv run --project dougle-bot dougle-bot start
uv run --project dougle-bot dougle-bot status
uv run --project dougle-bot dougle-bot stop
```

`~/.dougle-bot/config.toml`에는 비밀이 아닌 설정만 저장합니다. [예시](config.example.toml)에서 Claude 별칭·모델·읽기 도구 이름·예산을 조정할 수 있습니다. CLI의 `--home`과 `--config`는 하위 명령 앞에 넣습니다. 기본 실행은 전체 직렬 처리입니다.

`doctor`는 로컬 준비 상태를 확인합니다. `doctor --online`만 Slack 읽기 접근을 확인합니다. 둘 다 Claude 모델을 실행하거나 스레드에 답변을 쓰지 않습니다. 실제 Skill 호출 성공은 첫 문의 처리에서 확인합니다.

## 승인 DM에서 할 수 있는 일

| 버튼 | 동작 |
|---|---|
| 게시 | 현재 초안·출처·서명을 원래 스레드에 게시합니다. |
| 직접 수정 | 새 초안으로 저장합니다. 게시하려면 다시 [게시]를 눌러야 합니다. 3,000자를 넘는 초안에서는 숨깁니다. |
| 다시 생성 | 수정 지시를 받아 새 초안을 만듭니다. |
| 조회 결과 입력 | DB를 직접 볼 수 없을 때 비민감 결과 요약과 조회 시각을 받습니다. SQL은 실행하거나 스레드에 게시하지 않습니다. |
| 거절 | 스레드에 답하지 않고 문의를 종료합니다. |

지정 승인자 한 명만 처리할 수 있습니다. 옛 카드, 다른 사람, 다른 문의의 초안으로는 게시할 수 없습니다. 스레드가 바뀌면 재작성·재승인이 필요합니다. 모든 게시 답변 끝에는 `✦ dougle`을 붙입니다.

승인자는 **답변의 정확성과 출처 내용을 해당 채널에 공유해도 되는지** 함께 확인해야 합니다. 개인 계정으로 읽을 수 있다는 사실만으로 팀 공유가 허용되지는 않습니다.

## 답변 스타일

매 엔진 호출에서 `dougle-ask`와 `i-have-adhd`의 성공한 Skill 도구 기록을 확인합니다. 호출 누락·실패 또는 알려진 반말 표현이 감지되면 한 번 재작성하고, 재실패하면 승인용 초안을 만들지 않습니다. 존댓말은 짧은 안내·후속 질문·실패 응답에도 적용합니다. 인용문과 코드 원문은 유지합니다.

일반 `ask`는 호스트의 스킬 실행 절차를 따릅니다. 봇의 실행 기록 검사와 일반 스킬 지침은 구분합니다. 말투 검사가 한국어의 모든 표현이나 사실 정확성을 보장하지는 않습니다.

## 오류와 복구

- **`required_skill_not_activated`:** 플러그인 업데이트·리로드와 등록된 스킬 이름을 확인하세요. 파일 존재만으로 실제 호출 성공을 확인할 수는 없습니다.
- **`thread_history_limit`:** 새 스레드에서 필요한 맥락으로 질문하거나 이력 한도를 검토하세요. 잘린 스레드를 전체라고 처리하지 않습니다.
- **`daily_budget_exceeded`:** 일일 예산을 확인하세요. 실패·재시도도 예산에 포함하며 비용을 알 수 없는 실행은 예약액을 유지합니다. CLI의 호출 예산은 실제 과금을 정확히 제한하는 결제 차단기가 아닙니다.
- **`post_unknown`:** 자동 재전송하지 않습니다. `dougle-bot reconcile 문의번호`는 전송 ID·본문이 일치하는 Slack 게시 증거를 읽어 확인합니다. 안 보인다는 이유로 미게시라고 판단하지 않습니다.
- **`slack_disconnected`:** 프로세스가 있어도 연결 정상으로 표시하지 않습니다. 네트워크·앱 권한을 확인하세요.

원시 로그에 인증정보를 출력하지 않습니다. 운영 DB·질문·초안은 `~/.dougle-bot/`에 로컬 보관하며 제품 Git이나 지식 publish 대상으로 보내지 않습니다. 수동으로 오래된 DB 백업을 덮어쓴 뒤 재게시하는 복원 절차는 지원하지 않습니다.

## 개발 검증

```sh
uv run --project dougle-bot pytest dougle-bot/tests
uv run --project dougle-bot ruff check dougle-bot
```

외부 통신을 차단한 테스트에서 Fake Slack·Fake Engine, 실제 임시 SQLite, 실제 가짜 CLI 프로세스, Bolt 이벤트 처리기를 사용합니다. 실제 키체인·회사 자료·Claude 모델은 호출하지 않습니다.

[실행 결과](../docs/dougle-bot/implementation-verification.json)와 [회사 PC 확인 목록](../docs/dougle-bot/company-acceptance.md)을 구분합니다. 기존 231개 설계 시나리오 전체가 실행·통과한 것은 아닙니다. 회사 계정의 Claude/MCP/Slack 동작은 회사 PC에서 별도 확인해야 합니다. 봇 외부의 Claude 세션이 공통 기억을 동시에 수정하는 문제도 봇의 직렬 큐만으로 막지 못합니다.
