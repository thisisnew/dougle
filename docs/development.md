# 개발 환경 실행

제품 지원 대상은 **Apple Silicon(M 시리즈) Mac만**입니다. 데스크톱 개발·릴리스 빌드 타깃은 `aarch64-apple-darwin`이며 Intel 호환성은 개발/인수 범위에서 제외합니다. `desktop/tauri.sh`와 `scripts/package-macos.sh`가 이를 강제합니다. 일반 백엔드·화면·서명 도구의 호스트 실행은 별개입니다. 아래 과거 Intel 패키징 기록은 현재 지원 범위가 아닙니다.

앱 사용자에게 아래 도구 설치나 명령 실행을 요구하지 않습니다. [앱 소개와 설치](../README.md).

Java 21, Node.js 22.12+ 또는 24, Git이 필요합니다. Maven은 저장소 Wrapper를 사용합니다.

```bash
bash scripts/build.sh
bash scripts/run.sh
```

`http://127.0.0.1:8787`에서 엽니다. 개발 데이터는 `.data`에 저장되며 설치 앱 데이터와 다릅니다.
`FORGE_DATA_DIR`로 저장 위치, `PORT`로 포트를 지정할 수 있습니다. 개발 서버 두 개는 `bash scripts/dev.sh`로 실행합니다.

```bash
bash scripts/maven.sh test
(cd frontend && npm test -- --maxWorkers=2 && npm run build)
node scripts/smoke.mjs
node scripts/crash-recovery-smoke.mjs
bash scripts/desktop-cargo.sh test --locked
```

[아키텍처](architecture.md) · [API](api.md) · [Mac 빌드](macos-build.md) · [작업 인수인계](../HANDOFF.md).

## 변경 완료 체크리스트

1. 변경한 기능의 테스트와 빌드를 실행한다.
2. UI 변경은 가상 자료로 실제 화면을 확인하고 README 스크린샷을 갱신한다.
3. 공통 JAR와 각 CPU용 Java/Codex/Tauri를 묶어 Intel/ARM DMG를 순차 빌드한다.
4. Intel 설치 복사본 실행, ARM 정적 검사, 서명/체크섬을 확인한다. ARM 실기기 검증과 혼동하지 않는다.
5. 코드·고객 소개·설치 안내·검증 결과를 커밋하고 새 Release에 설치 파일과 체크섬을 올린다.
6. GitHub의 실제 공개 자산/해시와 README 다운로드 링크가 일치하는지 확인한 뒤 배포 완료로 보고한다.
7. 0.1.3부터 서명된 앱 업데이트 파일도 올린다. 공개 후 `node scripts/update-manifest.mjs --publish`로 검증된 피드를 커밋·푸시한다. [키 관리·정확한 순서](automatic-updates.md).

기존 릴리스 파일을 조용히 덮어쓰지 않는다. 문서나 배포되지 않는 테스트 도구만 수정하고 앱·의존성·패키징 입력이 동일할 때는 기존 실행 파일을 유지할 수 있지만 기능 변경에는 새 설치 파일이 필요하다. 공증은 현재 범위에서 제외한다.
