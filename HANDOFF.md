# 작업 인계

## 현재 작업: 터미널 모니터링 + 운영조회 확인 요청

스킬0.8.14 / 봇0.1.11. progress.log에 문의ID·실제관측단계·경과/무신호시간·도구응답수·최종상태 기록. 원문·질문·토큰·추론 미출력. 1MiB×3 순환, thread lock, 모니터 오류가 응답을 막지 않음. 기존30초Slack갱신 유지, 터미널은 단계변화(최대초당1행)/10초유휴갱신. code/live 분류 추가, 구타이밍프로필호환. status.monitor_command로 실제경로tail명령제공. 보기종료Ctrl+C는 봇중지아님. 회사연결·실제AI호출없이 회귀검사 진행. 시간 입력 필드 제거(제출시각만 자동기록, 실제조회시각 아님), 실제설정 기반BigQuery 도구탐색/스키마/조회절차 지시, SQL검증실패시조회문만제외·근거검사는유지, DM구분·입력대기말풍선·한글조사옆강조정리. 실제Codex1회/합성MCP2사례로조회순서·권한거부확인. 회사/nativeClaude미검증. 공개릴리스확인예정.

## 직전 수정 (2026-11-07 회사 기본 질문 실패)

일반 업무/코드 질문이 required_read_not_verified로 실패한다는 제보. 공통 결함: 모델에 tool_use_id를 명시 전달하지 않으면서 정확한 ID를 요구했고 기존 fake는 정답ID를 미리 넣었음. PostToolUse receipt_hook 추가, 독립 stream검증 유지, 실패진단 counts만 status 노출. 회사 실제 원인은 로그 없으므로 확정금지.

스킬0.8.13 / 봇0.1.10. 615자동테스트(신규18), 실제Codex2회 훅문맥으로ID연결통과. Claude가용성1회는 여전히인증만료. docs/dougle-bot/receipt-verification-fix.md 참조. cffac70 소스·CI42350152896 Python3.12/3.13 통과. v0.8.13-skills-preview.1 공개·익명다운로드 일치 확인. 업데이트는prepare→stop확인→start→running_version=0.1.10 필요.

## 직전 평가 (2026-11-07)

1,066개에 층별 결과를 연결했습니다. docs/scenarios/execution-20261107/README.md 참조. 전체 회사 종단간 PASS가 아닙니다. 봇597개 통과, 실제 AI181회(가용성2포함), Codex+fakeMCP440개, 병합72×3, scanner120+의미12(중첩), 정책180, 이미지2, 파일성능60. Claude CLI 인증 만료로 native 검증 불가. 회사 Slack/키체인/지식 미접근.

수정: 불투명ID/쓰기성공확인, bot와schedule 서명·승인규칙 분리, 필수스킬 실패차단, fetch접근안내, schedule재개·완료근거. 빠른 민감정보 검사 optional 표준Perl+공유AWK; 없으면legacy. UTF8유효범위 강화. 두경로84개씩, 차등200(199동일/1기존잘못된UTF8허용차단), 혼합1천중단복구,10만파일343.713초. 동일1천72.134→4.513초, 해시동일. 전체응답속도주장금지.

스킬0.8.12 / 봇0.1.9변경없음. 보고서는 PARTIAL 경계를 보존합니다. 원시모델event/내부추론은 .tools에만 두며 공개하지 않습니다. GitHub 소스0753680, CI42341406350 Python3.12/3.13 성공. v0.8.12-skills-preview.1 공개, 익명 ZIP·체크섬 바이트 일치 확인 완료. 사용자 상시배포승인 있음.

## 직전 구현

스킬0.8.11 / 봇0.1.9. 사용자가 승인 DM을 없애고 정상 문의는 즉답, 운영 조회·개발자 도움·오류만 내부 DM으로 받도록 요청했다. 구현 완료, 테스트508개 통과. 회사 서비스·키체인·지식 DB·AI CLI를 테스트에서 호출하지 않았다. 당시 실제 모델 평가 추가0회였습니다. 이후 추가 승인은 위 현재 작업을 따릅니다.

## 변경

