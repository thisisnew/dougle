# Dougle Slack 봇

**`@dougle 질문` → 근거 확인 → 원래 스레드에 바로 답변합니다.**
운영 조회·개발자 도움·내부 오류가 있을 때만 담당자 DM으로 확인을 요청합니다.

## 시작하기

플러그인을 업데이트한 뒤 `/dougle:dougle-bot`을 실행하세요.

```text
✦ dougle bot
1. 시작
2. 상태 확인
3. 중지
4. 채널 추가
```

처음에는 설치·설정을 안내합니다. 시작하면 등록된 채널의 새 멘션에 답합니다. 중지 중 올라온 질문은 나중에 가져오지 않습니다. 채널 추가는 같은 앱의 토큰을 재사용하며 `/invite @dougle`이 필요합니다.

## 도움이 필요한 문의

| 상황 | 동작 |
|---|---|
| 답변 가능 | 스레드에 바로 게시합니다. 정상 문의의 DM은 만들지 않습니다. |
| 질문이 모호함 | 답에 필요한 정보 한 가지만 질문자에게 묻습니다. |
| 관련 자료를 못 찾음 | 확인한 범위에서 무엇을 찾지 못했는지 설명합니다. 기능이 없다고 단정하지 않습니다. |
| 운영 DB·코드·화면 확인 필요 | DM에 원래 질문, 확인 대상·항목, 필요한 경우 검증된 SELECT SQL을 보냅니다. |
| 내부 오류·시간 초과 | DM에 필요한 점검 사항과 **처리 결과 입력**을 표시합니다. 고객에게 디버깅이나 재시도를 요구하지 않습니다. |

담당자가 **확인 결과 입력** 또는 **처리 결과 입력**으로 비민감 요약만 제출하면, 같은 문의의 답변을 이어서 준비하고 스레드에 게시합니다. 결과가 부족하면 남은 확인만 요청합니다. 재시도·재생성 메뉴는 없습니다.

기존 `approver_user_id`가 내부 확인 담당자입니다. 새 토큰이나 Slack 권한은 필요하지 않습니다. 일반 DM 텍스트를 임의 문의에 연결하지 않으며 **해당 카드 버튼**으로 결과를 입력합니다. 다른 사람이 입력하거나 오래된 카드를 제출하면 반영하지 않습니다.

SQL은 자동 실행하거나 질문자에게 게시하지 않습니다. 담당자 보고와 직접 조회한 근거를 구분합니다. ‘연결을 고쳤다’는 보고는 코드·운영 상태를 확인했다는 근거로 사용하지 않습니다.

Slack 전송 성공 여부가 불분명하면 중복 답변을 막기 위해 자동으로 다시 보내지 않고 내부 DM에 알립니다. 모든 외부 서비스 오류가 자동 복구되는 것은 아닙니다. 원격 읽기 권한이 채널 공유 권한을 뜻하지 않으므로, 허용 채널과 읽기 자료는 공유 가능한 범위로 설정해 주세요.

## 진행 상태와 대화

- 질문에 `:dougle:` + ⏳가 붙고, 게시되면 ✅로 바뀝니다. 사용자 이모지가 없으면 상태 이모지만 표시합니다.
- **2. 상태 확인**에서 실행 상태·버전·문의별 단계·경과 시간과 `recent_timings`를 확인합니다. 단계 시간은 모델/도구의 정확한 CPU 시간이나 토큰 수가 아닙니다.
- 같은 스레드는 같은 세션을 이어갑니다. 별도의 채널 메시지는 새 대화입니다.
- 내부 확인 DM은 같은 카드가 갱신됩니다. 결과 처리 중에는 문의별 중단이 가능합니다. 일반 문의를 멈추려면 봇 중지를 사용합니다.
- 입력한 운영 결과는 해당 문의의 근거이며 다른 문의에 자동으로 재사용하지 않습니다.

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

같은 service/account로 이미 저장했다면 다시 입력하지 않아도 됩니다. 스킬에서 내부 확인 담당자 사용자 ID, 받을 채널, 평소 Claude 실행 명령을 알려주면 나머지 설정을 진행합니다. 키체인 비밀값은 런타임만 읽고 AI에 전달하지 않습니다.

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

