# Knowledge Forge 0.1.1 — Apple Silicon 미리보기

M 시리즈 Mac을 위한 **ARM64 앱 + ARM64 Java 21** 패키지입니다. Rosetta용 Intel 패키지가 아닙니다.
기능과 DB v21은 Intel 0.1.1과 같고, 공증은 사용자 요청에 따라 제외했습니다.

## 다운로드와 설치

`Knowledge.Forge_0.1.1_arm64.dmg`와 `SHA256SUMS-arm64`를 받습니다.
같은 폴더에서 `shasum -a 256 -c SHA256SUMS-arm64`로 무결성을 확인할 수 있습니다.
DMG SHA-256: `ff03267107138100f0ee7c3cd0ee0a711298978770a1ee27f86d26b31f118816` (164,655,958바이트).
기존 앱을 완전히 종료하고 데이터 폴더 전체를 백업한 뒤 Applications의 앱을 교체하세요.

Java는 포함되어 있습니다. AI는 별도 설치·로그인한 Codex/Claude Code CLI와 건별 전송 승인이 필요합니다.
자료 보관·검색·수동 지식 편집은 AI 없이 사용할 수 있습니다.

## 반드시 알아둘 점

ARM 대상 컴파일·앱 번들·DMG 무결성·설치 복사본 서명·앱/JVM 아키텍처 일치 검사를 통과했습니다. SQLite ARM 드라이버 포함도 확인했습니다.

- **Intel Mac에서 교차 빌드한 실험적 미리보기입니다. 실제 Apple Silicon 기기 실행은 아직 확인하지 않았습니다.**
- ad-hoc 서명만 적용하며 Developer ID 서명·Apple 공증은 없습니다. 회사 정책에 따라 실행이 막힐 수 있습니다.
- 새 DB를 구버전 앱으로 열지 마세요. Intel/ARM 앱은 같은 데이터 위치를 사용하므로 동시 실행하지 않습니다.
- 첫 체험은 가상 파일로 시작하세요. 실제 회사 자료·외부 AI 전송은 회사 정책을 먼저 확인하세요.

[설치 안내](https://github.com/thisisnew/knowledge-forge/blob/main/docs/macos-install.md) · [쉬운 사용법](https://github.com/thisisnew/knowledge-forge/blob/main/docs/how-it-works.md) · [ARM 빌드와 검증](https://github.com/thisisnew/knowledge-forge/blob/main/docs/apple-silicon.md) · [구조도](https://github.com/thisisnew/knowledge-forge/blob/main/docs/design-map.md)

Intel Mac은 [기존 Intel 릴리스](https://github.com/thisisnew/knowledge-forge/releases/tag/v0.1.1-preview.1)를 사용하세요.
