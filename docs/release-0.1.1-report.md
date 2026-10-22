# 0.1.1 검증 보고서

검증일: 2026-10-22. Intel Mac, Java 21, SQLite v21. 회사 자료는 사용하지 않았습니다.

| 검사 | 결과 |
|---|---|
| Maven verify | 350 통과 / 실제 AI opt-in 1 제외 |
| Vitest / 프런트 빌드 | 195 통과 / 빌드 성공 |
| Rust test / fmt / clippy all-targets | 12 통과 / 성공 / 경고 오류 없음 |
| 실제 JAR 및 동봉 Java 통합 smoke | 통과, 인증·SSE·취소·재시작 포함 |
| DMG 설치 복사본 네이티브 UI | 업로드·미리보기·다운로드·검색·지식 편집/게시·관련 자료·설정 통과 |
| 실제 60초 폴링 | OFF/ON·재시작 예약 보존 통과 |
| CLI 경로 재시작 | 변경 전 승인 차단, 저장만으로 실행하지 않음 통과 |
| 이전 배포 JAR DB 업그레이드 | v18 자료·지식·게시 보존 후 v21 통과 |
| 종료 상태 전체 백업 복원 | 별도 임시 폴더에서 복원 후 동일 자료 확인 |
| 네이티브 중복 실행·부모 종료·재시작 | 최종 재검증 통과, 기동 8.350초 |

공개 릴리스: [v0.1.1-preview.1](https://github.com/thisisnew/knowledge-forge/releases/tag/v0.1.1-preview.1), 빌드 코드 `d4aa1bced05b641490b429836b43ec803e0c3cc9`.
DMG 166,793,851바이트, 공개 자산 SHA-256 `2d8b07bcad252e061137c83ee01d1a9d9cf81235d334daae80a9c18b59d6fb19`가 로컬 파일과 일치합니다.

## 성능

[측정 원본](performance-desktop-runtime.json): 합성 파일 100개, 순차 검색 30회 p95 **13.71 ms**.
동봉 Java 기동 **12.022초**, 재시작 **6.684초**. 동시 빌드 부하를 완전히 격리한 벤치마크가 아닙니다.
실제 LLM 응답 속도나 대규모 저장소 성능으로 해석하지 마세요.

## 요구사항 대조

| 요구 | 현재 구현 |
|---|---|
| 1–3 Workspace·원본 탐색·등록 | 구현. 공개 HTTPS/GitHub, 로컬 폴더·파일 |
| 4–6 폴링·동기화·분석 | 구현. Source별 OFF 기본, 실패 시 이전 자료 유지 |
| 7–8 지식 생성·독립 관리 | 구현. AI 초안/사람 검토/명시 게시, 불변 버전 |
| 9–10 다중 도메인·검색·관계 | 구현. Lucene/Nori·Java 심볼·공통 출처; 의미 그래프 아님 |
| 11 채팅·SSE·중단 | 구현. 근거 먼저, 건별 승인, 선택 문맥 고정. 실제 제공자 스트림 재검증 별도 |
| 12–13 최신성·증분 갱신 | 구현. 로컬 수집본 대비 상태, 변경 파일 분석·Git blob 재사용 |
| 14 Mac 설치형 | Intel DMG 생성·설치 복사본 검증. 공증·ARM·Windows 후속 |

## 제한과 재현

- 사용자 Finder/Gatekeeper 허용 절차와 다른 실물 Mac은 자동 검사로 대체하지 않았습니다.
- 실제 AI 호출은 이번에 하지 않았습니다. 가상 CLI 결과를 실제 서비스 성공으로 보고하지 않습니다.
- 프런트 단일 청크 크기 경고는 남아 있습니다. 빌드 실패는 아닙니다.
- 네이티브 중복 실행 검사에서 종료된 zombie를 살아 있는 Java로 오인했습니다. `ps stat`으로 종료 상태를 구별하도록 검사기를 수정했습니다. 앱 동작을 우회하지 않습니다.
- 명령: `bash scripts/build.sh`, `node scripts/smoke.mjs`, `node scripts/cli-settings-smoke.mjs`, `node scripts/upgrade-smoke.mjs <이전-v18-JAR>`, `node scripts/native-host-smoke.mjs`.
- 네이티브 수명주기 최신 결과는 [기계 판독 보고서](native-host-report.json), 빌드 절차는 [Mac 빌드](macos-build.md)입니다.