## 업데이트 적용

플러그인 업데이트·리로드 → `/dougle:dougle-bot`의 새 실행 환경 준비 → **중지 확인 → 시작** 순서입니다. 상태에서 `running_version: 0.1.12`, `update_required: false`, `reply_mode: automatic`을 확인하세요.

0.8.11부터 기본값은 자동 답변입니다. 기존 설정에 `require_approval`이 없으면 새로 접수하는 문의부터 자동으로 답합니다. 예전 승인 대기 답변을 업데이트만으로 자동 게시하지 않습니다. 검토 방식을 유지하려면 `config.toml`의 `[slack]`에 `require_approval = true`를 지정하세요. 이때만 기존 게시·직접 수정·거절 절차를 사용합니다.

## 답변과 이미지

질문자가 궁금한 점부터 존댓말로 답하고, 필요한 조건·다음 행동·불확실성을 남깁니다. 매 AI 호출에서 `dougle-ask`, `i-have-adhd`, `plain-language`의 성공 기록을 검사합니다. 출처는 로컬 기록과 필요한 내부 DM에 보존하고, 게시 본문에는 출처 목록·인용 번호·서명을 붙이지 않습니다. 일반 `dougle-ask`는 출처를 표시합니다.

PNG/JPEG 첨부 질문은 Slack 앱의 `files:read`가 필요합니다. 스레드 최대4장, 장당5MiB·합계12MiB입니다. 권한·형식·다운로드 오류는 내부 DM으로 보냅니다. 이미지 본문은 봇 DB·로그에 저장하지 않지만 Claude 세션 보관은 별개입니다. DM 이미지 조회 결과 입력은 지원하지 않습니다.

[내부 확인·자동 답변 검증](../docs/dougle-bot/internal-handoff.md) · [근거 확인 방식](../docs/dougle-bot/research-verification.md) · [속도 측정 범위](../docs/skills/large-corpus.md)

## 개발 검증

```sh
uv run --project dougle-bot pytest dougle-bot/tests
uv run --project dougle-bot ruff check dougle-bot
```

테스트는 가짜 서비스와 임시 DB로 실행합니다. 실제 회사 Slack·키체인·AI CLI를 호출하지 않습니다. 배포 wheel의 소스와 테스트한 소스가 같은지도 검사합니다.

## 대화에서 진행 상황 보기

`/dougle:dougle-bot` → **2. 상태 확인**을 선택하세요. “더글 지금 뭐 하고 있나요?”라고 물어보셔도 됩니다. 별도 터미널이나 로그 명령어 없이 현재 대화에 실행 상태·진행 중인 문의·최근 처리 결과를 표시합니다.

봇0.1.12부터 마지막으로 관측한 작업 단계와 당시 처리 시간도 보여줍니다. 자동 갱신 화면은 아니며 다시 확인하면 새 상태를 가져옵니다. 오래된 기록을 현재 실행 상태로 표시하지 않습니다. 첫 문의 전에는 아직 진행 기록이 없을 수 있습니다.

## 터미널에서 진행 상황 보기

계속 흘러가는 로그가 필요한 경우에만 사용하는 선택 사항입니다. 봇0.1.11 이상에서 지원합니다.

```sh
tail -n 30 -F ~/.dougle-bot/progress.log
```

문의 번호별로 자료 검색·원본 코드 확인·운영 조회·답변 준비·게시 결과와 경과 시간을 표시합니다. 약10초마다 마지막 진행 신호를 갱신하며, 신호가 없다는 이유만으로 멈췄다고 단정하지 않습니다. AI의 생각이나 자료 원문을 출력하는 로그는 아닙니다.

첫 문의 전에는 파일이 없어 기다릴 수 있습니다. Ctrl+C는 로그 보기만 종료하며 봇은 계속 동작합니다. 저장 위치를 바꿨다면 상태 확인의 `monitor_command`를 사용하세요. 로그는 최대1MiB씩3개로 순환하며 질문·자료 원문·토큰은 남기지 않습니다.
