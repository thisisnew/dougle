# 어디까지 검증했나요?

**0.8.9는 많은 자료의 변경분 처리와 병목 측정에 초점을 맞췄습니다.** 지식은 Markdown을 유지합니다.

- 연결·질문 중 갱신·Fetch·Publish의 변경 비교, 묶음 처리, 중단·재개 절차를 보완했습니다. 호스트 AI의 실제 대용량 의미 병합을 실행 검증한 것은 아닙니다.
- 민감정보 검사62개 합성 사례를 실행합니다. 검사 전체 범위를 유지한 채 외부 명령 실행 횟수를 줄였습니다. [파일 수별 실제 측정](large-corpus-benchmark.json)
- 봇은 fake Slack/AI와 실제 SQLite·가짜 CLI로 검사합니다. 최신 개수와 항목은 [자동 테스트 기록](../dougle-bot/implementation-verification.json)에 있습니다. 배포 wheel과 테스트 소스의 바이트 일치도 확인합니다.
- 같은 문의의 표현 보완만 근거를 재사용합니다. 근거 변경·누락·세션 유실·다른 문의·운영 현재값·필수 스킬 미실행에는 해당 예외가 적용되지 않는지 검사합니다.
- [질문 시 갱신 시나리오](freshness-evaluation.md)는 계약 검토 항목이며 모델 실행 통과 건수가 아닙니다.

새 모델 호출0회, 누계200/200입니다. 회사 PC의 전체 답변 지연·실제 의미 품질은 이번 fake 검사로 검증했다고 표시하지 않습니다. 자동 Publish·지식용 SQLite·append-only 전환은 적용하지 않았습니다.

[처리 방식과 한계](large-corpus.md) · [패키지 검사](package-verification.json) · [공개 릴리스 검사](public-release.json) · [이전 기록](../history.md)
