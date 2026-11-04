# Dougle Skills 개발 지침

## 현재 방향 — 2026-11-04 사용자 결정

더글은 Claude Code·Codex 안에서 사용하는 도메인 지식 스킬 모음이다. 전용 앱/UI/터미널/AI 선택 메뉴를 만들지 않는다. `dougle-connect`는 자연어 자료 연결, `dougle-ask`는 근거 기반 업무 질문을 담당한다. 업무 지식은 내부적으로 축적하며 별도 열람 UI를 만들지 않는다. 연결 후 선택지는 필요한 경우 `1 계속 연결하기`, `0 이전으로`만 제공한다.

## 구현과 경계

- 현재 코드는 skills/, skill-runtime/, scripts/*-skills.py, tests/skills/다.
- 공통 런타임 수정 후 `python3 scripts/build-skills.py`로 두 자기완결 스킬 사본을 갱신한다.
- 원본 코드·문서·회사 DB는 읽기 전용. 더글 자체 상태만 수정한다.
- 호스트 도구를 이용하는 외부 연결은 실제 읽기 성공 전 pending이다. capture는 스냅샷이며 회사 SSO 성공이나 지속 동기화를 뜻하지 않는다.
- 호스트 AI의 모든 도구를 스킬이 전역 차단한다고 주장하지 않는다.
- 실제 회사 자료/인증정보/상태DB/캐시는 커밋하지 않는다. 가상 자료로 테스트한다.
- 제품 지원 대상은 M 시리즈 Mac. Intel 전용 분기/빌드/배포는 추가하지 않는다. 범용 테스트를 Intel 개발 호스트에서 실행하는 것은 제품 지원과 별개다.
- 과거 외부 AI181/200 원장은 유지한다. 이 작업은 별도 실모델 호출 없이 도구·설치·공유 검증을 수행한다.

## 검증

```sh
python3 scripts/build-skills.py --check
python3 -m unittest discover -s tests/skills -v
python3 scripts/package-skills.py
```

실제 Claude/Codex 모델 동작, 회사 SSO, 도구 없는 신규 회사 DB 연결을 합성 테스트 통과로 대신하지 않는다. docs/skills/verification.md에 검증 범위를 구분한다.

## 문서·배포

사용자는 구현 완료 후 GitHub 반영과 최신 릴리스를 승인했다. README·docs/skills/·HANDOFF를 실제 지원 범위로 갱신한다. 기존 0.1.x 앱 DB/릴리스/업데이트 피드는 보존한다. 앱 배포 작업을 새 스킬 배포에 섞지 않는다. 현재 스킬 버전은0.2.0 Preview이며 Python3.9+/FTS5 의존성을 숨기지 않는다.

이전 앱 지침은 docs/desktop-agent-history.md, 작업 기록은 docs/desktop-handoff-history.md에 보존했다. docs의 이전 문서 배너는 당시 기능/검증을 현재 지원으로 오해하지 않기 위한 것이다. 새 방향과 충돌하는 과거 앱 지침을 적용하지 않는다.
