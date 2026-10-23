# Claude 간편 연결 — 무엇이 남았나요?

**현재 배포: 0.1.5.** Codex는 앱에 들어 있습니다. Claude는 이미 설치된 호환 도구를 연결하는 방식이며, 아직 앱에 포함하지 않았습니다.

## 왜 바로 넣지 않나요?

2026-10-23 확인한 [공식 제품 포함 조건](https://code.claude.com/docs/en/legal-and-compliance)은 Commercial Terms 적용, 바이너리·인증 방식 유지, 사용자 본인의 인증과 직접 과금을 요구합니다. 개발자가 사용자의 구독 인증을 대신 처리하는 것과 사용자가 원본 CLI에 직접 로그인하는 것은 구분됩니다. **현재 앱 방식에 대한 허용 확인이나 약관 동의를 테스트 통과로 대신할 수 없습니다.** 배포 주체의 확인 없이 약관에 대신 동의하거나 동봉하지 않습니다.

또한 [공식 설치 요구사항](https://code.claude.com/docs/en/setup)은 macOS 13 이상입니다. 앱의 현재 최소 설정은 11.3이므로, Claude 포함만으로 모든 지원 Mac에서 작동한다고 안내할 수 없습니다. 실제 배포할 CLI 버전의 요구사항도 다시 확인해야 합니다.

## 실제 코드와 대조

| 흐름 | 현재 구현 | 확인할 코드 |
|---|---|---|
| 앱에 실행 도구 포함 | Codex만 포함, Claude 동봉 없음 | `scripts/stage-codex.sh`, `desktop/src-tauri/tauri.conf.json`, `supervisor.rs` |
| Claude 경로 선택 | 저장 경로 우선, 없으면 설정값 또는 `claude`; 변경은 재시작 적용 | `CliLaunchConfiguration` |
| 연결 확인 | `auth --help` 지원 확인 후 `auth status` 판독 | `ProviderConnectionAdapter` |
| 로그인 | 별도 동의 후 CLI의 `auth login`; 종료 후 인증 재확인 | `ProviderLoginAdapter` |
| AI 실행 | 별도 자료 전송 승인 후 제한된 `--print` 요청 | `CliCuratorModel`, `AnswerJobService` |
| 비밀번호·토큰 | CLI가 관리; 앱 API/DB로 복사하지 않음 | `ProviderLoginAdapter`, `ProviderConnectionAdapter` |

인증 확인은 원격 모델 이용·과금·구독 사용 허용의 보증이 아닙니다. 코드상 인증 기능이 있다는 사실과 배포/이용 조건 충족은 별개입니다.

## 다음 구현의 완료 조건

1. 배포 주체가 약관 적용과 제품의 인증·요청 방식 허용을 확인합니다. 불명확하면 Anthropic에 문의합니다. 자동 다운로드로 바꾸어도 이 확인을 생략하지 않습니다.
2. 사용할 CLI 버전을 고정하고 Intel/ARM 원본 파일·해시·라이선스·서명을 검증합니다. 기존 사용자 설치/인증 파일을 덮어쓰지 않습니다.
3. 지원 macOS 정책을 정하고, 미지원 환경에서는 이유를 안내합니다. 바이너리와 제공자 인증 방식을 임의로 변경하지 않습니다.
4. 앱 내부 경로 연결, 누락·변조·CPU 불일치·취소·재시작 테스트를 추가합니다. 자료 전송 승인은 유지합니다.
5. 별도 승인받은 가상 자료로 실제 계정 로그인·답변·중단을 검증합니다. 가상 CLI 테스트를 실제 AI 성공으로 기록하지 않습니다.
6. Intel/ARM 설치본과 업데이트 서명을 검증하고 공개 배포합니다. ARM 실기기 검증 여부를 구분합니다.

이번 점검은 문서와 기존 연결 경로의 회귀 검사입니다. 앱·DB·설치 파일·업데이트 피드는 변경하지 않습니다. 현재 설치본은 그대로 0.1.5입니다.

## 재검사 결과 (2026-10-23)

- Java 연결/로그인 어댑터·HTTP 보호·구조 계약 **13건 통과**, 실패/제외 0건.
- 제공자 선택·늦은 응답 무시·동의·로그인·취소 UI **6건 통과**.
- 기존 모델 조사 목록의 **318개 파일 해시 일치**. 전 실행 경로의 커버리지나 약관 적합성 검증은 아닙니다.
- 실제 계정 로그인·외부 AI 전송·전체 E2E 재실행은 하지 않았습니다. 기존 설치본 전체 검증은 [0.1.5 보고서](release-0.1.5-report.md)를 따릅니다.

재현:

```bash
bash scripts/maven.sh -Dtest=ProviderConnectionAdapterTest,ProviderLoginAdapterTest,ProviderConnectionIntegrationTest,ArchitectureContractTest test
cd frontend && npm test -- --maxWorkers=2 src/ProviderConnectionPanel.test.tsx
```
