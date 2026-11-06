# 개발하기

**`skills/`가 정본이고 `plugins/dougle/skills/`는 동일한 배포 사본입니다.**

## 현재 구성

| 경로 | 역할 |
|---|---|
| `skills/dougle-connect`, `dougle-ask` | 자료 연결·질문 시 갱신·답변 |
| `skills/dougle-publish`, `dougle-fetch` | 지식 공유·병합 |
| `skills/dougle-schedule`, `dougle-bot` | Slack 문의 확인·봇 관리 |
| `skills/i-have-adhd`, `plain-language` | 내부 답변 정리 |
| `dougle-bot/` | 선택형 Python 봇 실행기·테스트 |

지식 스킬은 호스트 AI의 도구를 사용합니다. 봇을 선택한 사용자만 별도 실행기를 설치합니다. publish의 민감정보 검사는 번들 셸 스크립트를 실행합니다.

## 변경 후 검사

```sh
node scripts/check-skills.mjs
node scripts/check-sharing-merge.mjs
node scripts/check-current-tree.mjs
node scripts/test-publish-sensitive.mjs
claude plugin validate ./plugins/dougle
claude plugin validate .
```

봇 코드를 바꿨다면 [봇 개발 검증](../../dougle-bot/README.md#개발-검증)도 실행하고, 테스트한 소스와 배포 wheel을 맞추세요. CI는 현재 스킬·봇 검사만 실행합니다.

공통 참조 문서를 수정하면 사용하는 모든 스킬과 배포 사본을 함께 갱신합니다. 배포 ZIP은 전체 스킬·참조·번들 자원·플러그인 매니페스트를 포함합니다. Node와 검사 도구는 개발용이며 지식 스킬 사용자의 설치 조건이 아닙니다.

## 외부 스킬과 검증 범위

`i-have-adhd`와 `plain-language`의 원본 출처·라이선스는 각 스킬에 포함합니다. 적용 범위와 호출 메타데이터를 수정한 배포본이므로 수정 없는 원본이라고 표시하지 않습니다. 원본 스냅샷을 바꾸면 출처·라이선스·해시를 함께 검토하세요.

구조 검사와 가짜 외부 서비스 테스트는 실제 AI의 의미 판단·회사 연결 검증과 다릅니다. 실제 모델 평가는 별도 호출 예산을 따릅니다. [검증 범위](verification.md) · [저장소 구성](../repository.md)
