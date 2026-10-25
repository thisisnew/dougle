# Dougle 0.1.0 — Intel Mac 미리보기

Java 21을 포함한 Intel(x86_64) Mac 설치 파일입니다. DMG를 열고 앱을 Applications로 복사합니다.

**ad-hoc 서명만 적용했으며 Apple 공증은 없습니다.** Apple Silicon/Windows 패키지가 아닙니다. 첫 체험에는 가상 자료를 사용하세요.

## 가능한 기능
- Workspace·원본/지식 탐색, 파일·폴더·공개 URL·GitHub 수동 수집.
- 문서/Java 분석, Lucene 검색, 변경 파일만 다시 분석.
- 근거 연결 지식 초안·사람 편집·검토·게시·버전 보존.
- 별도 CLI AI 승인·답변·SSE·취소·이력·선택한 문맥 이어 묻기.

## 검증
- UI 185개, 백엔드 316개, Rust 12개 통과. 실제 AI opt-in 1개 미실행.
- DMG 설치 복사본의 인증된 네이티브 React·동봉 Java·업로드·검색·다운로드 검증.
- 실제 앱의 중복 기동 방지·부모 종료 후 Java 정리·재시작 검증.
- AI 검증은 가상 CLI이며 실제 서비스 호환성 검증이 아닙니다.

## 남은 것
Source 자동 폴링, Git 네트워크 증분, 관계 검색, Finder의 CLI 설정 UX, 전체 설치형 회귀, 공증과 다른 Mac 검증은 진행 중입니다. 전체 제품 완료 버전은 아닙니다.

[설치·백업 안내](https://github.com/thisisnew/dougle/blob/main/docs/macos-install.md) · [검증 보고서](https://github.com/thisisnew/dougle/blob/main/docs/desktop-report.md) · [남은 작업](https://github.com/thisisnew/dougle/blob/main/docs/backlog.md)

첨부한 `SHA256SUMS`와 파일 해시를 확인하세요. 출처가 불분명한 재배포본을 실행하거나 macOS 보안을 전체 해제하지 마세요.
