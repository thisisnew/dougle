# dougle-bot 실행 계약

사용자가 제공한 §§0–7과 후속 합의로 구현한 첫 로컬 봇입니다. 사람용 안내는 [README](README.md), 기존 시험 설계는 [TEST_PLAN](../docs/dougle-bot/TEST_PLAN.md)에 있습니다. 기존 시험 설계의 모든 가정을 구현 완료로 간주하지 않습니다.

## 입력과 저장

- Slack Socket Mode `app_mention`만 수신합니다. 허용 채널·실제 설치 workspace·사람 메시지·정상 timestamp·본인 멘션을 확인합니다.
- `channel:ts`를 이벤트 키로 사용합니다. 이벤트·thread·inquiry·job 저장을 SQLite 한 트랜잭션으로 마친 후 ack합니다. Bolt `process_before_response=True`가 필요합니다.
- 실행 시작 시 접수 기준 시각을 기록합니다. 중지 상태와 시작 기준보다 과거 멘션은 접수하지 않습니다. 과거 이력을 문의 큐로 백필하지 않습니다.
- WAL/FK, user_version=1, 프로세스 flock, 전체 worker 한 개를 사용합니다. 코드 스키마는 store.py가 정본입니다. 기존 inquiry/draft/thread/engine_runs 외 control/jobs/deliveries/audit를 보관합니다.
- 새 시작은 미완료 drafting 작업을 재대기시키고 posting/불명확한 카드 전송을 미확인 상태로 보존합니다. 확정 전송의 단순 자동 재시도는 금지합니다.

## 엔진

- `claude -p`를 고정 cwd에서 실행하고 질문은 stdin으로 전달합니다. 회사 별칭은 zsh 로그인·대화형 초기화를 거친 고정 명령 이름으로 실행합니다. 질문을 셸 코드에 넣지 않습니다.
- stream-json 최종 result의 structured_output만 답변 후보로 취급합니다. assistant Skill tool_use와 대응하는 성공 user tool_result를 연결하여 **이번 호출의** ask/style 실행을 확인합니다.
- 결과 스키마는 prompts.py의 OUTPUT_SCHEMA입니다. answered/cannot_answer/needs_lookup, body, sources, lookup을 검사합니다. 답변 근거가 필요하고 조회 결과는 사용자 제공 근거와 시각을 표시해야 합니다.
- 세션 없음은 새 세션으로 한 번 재시도합니다. 스키마·말투·Skill 실패도 한 번만 재시도합니다. 각 실행은 별도 예산 예약을 합니다. 인증·네트워크 실패를 세션 없음으로 취급하지 않습니다.
- 성공한 초안에 대해서만 세션·last_seen을 전진시킵니다. 후속 호출은 해당 thread 세션과 이후 메시지를 사용합니다. 세션 복원 실패 시 읽은 전체 스레드로 재시작합니다. 이력 한도를 넘으면 명시적으로 중단하며 묵시적으로 잘라내지 않습니다.
- timeout/중지 시 소유한 프로세스 그룹을 종료하고 원시 stdout/stderr를 보관하지 않습니다. 출력 총량은 4MB로 제한합니다.

## 도구 권한

기본 명세의 광범위한 Bash(git/gh)와 Write(path) 문자열만으로는 읽기 전용을 보장하지 못하므로 제외했습니다. Bash/Agent/Task를 차단하고, PreToolUse hook으로 명시적 도구 목록과 실제 파일 쓰기 경로를 검사합니다. Write/Edit는 ~/.dougle 내부만 허용하며 symlink 탈출을 차단합니다. Skill은 ask/style만 허용합니다. Claude의 내부 StructuredOutput/ToolSearch는 결과 반환·도구 발견 용도로 허용하되 발견한 다른 도구 실행에도 동일 정책을 적용합니다.

이 정책은 OS 전체 샌드박스나 회사 MCP 서버 구현의 안전성 증명이 아닙니다. 실제 도구 이름·회사 관리 정책과 읽기 전용 계정이 필요합니다. 봇이 관리하지 않는 외부 Claude 작업과 메모리 쓰기까지 직렬화하지 않습니다.

## 승인·전송

승인자 ID + workspace + inquiry + 최신 draft + 실제 카드 channel/ts를 확인합니다. 수정은 새 revision, 재생성/조회 결과는 새 job입니다. 승인 없이 게시하는 경로는 없습니다. needs_lookup에서는 게시 버튼이 없으며 런타임은 SQL을 실행하지 않습니다.

