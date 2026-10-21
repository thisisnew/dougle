# Knowledge Forge 개발 지침

- 시작할 때 `HANDOFF.md`, `docs/architecture.md`, `docs/phase-plan.md`를 읽는다.
- 이어서 개발할 때 `docs/backlog.md`의 미완료 항목과 인수 조건을 확인하고 완료 증거와 함께 갱신한다.
- Phase 1·2, P3-1, P3-2 CLI AI 초안/전송 승인이 구현되어 있다. `docs/cli-ai.md`의 검증/남은 범위를 확인한다. 검증·게시는 아직 없다.
- 사용자 요청으로 Ollama 대신 로그인된 Codex/Claude Code CLI를 사용한다. 현재 코딩 대화가 아닌 새 요청이며 외부 전송은 건별 승인한다.
- 기능 하나마다 유스케이스·포트·어댑터와 실제 테스트를 완성한다.
- Java 21, Spring Boot 4.1.x, React/TypeScript/Vite, SQLite JDBC를 유지한다.
- 도메인 모델은 Spring, HTTP, JDBC를 참조하지 않는다.
- 후속 검색은 Lucene 10 + Nori 및 심볼/관계 인덱스를 사용한다. 외부 DB 서버는 추가하지 않는다.
- 특정 업무 도메인을 하드코딩하지 않는다. 테스트는 `fixtures`의 가상 자료만 사용한다.
- 원본과 지식 수명주기를 분리하고, 검증 후 게시와 수동 편집 보호 원칙을 지킨다.
- 수집은 읽기 전용, 폴링은 Source별 opt-in, 외부 LLM 전송은 명시적 승인으로 제한한다.
- GitHub Actions Workflow를 생성·수정하지 않는다.
- 자격 증명, 실제 회사 자료, 로컬 DB, 빌드 캐시를 커밋하지 않는다.
- 백엔드 검증: `bash scripts/maven.sh test`
- 프런트엔드 검증: `cd frontend && npm test && npm run build`
- 통합 빌드: `bash scripts/build.sh`
- 패키지 검증: `node scripts/smoke.mjs` (빌드된 JAR 필요, 임시 로컬 포트 사용)
- 기능 완료 후 `HANDOFF.md`, API/아키텍처 문서, 테스트 결과를 실제 상태로 갱신한다.
