# GitHub 자료를 별도 설치 없이 가져오기

0.1.5부터 Mac 앱 안에 Git이 들어 있습니다. 사용자는 Git이나 Xcode를 따로 설치하지 않고 **Source 등록 → GitHub 주소 → 수동 동기화**를 실행하면 됩니다.
공개 저장소의 기본 브랜치를 읽습니다. 비공개 저장소 인증은 아직 지원하지 않습니다.

## 작동 원리

```mermaid
flowchart LR
  A[Source 등록·동기화] --> B[GitHub API로 변경 확인]
  B --> C{새 버전인가?}
  C -- 아니요 --> D[기존 자료 재사용]
  C -- 예 --> E[앱에 포함된 Git 실행]
  E --> F[변경 파일 읽기·해시 확인]
  F --> G[로컬 저장·검색 갱신]
```

원격 저장소와 Workflow를 바꾸지 않습니다. 원본이 바뀌면 관련 지식에 오래됨을 표시하고, 사람이 편집한 문서는 보존합니다.

## 설계와 실제 코드

| 역할 | 구현 | 확인 방법 |
|---|---|---|
| CPU별 Git 준비 | `stage-git.sh → build-bundled-git.sh` | 고정한 공식 소스 SHA-256, Intel/ARM 빌드 |
| 외부 설치 의존 제거 | Git과 HTTPS 도구를 앱에 포함 | Mach-O CPU 검사, 링크가 macOS 시스템 라이브러리뿐인지 검사 |
| 앱 실행 시 연결 | Rust `supervisor::bundled_git → --forge.git-home` | 누락·앱 밖을 가리키는 경로 거부 |
| 지정한 Git 실행 | `GitRunner`의 절대 경로·`--exec-path` | 공백·한글 경로, 누락 시 시스템 Git으로 돌아가지 않음 |
| 사용자 Git 설정과 분리 | 상속한 `GIT_*` 제거, 통제된 환경만 설정 | 실행 도구·설정·템플릿 주입 차단 회귀 |
| 읽기 전용 수집 | 기존 `GithubCollector` | 공개 HTTPS, credential/helper·hook·file/ext 프로토콜 차단, 시간·용량 제한 유지 |

새 DB 테이블이나 API는 없습니다. SQLite v21과 Source revision, blob 재사용 모델을 유지합니다.
개발 서버는 기본적으로 개발 PC의 Git을 사용합니다. 설치 앱은 항상 내장 Git을 지정합니다.

## 소스와 라이선스

Git 2.56.0의 [공식 소스](https://www.kernel.org/pub/software/scm/git/git-2.56.0.tar.xz)를 수정 없이 빌드합니다.
Git의 GPLv2 전문, 전체 해당 소스 압축, 실제 빌드 스크립트를 앱의 `Contents/Resources/resources/git/`에 함께 제공합니다.
빌드 절차는 [동봉 설명서](bundled-git-source.txt)에 있습니다. 사용자가 이 소스를 빌드할 필요는 없습니다.

버전을 올릴 때 소스 해시·양쪽 CPU·라이선스/소스 포함·네트워크 수집을 다시 확인합니다.
처음 빌드는 Git의 기본 Rust 선택 때문에 실패했습니다. 공식 `NO_RUST` 옵션으로 C 구현을 선택해, Apple 개발 도구만으로 재현하도록 수정했습니다.

## 검증

```bash
bash scripts/maven.sh test -Dtest=GitRunnerTest,CollectorTest
bash scripts/macos-architecture-test.sh
FORGE_VERIFY_PUBLIC_GITHUB=true bash scripts/verify-macos-package.sh release/Knowledge.Forge_0.1.5_x86_64.dmg
TAURI_CONFIG='{"bundle":{"resources":[]}}' bash scripts/desktop-cargo.sh build --locked --example git_smoke
env PATH=/nonexistent GIT_EXEC_PATH=/nonexistent GIT_CONFIG_PARAMETERS=invalid \
  desktop/src-tauri/target/debug/examples/git_smoke \
  "$PWD/desktop/src-tauri/target/release/bundle/macos/Dougle.app/Contents/Resources"
```

마지막 검사는 실제 네이티브 실행 관리 코드와 동봉 Java/JAR/Git으로 공개 예제 저장소 `octocat/Hello-World`를 수집·검색·재동기화합니다. 임시 데이터만 만들며 AI를 호출하지 않습니다.
최종 실행 결과와 설치 파일은 [0.1.5 검증 기록](release-0.1.5-report.md)을 따릅니다. Intel의 실행 결과가 Apple Silicon 실기기 실행을 대신하지 않습니다.