게시 직전 초안의 스키마·민감정보·말투와 읽은 스레드의 변경 여부를 검사합니다. 원본 Confluence/DB 내용을 런타임이 직접 재조회하는 것은 아닙니다. 자료 최신성 판단은 dougle-ask, 공유 가능 범위 판단은 승인자가 맡습니다. Slack 조회와 게시 사이에 생기는 원격 변경까지 원자적으로 잠글 수는 없습니다.

awaiting_approval→posting 조건부 UPDATE로 선점한 뒤 조각별 전달 ID·시도·결과를 기록합니다. 명확한 첫 조각 미전송 오류만 재승인 가능 상태로 돌아갑니다. 응답 유실·부분 성공·종료는 post_unknown으로 남깁니다. reconcile은 본문·메타데이터 ID가 일치하는 단일 봇 메시지의 긍정적 증거로만 완료를 확인합니다. 기존 승인 카드 전달 자체가 불명확한 경우 자동 재발송하지 않습니다.

## 운영

메뉴는 1시작/2상태/3중지/4채널추가입니다. 설정이 없으면 설치·키체인·승인자/채널 안내가 먼저입니다. 같은 Slack 앱의 채널 추가는 기존 토큰을 재사용합니다. 설정 변경 전 실행을 중지하며 새 채널 등록만으로 자동 재시작하지 않습니다.

토큰은 macOS Keychain에서만 읽습니다. 저장은 security의 값 없는 마지막 -w와 사용자 터미널 입력을 사용합니다. 환경변수/설정/CLI 인자로 토큰을 받지 않습니다. 기본 실행은 M Mac, Python3.12+, uv, slack-bolt/표준 sqlite3입니다. 과거 앱 배포와 별도 선택형 런타임입니다.

## 확인한 외부 계약

