# 0.1.5 — GitHub 자료를 별도 설치 없이

Mac 앱에 Git을 포함했습니다. 사용자는 Git/Xcode를 추가로 설치하지 않고 공개 GitHub 저장소를 등록·동기화할 수 있습니다.
Java·Codex·Git은 앱에 포함되며 Claude는 기존 호환 도구를 연결합니다.

## 변경과 모델 대조

- 공식 Git 2.56.0 소스의 SHA-256을 고정하고 CPU별 Git·HTTPS 도구를 빌드합니다. macOS 시스템 라이브러리만 사용합니다.
- `supervisor → forge.git-home → GitRunner → GithubCollector`가 앱 내부 실행 파일을 선택합니다. 누락·외부 경로는 거부하며 시스템 Git으로 대체하지 않습니다.
- 상속한 Git 환경 설정을 제거하고 HTTPS 인증서 검사·hook/credential 차단·파일/외부 프로토콜 제한을 유지합니다.
- Git GPL·전체 해당 소스·실제 빌드 스크립트를 앱에 동봉합니다. [설계·코드·라이선스 대조](bundled-git.md).
- DB v21과 API, 화면은 그대로입니다. 기존 스크린샷도 현재 화면과 일치합니다.

## 완료한 검사

- Maven clean verify: **370건 중 369 통과, 실제 외부 AI 1 제외**.
- UI **204 통과**, TypeScript/Vite 빌드 성공.
- Rust **17 통과**, rustfmt 및 all-targets clippy 통과.
- 새 Java 회귀 4건: 공백·한글 절대 경로 실행, 누락·외부 경로 거부, 상속 설정 차단, 읽기 전용 설정 유지.
- CPU 혼합·Git helper 혼합·외부 라이브러리 의존·Git 라이선스/소스 누락 방어 검사 통과.
- `PATH=/nonexistent`에서 실제 Git으로 공개 `octocat/Hello-World`를 partial clone하고 README blob을 읽었습니다.
- 실제 네이티브 실행 관리 코드·동봉 Java/JAR/Git으로 공개 저장소 수집·검색·같은 revision 재동기화를 통과했습니다. `GIT_EXEC_PATH`와 잘못된 `GIT_CONFIG_PARAMETERS`를 넣어도 통제된 실행 설정을 사용했습니다. 가상 자료와 공개 예제만 쓰며 AI를 호출하지 않았습니다.

처음 Git 소스 빌드는 기본 Rust 도구를 찾지 못해 실패했습니다. 공식 `NO_RUST` 옵션으로 C 구현을 선택해 수정했고 다시 빌드·실행했습니다.
- Intel DMG를 읽기 전용으로 열어 별도 위치에 복사한 앱의 서명·CPU를 확인했습니다. 실제 React/WebKit의 Workspace·한글·AI 연결·업로드/다운로드·검색·게시·폴링 OFF 검사를 통과했습니다.
- 같은 설치 복사본의 내장 Git으로 `PATH=/nonexistent`와 잘못된 Git 환경을 넣은 공개 GitHub 수집·검색·재동기화도 통과했습니다.
- Intel 업데이트 압축의 서명·버전·압축 해제한 앱의 CPU·ad-hoc 서명·현재 JAR 일치 검사를 통과했습니다.
- 전체 API 통합 검사에서 가상 AI 지식/답변·SSE·중단·문맥·재시작·100개 파일 검색을 다시 통과했습니다. [측정](performance-phase3-foundation.json).
- 빌드와 병행한 첫 통합 검사는 서버 시작 시간 제한에 걸렸습니다. 빌드를 마친 뒤 같은 명령을 다시 실행해 통과했습니다. 앱 코드나 제한 시간을 바꾸지 않았으며 첫 실패를 제외하지 않고 기록합니다.
- 실제 0.1.5 네이티브 실행 파일의 격리된 데이터 경로·세션 보호·중복 실행 거부·종료·같은 DB 재시작을 통과했습니다. 단일 시작 측정은 31,101ms입니다. 개발 Mac의 당시 부하가 반영된 값이며 일반 성능 보장이 아닙니다. [측정](native-host-report.json).
- 가상 앱의 업데이트 설치·변조/서명 버전 불일치/구버전/누락 CPU 차단·실패 복구·자료 보존을 다시 통과했습니다.
- ARM DMG를 읽기 전용으로 열어 설치 복사본·앱/Java/Codex/Git/HTTPS 도구의 CPU·서명을 정적으로 검사했습니다. 실제 ARM 기기에서 실행한 결과는 아닙니다.
- ARM 업데이트 압축도 서명·버전·압축 해제한 앱의 CPU·ad-hoc 서명·현재 JAR 일치 검사를 통과했습니다.
- 두 앱에 들어간 JAR·Git 원본 소스 해시·빌드 스크립트·소스 안내가 현재 빌드 입력과 일치합니다.
- 코드/설정 318개와 테스트/fixture 90개의 목록·해시를 재생성했습니다. 목록 작성은 모든 코드 경로의 실행 검증을 뜻하지 않습니다.

## 설치 파일

[0.1.5 설치 파일](https://github.com/thisisnew/knowledge-forge/releases/tag/v0.1.5-preview.1).

| 파일 | 크기(bytes) | SHA-256 |
|---|---:|---|
| `Knowledge.Forge_0.1.5_x86_64.dmg` | 303229788 | `a9991658015cf327a0d831cf6f632feca4840e5672d51aeb9bfb025567e9d87f` |
| `Knowledge.Forge_0.1.5_arm64.dmg` | 292238441 | `470a70a3a1bb55afc80985d5c9a15baec159b74c7e3d749623c20fc781d3fc72` |
| `Knowledge.Forge_0.1.5_x86_64.app.tar.gz` | 284886951 | `fc8d2c937d5f5c8a39616d82edc04836fe0681c0c79b27b37b0fff7cc911f308` |
| `Knowledge.Forge_0.1.5_arm64.app.tar.gz` | 275528093 | `75f26f0cabaa5341a20794ce903cc73ab2920df415dabcfcda28ac85bb5d4815` |

두 CPU의 공통 JAR SHA-256: `5fbaadb8cdd7f4fd7bc35815cbcd4f78c72624a560c22e21137d7533146897c3`.
초안 릴리스의 설치 파일·서명·체크섬·버전 정보 10개가 모두 로컬 크기/SHA-256과 일치했습니다. 공개 배포와 자동 업데이트 피드 승격 결과는 아래에 추가합니다.

## 남은 확인

Apple Silicon 실물 실행, 실제 AI 계정 생성/스트림, Claude 동봉, 사용자의 설치 위치에서 업데이트 체험은 별도입니다.
Apple 공증은 요청에 따라 제외합니다. Git 의존 제거는 깨끗한 Mac의 Finder/Gatekeeper 전체 설치 검증을 대신하지 않습니다.
