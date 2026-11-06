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
