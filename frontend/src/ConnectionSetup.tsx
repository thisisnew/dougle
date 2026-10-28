import { useState } from 'react'
import { Button } from './components/ui/button'
import type { ConnectionIndicator } from './connectionIndicator'
import { plugins, type PluginId } from './plugins'

const preferenceKey = 'dougle.connection-setup.dismissed.v1'

export default function ConnectionSetup({ connection, disabled, onAi, onPlugin, alwaysVisible = false }: {
  connection: ConnectionIndicator | null
  disabled: boolean
  onAi: () => void
  onPlugin: (id: PluginId) => void
  alwaysVisible?: boolean
}) {
  const [dismissed, setDismissed] = useState(() => {
    try { return localStorage.getItem(preferenceKey) === 'true' } catch { return false }
  })
  function dismiss() {
    setDismissed(true)
    try { localStorage.setItem(preferenceKey, 'true') } catch { return }
  }
  if (dismissed && !alwaysVisible) return null
  return <section className="connection-setup" aria-label="업무 환경 연결">
    <h2>업무 환경 연결</h2>
    <p>사용할 AI와 자료 서비스를 연결하세요. 회사 로그인은 브라우저에서 진행합니다.</p>
    <div className="connection-setup-actions">
      <Button variant="outline" disabled={disabled || connection?.state === 'CHECKING'} onClick={onAi}>
        {connection?.state === 'AUTHENTICATED' ? `${connection.provider} 로그인 확인됨` : 'AI 연결 설정'}
      </Button>
      {plugins.map(plugin => <Button key={plugin.id} variant="outline" disabled={disabled} onClick={() => onPlugin(plugin.id)}>{plugin.name} 연결</Button>)}
    </div>
    <p>GitHub는 현재 공개 저장소만 지원합니다. 회사 비공개 저장소 인증은 아직 지원하지 않습니다.</p>
    {!alwaysVisible && <Button variant="ghost" disabled={disabled} onClick={dismiss}>안내 닫기</Button>}
  </section>
}
