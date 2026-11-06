# 현재 저장소 구성

더글은 **AI 스킬 + 선택형 Slack 봇**으로 구성됩니다.

| 경로 | 용도 |
|---|---|
| `skills/` | 정본 스킬과 참조 규칙 |
| `plugins/dougle/` | Claude·Codex 플러그인 배포 사본 |
| `dougle-bot/` | 선택형 Slack 봇과 테스트 |
| `scripts/` | 현재 스킬 검사·가상 자료 검증 도구 |
| `docs/` | 현재 사용법·설계·검증 기록 |

0.8.6에서 사용하지 않는 `backend/`, `frontend/`, `desktop/`, 앱 전용 빌드·테스트·CI, 옛 앱 문서와 부속 자료를 제거했습니다. 현재 스킬·봇은 해당 코드에 의존하지 않습니다.

기존 개발 PC의 미추적 빌드 캐시는 `.tools/retired-builds-086/`로 분리했습니다. 사용자 지식·봇 설정·키체인은 변경하지 않았습니다.

[이전 코드와 기록](https://github.com/thisisnew/dougle/tree/f652fd8)은 Git 이력에 남습니다. 기존 공개 릴리스와 `updates/preview.json`은 과거 설치본의 조회 경로 보존을 위해 유지하며 새 스킬 릴리스와 분리합니다.