- [Bolt App](https://docs.slack.dev/tools/bolt-python/reference/app/app.html): process_before_response/ack 동작을 설치 SDK로도 검사합니다.
- [Claude headless](https://code.claude.com/docs/en/headless): 비대화형 실행, JSON 출력.
- [Claude structured output](https://code.claude.com/docs/en/agent-sdk/structured-outputs): StructuredOutput 도구와 최종 structured_output의 구분.
- [Claude permissions](https://code.claude.com/docs/en/permissions): 도구 권한과 실제 hook 경계를 구분합니다.

실제 회사 연동·실제 모델의 의미/존댓말 준수·채널별 자료 공개 적합성은 가짜 외부 구현만으로 입증하지 않습니다.

## 0.8.1 게시 표시 계약

일반 ask는 출처 포함, 봇 게시 body는 비개발자용 업무 설명을 기본으로 한다. sources는 별도 승인 DM에만 표시한다. 게시 답변에는 서명이 없다. 구현 상세는 명시적 기술 질문에만 포함하며 조건·예외·최신성 불확실성은 유지한다. 렌더러는 사실을 요약하지 않고 제한된 서식/등록된 인용표시만 변환한다. 코드·인용은 보존한다.

성공한 카드 표시의 본문 해시와 revision을 기록해 게시 직전에 대조한다. 기존 카드/형식 변경 시 갱신된 미리보기를 다시 승인받는다. 이미 저장된 미전송 전달 조각의 본문이 다르면 새 revision을 생성하며 오래된 sources/서명 payload를 재사용하지 않는다.

내구성 있는 접수와 ack 후 진행 DM을 비동기로 보낸다. worker 시작 전 작성 중, 완료 후 승인 대기로 같은 카드를 갱신한다. 질문에 상태 리액션을 추가/교체하며 실패는 답변 처리를 막지 않는다. 시도한 리액션 이름을 audit에 먼저 기록하여 worker 재생성 후에도 자신의 이전 표시를 정리한다. 중복 추가/이미 없는 삭제는 멱등 성공이다. 이모지는 마지막 단계이며 heartbeat를 대신하지 않는다. 0.8.2부터 identity와 state의 두 리액션을 집합으로 기록한다. dougle을 유지하고 접수/작성/승인/조회/게시 중은 hourglass_flowing_sand, 게시 완료는 white_check_mark, 오류/전송 불명은 warning, 거절은 no_entry_sign이다. 과거 단일 이름 audit도 읽어 정리한다. 기존 eyes/hourglass_flowing_sand는 dougle로 해석하고, dougle 추가가 invalid_name이면 현재 상태 이모지만 표시한다. 권한/네트워크 실패에는 다른 이모지로 재시도하지 않고 명시적 빈 값은 유지한다.

실행 버전은 daemon 시작 audit로 확인한다. 스킬 버전/CLI 파일 버전을 실제 실행 프로세스 버전으로 간주하지 않는다. 플러그인 갱신만으로 실행 중인 프로세스는 바뀌지 않는다. 세션은 기존 thread_key 기준이며 채널 단위 자동 연결은 하지 않는다.

Slack 서식은 [공식 mrkdwn 안내](https://docs.slack.dev/messaging/formatting-message-text/)에 맞춰 굵게·글머리표·줄 간격을 사용한다.

## 0.8.2 승인 조작과 설명 범위

거절은 승인자·최신 draft·카드·워크스페이스 검증 후 즉시 조건부 상태 전이를 수행하며, durable 변경 후 ack하고 카드 갱신을 뒤로 넘긴다. 새 거절 사유 모달은 만들지 않는다. 이전 버전에서 이미 열린 거절 모달은 빈 사유로도 완료할 수 있다.

수동 다시 생성 버튼·모달은 제거한다. 이전 카드 버튼은 안내와 카드 갱신만 하고 AI 작업을 만들지 않는다. 이전 모달 제출도 차단한다. 이미 등록된 작업과 스레드 변경/스키마 실패 등 내부 재검증은 별개로 유지하며 결과는 다시 승인해야 한다.

불필요한 후속 질문 권유/자료 읽은 날짜/운영 미조회 문구는 관성적으로 붙이지 않는다. 답에 영향을 주는 오래된·충돌하는 근거나 필요한 실시간 조회 실패는 짧게 표시하거나 확인을 요청한다. i-have-adhd의 구조화 + 성인 비전문가용 설명을 결합하며 별도 ELI5 스킬 실행을 주장하지 않는다. 이는 출력 지침이고 새 모델 의미 품질을 실측한 결과는 아니다.

## 0.8.3 답변 범위와 이미지 입력

답변 선택 정책은 answer_policy.py에서 매 호출/resume/재검증/조회 결과 입력에 전달한다. 질문의 의미에 따라 범위를 정하고, 상세 요청도 요청 주제에 한정한다. 이는 프롬프트 계약이며 문자 수 절단이나 완전한 의미 검증기가 아니다.

Slack files 객체와 files.info로 권한 있는 직접 업로드 PNG/JPEG를 확인한다. files:read를 요구한다. 다운로드는 files.slack.com의 HTTPS /files-pri/만 허용하고 리다이렉트를 따르지 않는다. 응답을 제한된 크기/시간 안에서 읽고 MIME·기본 파일 프레이밍을 확인한다. 완전한 이미지 디코더는 아니며 CLI의 알려진 이미지 처리 오류도 실패로 처리한다. Slack 호스팅 방식이 바뀌거나 리다이렉트가 필요한 경우 명확한 다운로드 오류로 중단한다.

이미지는 메모리에서 base64 image content로 변환하여 Claude --input-format stream-json의 stdin으로 전달한다. 키체인 값·다운로드 URL·이미지 본문을 프롬프트 텍스트나 DB·audit에 저장하지 않는다. 이미지 입력 자체는 호스트 AI 제공자에게 전달되며 Claude 세션 보관 정책은 별개다. 원본은 읽기 전용이다. 같은 스레드의 이미지 최대4장/장당5MiB/합계12MiB를 매번 확인하며 일부만 조용히 생략하지 않는다. 초기 event에 첨부가 있었지만 history에서 보이지 않는 경우도 중단한다. 오류 시 모델을 호출하지 않고 승인자 카드에 안내한다. 첨부 변경/삭제 정보는 history_hash에 포함되어 재승인이 필요하다. 실제 이미지 픽셀 내용을 해석하는 정확성이나 이미지 내부 비밀정보의 완전 탐지는 별도 검증 대상이다.

프로토콜 근거: [Claude streaming image input](https://code.claude.com/docs/en/agent-sdk/streaming-vs-single-mode), [CLI input format](https://code.claude.com/docs/en/cli-reference), [Slack file downloads](https://docs.slack.dev/messaging/working-with-files/).

## 0.8.4 필수 근거 확인

응답 JSON에 verification(question_kind, required, checks, missing)을 추가한다. checks는 종류·이번 호출의 성공한 읽기 ID·sources 인덱스를 연결하며, 이미지/개발자 결과는 별도 영수증 식별자를 사용한다. answered는 모든 필수 종류를 검증해야 한다. 보완 시 필수 종류를 줄이지 않으며 조회 결과 반영 작업도 이전 요구를 보존한다. 기존 schema/style와 같은 1회 보완 예산을 공유한다. drafts.verification_json을 SQLite user_version2로 추가한다. 이전 미게시 초안은 자동 게시하지 않고 다시 조사·검토한다. 의미적 정확성을 도구 성공만으로 보장하지 않는다. 상세 계약은 [근거 확인](../docs/dougle-bot/research-verification.md)을 따른다.
