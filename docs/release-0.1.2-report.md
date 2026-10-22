# 0.1.2 Mac 미리보기 — 배포 기록

## 변경 내용

- 점무늬·두꺼운 테두리·큰 그림자·반복 문구를 덜고, 작은 픽셀 그림만 남긴 화면.
- 제공자 선택 기억, 인증 상태 확인, 명시적 브라우저 로그인과 중단.
- Intel/Apple Silicon별 Codex 0.163.0 실행 도구와 Java 21 동봉.
- 비개발자용 README와 실제 화면 스크린샷. 저장 형식은 SQLite v21 유지.

## 검증

- UI 전체 201개 통과, TypeScript/Vite 빌드와 최종 JAR 패키징 성공.
- 연결 기능의 백엔드·Rust 검사 수치는 [연결 검증 기록](connection-verification.md)을 따릅니다. 이번 화면 변경으로 해당 로직을 변경하지 않았습니다.
- 실제 JAR에 가상 AI 도구를 연결한 인증 smoke 통과. 실제 계정이나 모델 호출은 하지 않았습니다.
- 브라우저에서 첫 서재 생성·가상 파일 업로드·미리보기 확인. 390px 화면에서 가로 넘침 없이 검색 입력 폭 301px 확인.
- README 이미지는 해당 JAR의 실제 UI를 브라우저에서 촬영했습니다. 목업이나 가짜 AI 답변이 아니며 가상 자료만 사용합니다.
- Intel DMG의 이미지·서명·CPU 검사, 설치 복사본에서 동봉 Java/React·업로드·검색·게시 실행 통과.
- Intel 앱 실행 파일을 격리 HOME·최소 PATH에서 실행하여 시작·중복 실행 거부·종료·재시작 통과. [기록](native-host-report.json). 빌드와 동시에 실행한 단일 시작 측정은 26,479ms로 일반 환경 성능 보증이 아닙니다.
- ARM DMG의 무결성·설치 복사본·앱/Java/Codex CPU 일치·ad-hoc 서명 검사 통과. 실제 ARM 실행은 하지 않았습니다.

## 설치 파일

[0.1.2 다운로드](https://github.com/thisisnew/knowledge-forge/releases/tag/v0.1.2-preview.1). 각 CPU의 DMG와 해당 `SHA256SUMS` 파일을 제공합니다.

| 파일 | 크기(bytes) | SHA-256 |
|---|---:|---|
| `Knowledge.Forge_0.1.2_x86_64.dmg` | 288099883 | `a687e1fcb83efdde1382e35e8c9beb3cf5345a1114cfe4894c8143ad9df53e97` |
| `Knowledge.Forge_0.1.2_arm64.dmg` | 275322001 | `cdadd53c5743c18d365dd80f07170fabbbb218105246e05688d83fbda8474509` |

두 앱에 들어간 JAR는 최종 빌드와 동일합니다. SHA-256: `4a52fb13f6994681c32393ad16a338a58175c1fd8fde62674e8c47c1c3ef5806`.
동봉 Java와 Codex 실행 파일은 각 CPU에 맞게 별도로 묶었습니다. 이 배포는 0.1.1 설치 파일을 대체하는 새 미리보기이며 이전 릴리스는 보존합니다.

## 검증 범위

Apple 공증은 요청에 따라 제외합니다. ARM 실기기 실행, 실제 계정의 로그인/답변은 별도 확인입니다.
Claude 실행 도구와 GitHub 수집용 Git은 아직 별도 실행 환경에 의존합니다.
이전 설치를 업데이트할 때 앱을 종료하고 자료 폴더 전체를 백업하세요. [설치·백업 안내](macos-install.md).
