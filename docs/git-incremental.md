# GitHub 재수집 — 바뀐 내용만 가져오기

Mac 0.1.5부터 Git 실행 도구를 앱에 포함합니다. [내장 Git 설계와 검증](bundled-git.md). 아래 증분 수집 규칙과 DB 모델은 그대로입니다.

Source 수동/자동 동기화에 같은 최적화를 적용합니다. **원격 저장소나 Workflow를 수정하지 않습니다.**

1. GitHub REST API로 기본 브랜치의 commit SHA를 읽습니다.
2. 마지막 반영 SHA와 같으면 저장된 원본을 재사용합니다. Git clone을 하지 않습니다.
3. SHA가 다르면 임시 partial clone으로 commit/tree를 읽습니다. 작업 파일 checkout은 하지 않습니다.
4. tree의 blob SHA와 저장된 원본 바이트로 계산한 Git blob SHA를 비교합니다.
5. 같은 내용은 재사용하고 새 blob만 Git으로 읽습니다. 경로 변경은 새 tree에 맞춰 반영합니다.
6. 크기·blob 해시를 확인한 전체 결과를 기존 증분 분석/원자적 반영 경로로 전달합니다.

이름만 바뀐 파일도 내용이 같으면 재사용합니다. 삭제된 경로는 결과에서 빠집니다.
조상 commit 연결을 가정하지 않으므로 force-push도 새 tree 기준으로 처리합니다.
실패하면 이전 반영본과 사람이 수정한 지식은 그대로 남습니다.

## 모델·코드 대조

- 새 DB 테이블은 없습니다. v19 `source.revision`과 `entry`의 원본 바이트를 사용합니다.
- `SourceService`는 동기화 선점 뒤 `SourceStore.snapshot`을 읽어 Collector에 넘깁니다.
- `GithubCollector`는 같은 revision 생략과 blob 재사용을 담당합니다.
- `git ls-tree -rz`로 경로/mode/blob ID를 읽습니다. 모든 blob 크기를 요구하는 `-l`은 사용하지 않습니다.
- 새 blob만 `cat-file -s`/`cat-file blob`으로 가져오고 크기와 Git 해시를 재검사합니다.
- 파일 ID/분석 갱신과 과거 근거 무효화 원리는 [기존 증분 스냅샷](incremental-snapshots.md)과 같습니다.

## 한계

변경 시 임시 clone의 commit/tree 메타데이터는 다시 받습니다. 영속 bare 저장소의 `git fetch`/`git diff` 캐시는 아닙니다.
서버가 partial clone 필터를 지원하지 않으면 Git이 더 많은 객체를 받을 수 있습니다.
Git 임시 저장소 100 MiB/명령 60초 제한, 파일 10 MiB/총 50 MiB/500개 제한은 유지합니다.
공개 GitHub 기본 브랜치만 지원하며 인증·submodule·symlink는 지원하지 않습니다.
API 호출은 남으므로 GitHub rate limit에 걸리면 오류/백오프를 적용합니다. 일반 URL의 조건부 요청은 [별도 구현](url-conditional.md)이며 GitHub commit API는 매번 조회합니다.
Git blob SHA-1은 객체 재사용 식별자입니다. 앱의 원본/근거 SHA-256 검사를 대체하지 않습니다.

## 검증

2026-10-22: 대상 Collector/Source 통합 테스트 28개 통과, 전체 백엔드 329개 통과/실제 AI opt-in 1개 skip, 실행 JAR 빌드 성공. UI는 변경하지 않았으며 직전 188개 통과 기준입니다.

```sh
bash scripts/maven.sh test -Dtest=CollectorTest,SourceIntegrationTest
```

가상 Git 저장소로 변경/삭제/이름 변경, 같은 commit clone 생략, 변경 blob만 읽기,
blob 해시 불일치·링크 차단, DB에서 이전 원본 전달과 ID 보존을 검사합니다.
별도 로컬 partial clone fixture는 재사용 blob이 clone에 여전히 missing임을 검사합니다.
fixture만 로컬 file transport를 허용하며 제품의 GitRunner 제한은 바꾸지 않습니다.
실제 회사 저장소나 GitHub 원격 저장소로 성능 측정한 결과가 아닙니다.

참고: [Git ls-tree 형식](https://git-scm.com/docs/git-ls-tree).
