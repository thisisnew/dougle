# 작업 인계

## 현재 변경

스킬0.8.10 / 선택형 Slack 봇0.1.8. 사용자가 반복 실행 비용을 강조하며 강한 검증을 요청했다. 실제 회사 Slack·키체인·지식/DB·AI CLI에 접근하지 않고 합성 자료와 fake 서비스로 검증했다. 추가 실모델 호출0회, 누계200/200. 추가 허용 없이 모델 평가를 실행하지 않는다.

재현 후 수정:
1. required_skills 검사 실패가 먼저 발생하면 코드/live 조사 요구가 다음 시도에서 사라져 승인 가능해졌음. 요구 수집을 스킬 검사 앞으로 이동해 보존한다.
2. save_draft 뒤 finish_job 전 중단 시 재시작 후 엔진을 재호출했음. job_id를 받아 답변·작업 완료를 같은 트랜잭션에 저장한다. 실패는 둘 다 롤백. 저장 이전 중단까지 무재호출을 보장하지 않는다.
3. 합성 X-Amz-Signature URL이 scanner PASS였음. 서명/접근 토큰 쿼리 패턴을 추가하고 보고서는 값 없이 유지한다. 의미 검토와 전체 패키지 검사는 여전히 필수다.
4. sensitive.md가 같은 최종 stage도 step3 뒤 무조건 다시 scan하도록 요구했음. 최종 검토 뒤1회, 같은 작업/동일 bytes/동일 remote base의 PASS만 사용한다. 변경·다른 세션·실패/불완전 검사에는 재사용하지 않는다. 이 지침의 호스트 AI 실제 수행은 미검증이다.

## 검증

- 봇456개 자동 검사 통과: fake Slack/engine, 실제 SQLite·가짜 CLI·배포 wheel 바이트 일치. 동시 중복200이벤트는 테스트1개이며 엔진1회만 실행된다. 재시도/복원/예산/취소/저장 실패 경계를 포함한다.
- 민감정보77개 합성 사례 통과. signed URL, HTML escape, 파일명, CRLF, 마지막 줄, batch 중 바이너리 포함.
- 실제 Git1만 파일, 변경10개와 동일9,993개, 별도2,500개 일괄 변경, 동일목록3회, 관련 없는 커밋, rename, 변조/고정버전20개 확인.
- 혼합크기1천파일(4KiB/64KiB/1MiB) 세 번 전체 검사66.606/78.924/71.509초. 합성 암호1파일 추가는 전체 BLOCK, 복구 시 원래digest. 강제종료에는 PASS 없음, 재실행 통과. docs/skills/stress-verification.json에 결과를 저장했다.
- 단일 개발환경 측정이며 실제 M Mac·회사 Slack 응답속도·호스트 AI 의미 병합의 검증은 아니다. SQLite 지식 DB나 자동 Publish는 추가하지 않았다.

## 배포와 유지

skills/ 정본과 plugins/dougle/skills/ 사본을 동일하게 유지한다. 봇0.1.8은 실행환경 준비 후 중지 확인→시작이 필요하다. 개발 중 실제 봇을 재시작하지 않는다. 사용자 지식·설정·키체인은 보존한다.

GitHub push와 Preview 릴리스는 사용자 상시 승인 범위다. Python3.12/3.13 CI와 익명 다운로드 바이트 검증을 마치고 public-release.json에 기록한다. 로컬 설치본은 이전 Git 바이트와 전체가 일치할 때만 .tools/skill-backups/0.8.10에 백업 후 갱신하고 개인 수정본은 보존한다.

개발 도구 .tools/bot-dev/bin/, gh /usr/local/bin/gh. push는 HTTP/1.1 + postBuffer524288000. GUI 앱 금지. 제품 지침은 AGENTS.md. 이전0.8.9 측정은 large-corpus-benchmark.json에 당시 결과로 보존한다.