- SlackConfig.require_approval=False 기본값. 기존 config에 없으면 새 문의부터 자동 답변; 명시적 true는 검토 모드 유지. status.reply_mode는 설정값이다. 실제 구프로세스가 도는지는 running_version/update_required도 확인한다.
- 정상 문의는 DM 없이 자동 게시. 기존 approver_user_id는 내부 확인 담당자. lookup 결과 입력·실패 처리 결과 입력은 문의/최신 draft 또는 job/카드/사용자/workspace에 바인딩한다. 재시도·재생성 메뉴 없음. 자유 DM·DM 이미지 입력 없음.
- missing code/live cannot_answer는 내부 needs_lookup으로 전환. 질문 명확화·자료 없음은 고객 답변 가능. 원본 확인을 하지 않은 내용을 확정 답변으로 게시하지 않는다.
- 개발자 보고는 요청된 code/live/knowledge 종류만 지원. environment repair는 evidence_kinds=[]이므로 실제 조회를 다시 해야 한다. 기존 보고는 live 전용 호환. 조사 의무를 감사 기록으로 보존해 실패 후 처리 결과 작업에서도 낮추지 않는다.
- 답변 저장/job done/auto_reply_ready를 같은 트랜잭션에 저장. 게시 시도 CAS와 auto_post actor=system 기록. 구 승인 대기 답변은 자동 게시하지 않는다. 전송 불명/부분 성공은 post_unknown, 확실한 실패는 담당자 DM. 재시작 시 남은 내부 확인 카드를 복구한다.
- inquiries(status,id), audit(inquiry_id,action,detail) 보조 인덱스. 의미 스키마 버전2 유지. migrate=False는 인덱스도 변경하지 않는다. 지식용 SQLite 도입 아님.

## 검증/배포

508개 fake 서비스/실제 SQLite·Bolt·소유 fake CLI 검사와 wheel 소스 일치 통과. 새 시나리오52개. Ruff·스킬 구조274·병합 판단64/로컬 전송6·문서 링크290·플러그인 검증 통과. 회사 동작·모델 의미 정확도는 미검증. 배포 전 CI3.12/3.13, 익명 ZIP 검증을 확인한다.

skills/ 정본과 plugins/dougle/skills/ 사본 동일. 로컬 설치본은 폴더 전체가 작업 전 Git 바이트와 같을 때만 백업 후 갱신한다. 사용자 설정·지식·인증은 수정하지 않으며 실제 봇도 재시작하지 않는다. 적용은 플러그인 업데이트/리로드→실행환경 준비→중지 확인→시작, running_version=0.1.9/update_required=false/reply_mode=automatic 확인.

사용자의 GitHub push/Preview 릴리스 상시 승인 있음. 개발 도구 .tools/bot-dev/bin, gh /usr/local/bin/gh. push는 HTTP/1.1+postBuffer524288000. 결과/현재 제한은 docs/dougle-bot/internal-handoff.md. 이전0.8.10 성능 결과는 historical 측정으로 그대로 유지한다.

0.8.11 Preview 공개 완료: 소스 b6e4d8be83d318b8bb8f3c75f8193314fd2933af, CI42329880716 Python3.12/3.13 모두 성공. 익명 ZIP/체크섬 다운로드 바이트 일치. ~/.agents/skills 및 ~/.claude/skills의 dougle-bot은 작업 전 Git 바이트와 폴더 전체 일치 확인 후 .tools/skill-backups/0.8.11에 백업하고 갱신했다. 실제 실행 중인 봇은 건드리지 않았다.

시나리오 목록 공개 완료: 소스 f95d7fe8ce3d43914642e1f3d91ce33559301351, CI42332824112 Python3.12/3.13 성공. 기존 v0.8.11-skills-preview.1 릴리스에 문서 전용 Dougle_Scenarios_0.8.11.zip(103,130바이트)을 추가했고 익명 다운로드·SHA256 일치를 확인했다. 설치 ZIP과 런타임은 그대로다. 증거: docs/scenarios/publication.json. 새 1,066개 시나리오 실행은 NOT_RUN이다.
