# 개발하기

`skills/`가 정본이며 `plugins/dougle/skills/`는 내용이 같은 배포 사본입니다. 공통 참조를 고치면 사용하는 모든 스킬과 배포 사본을 함께 갱신합니다.

## 현재 구성

| 경로 | 역할 |
|---|---|
| `skills/dougle-connect`, `dougle-ask` | 자료 연결·질문 시 갱신·답변 |
| `skills/dougle-publish`, `dougle-fetch` | 지식 공유·병합 |
| `skills/dougle-schedule`, `dougle-bot` | Slack 문의 확인·봇 관리 |
| `skills/i-have-adhd`, `plain-language` | 내부 답변 정리 |
| `dougle-bot/` | 선택형 Python 봇 실행기·테스트 |

지식 스킬은 호스트 AI의 도구와 `~/.dougle/`의 Markdown을 사용합니다. 별도 실행기는 봇을 선택할 때 설치하며 publish 민감정보 검사는 번들 셸 스크립트로 실행합니다. Node와 검사 도구는 개발용입니다.

## 변경 후 검사

```sh
node scripts/check-skills.mjs
node scripts/check-sharing-merge.mjs
node scripts/check-current-tree.mjs
node scripts/test-publish-sensitive.mjs
claude plugin validate ./plugins/dougle
claude plugin validate .
```

봇 코드를 바꾸면 [봇 개발 검증](../../dougle-bot/README.md#개발-검증)의 pytest·Ruff와 배포 wheel의 소스 일치 검사도 실행하세요. CI는 현재 스킬·봇 검사를 실행합니다. 개발 테스트는 가짜 서비스와 임시 데이터를 사용하며 회사 Slack·키체인·사용자 지식이나 설정을 변경하지 않습니다.

배포 ZIP에는 전체 스킬·참조·번들 자원·플러그인 매니페스트를 포함합니다. 지식 스킬 사용자에게 개발용 실행 환경을 요구하지 않습니다.

## 외부 스킬과 검증 범위

`i-have-adhd`와 `plain-language`에는 원본 출처·라이선스를 포함합니다. 적용 범위와 호출 메타데이터를 조정한 배포본이므로 수정 없는 원본이라고 표시하지 않습니다. 원본 스냅샷을 바꾸면 출처·라이선스·해시도 검토합니다.

구조 검사·가짜 서비스 검사·실제 모델 평가·회사 수용 검증은 구분합니다. 실제 모델 평가는 승인된 범위와 호출 예산을 따르며 호출·실패·비용을 기록합니다. 정적 검사를 통과했다고 의미 판단이나 회사 연결까지 검증한 것은 아닙니다. [검증 범위](verification.md) · [저장소 구성](../repository.md)
