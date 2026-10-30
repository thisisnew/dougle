# 공개 업데이트 파일로 실제 앱 교체 확인

**Apple Silicon Mac에서0.1.38 → 0.1.40 교체를 확인했습니다.** GitHub의 새 ARM Mac에서 이전 공개 DMG와 새 공개 피드·서명 아카이브를 사용했습니다. 사용자 PC의 앱이나 실제 자료는 건드리지 않았습니다. [실행 기록](https://github.com/thisisnew/dougle/actions/runs/41097881980) · [결과 JSON](published-update-report.json).

## 확인한 순서

1. 이전 공개 ARM DMG의 GitHub 해시와 앱 코드 서명을 확인합니다.
2. 임시 설치본의 동봉 Java/JAR로 가상 Workspace·원본 파일·출처 있는 문서를 만들고, 게시본과 사람의 미게시 수정본도 저장합니다.
3. 서버를 정상 종료하고 앱과 같은 Tauri 업데이트 라이브러리가 공개 피드에서 실제 서명 아카이브를 받습니다.
4. 기본 버전 비교·고정 공개 키·서명된 버전 검사를 거쳐 앱을 교체합니다.
5. 새 버전·코드 서명·검증된 제품 JAR 해시를 확인하고 같은 SQLite를 열어 Workspace·선택 상태·파일 원문·수정본·게시본·근거 응답이 동일한지 비교합니다.
6. 교체된 실제 네이티브 앱을 실행해 세션 보호·중복 실행 거절·부모 종료 시 Java 정리·재시작을 확인합니다. [네이티브 결과](native-host-report.json).

검사 도구만 이전 앱 버전을 사용합니다. 제품의 버전 비교나 보안 설정을 바꾸지 않았습니다. 교체 경로는 임시 폴더로 제한하며 자격 증명과 사용자 DB를 복사하지 않습니다. 외부 AI 호출은0회입니다.

## 확인하지 않은 것

- 실제 사용자 메뉴 클릭, Applications 설치 권한 창, Finder/Gatekeeper.
- 회사 계정 SSO, 회사 M5 환경, 실제 사용자 DB의 전체 데이터.
- 외부 AI의 답변 품질, Apple 공증.

최초 실행41097609656은 CI Cargo 홈의 잘못된 링크로 검사 도구 빌드 단계에서 실패했습니다. 전용 도구 디렉토리로 수정한 뒤 전체 공개 업데이트 시나리오를 통과했습니다. 실패 이력을 제품 업데이트 성공으로 계산하지 않습니다.

## 재현

Apple Silicon Mac에서 이전 공개 `release/Dougle_0.1.38_arm64.dmg`와 현재 소스·검증된 JAR 해시 기록을 준비한 뒤 실행합니다.

```bash
node scripts/published-update-smoke.mjs 0.1.38
```

`.github/workflows/verify-published-upgrade.yml`도 같은 검증을 수행합니다. 이전 공개 파일 확인용 GitHub 조회 인증, 고정 Rust 도구 체인과 macOS GUI 세션이 필요합니다. 공개 아카이브를 실제로 내려받으며 검사 후 임시 설치본을 정리합니다. Intel은 개발·인수·배포 지원 범위에서 제외합니다.
