import type { Provider } from './providerPreference'

export type ConnectionIndicator = { provider: Provider; state: string }
export const connectionLabels: Record<string, string> = {
  AUTHENTICATED: '로그인 확인', LOGIN_REQUIRED: '로그인 필요', TOOL_UNAVAILABLE: '실행 도구 없음',
  UNSUPPORTED: '확인 지원 안 됨', CHECK_FAILED: '확인 실패', CHECKING: '확인 중…', UNCHECKED: '아직 확인하지 않음',
}
