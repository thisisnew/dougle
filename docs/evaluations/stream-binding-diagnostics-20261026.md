# 응답 스트림 바인딩 진단 보강

미설치 소스 변경이다. 설치된 0.1.25 JAR와 현재 실제 AI 검증용 고정 아티팩트는 변경하지 않았다.

기존 `ignoredBindings` 합계만으로는 식별자가 없는 일반 알림과 다른 대화/턴의 답변 이벤트를 구분할 수 없었다. 고정된 숫자 카운터로 다음을 구분한다.

- thread 식별자 누락/상이, turn 식별자 누락/상이.
- 무시된 답변 생명주기 이벤트와 그 외 알림. 답변 생명주기는 agent message 시작/완료, 답변 delta, turn 완료만 포함한다.

원본 메서드명·식별자·답변·오류 본문은 보관하지 않는다. 카운터는 1,048,576에서 포화하고 snapshot은 불변이다. 기존 `AppStream` 생성자는 유지하며 구형 생성자는 상세 카운터가 null이다. 이벤트 바인딩 검사, 완료 조건, 120초 제한 및 자동 재전송 금지는 바꾸지 않았다. 이 변경은 과거 시간 초과의 원인이나 해결을 입증하지 않는다.

검증: `CodexTextStreamTest,ProcessDiagnosticsTest,CodexAppServerSessionTest` **30건 통과**. 로그 `/tmp/dougle-binding-diagnostics-fixed.log`. 첫 실행의 신규 테스트 1건은 문자열 치환이 `turn` 값뿐 아니라 JSON 키까지 바꾼 fixture 오류였다. 명시적 구조로 foreign-turn 이벤트를 생성하도록 고쳤다. 최초 로그 `/tmp/dougle-binding-diagnostics.log`를 보존했다.

프로토콜 참고: [공식 Codex App Server 문서](https://learn.chatgpt.com/docs/app-server). 실제 제품은 동봉된 CLI 버전에 대한 기존 계약을 유지하며 최신 문서만을 근거로 알 수 없는 이벤트를 수락하지 않는다.
