import { useEffect, useState } from 'react'
import * as Dialog from '@radix-ui/react-dialog'
import { Database, LoaderCircle, Plug } from 'lucide-react'
import notionIcon from './assets/plugins/notion.svg'
import jiraIcon from './assets/plugins/jira.svg'
import confluenceIcon from './assets/plugins/confluence.svg'
import mysqlIcon from './assets/plugins/mysql.png'
import { Button } from './components/ui/button'
import { request } from './api'
import { plugins, pluginForUrl, type PluginId } from './plugins'
import type { Source } from './SourcePanel'
import BrowserPluginConnect from './BrowserPluginConnect'

type Mysql = { host: string; port: number; database: string; username: string; password: string; sslMode: string }
type Connections = { notionConnected: boolean; atlassianConnected?: boolean; mysqlConnected: boolean; mysql: Mysql | null }
const emptyMysql: Mysql = { host: 'localhost', port: 3306, database: '', username: '', password: '', sslMode: 'VERIFY_IDENTITY' }
type Props = { initialPlugin?: PluginId | null; workspaceId?: string | null; onBusyChange?: (busy: boolean) => void; onChanged?: () => void }

export default function PluginPanel({ initialPlugin = null, workspaceId, onBusyChange, onChanged }: Props) {
  const [selected, setSelected] = useState<PluginId | null>(initialPlugin)
  const [connections, setConnections] = useState<Connections | null>(null)
  const [mysql, setMysql] = useState<Mysql>(emptyMysql)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')
  const plugin = plugins.find(item => item.id === selected)
  const isConnected = (id: PluginId) => !!(id === 'NOTION' ? connections?.notionConnected : id === 'ATLASSIAN' ? connections?.atlassianConnected : connections?.mysqlConnected)
  const connected = selected ? isConnected(selected) : false
  useEffect(() => {
    const abort = new AbortController()
    request<Connections>('/plugins', { signal: abort.signal }).then(value => {
      setConnections(value)
      if (value.mysql) setMysql(value.mysql)
    }).catch(reason => { if (!abort.signal.aborted) setError(reason.message) })
    return () => abort.abort()
  }, [])
  useEffect(() => { onBusyChange?.(busy); return () => onBusyChange?.(false) }, [busy, onBusyChange])

  function open(id: PluginId | null) {
    if (busy) return
    setSelected(id); setMysql(connections?.mysql || emptyMysql); setError(''); setMessage('')
  }
  function changeMysql(field: keyof Mysql, value: string | number) {
    setMysql(previous => ({ ...previous, [field]: value })); setMessage(''); setError('')
  }
  async function refreshConnections() { setConnections(await request<Connections>('/plugins')) }
  async function syncConnected() {
    await refreshConnections()
    if (!workspaceId) return
    const sources = await request<Source[]>(`/workspaces/${workspaceId}/sources`)
    let failed = 0
    try {
      for (const source of sources.filter(item => item.kind === 'URL' && pluginForUrl(item.location) === selected)) {
        setMessage(`${source.name} 동기화 중…`)
        const result = await request<Source>(`/workspaces/${workspaceId}/sources/${source.id}/sync`, { method: 'POST' })
        if (result.status === 'ERROR') failed++
      }
    } finally { setMessage(''); onChanged?.() }
    if (failed) setMessage(`${failed}개 자료의 접근 권한을 확인해 주세요.`)
  }
  async function run(action: 'test' | 'save' | 'disconnect' | 'import') {
    if (busy || !selected) return
    setBusy(true); setError(''); setMessage('')
    try {
      const path = `/plugins/${selected.toLowerCase()}`
      if (action === 'disconnect') {
        setConnections(await request<Connections>(path, { method: 'DELETE' }))
        setMysql(emptyMysql); setMessage('연결을 해제했습니다. 보관한 자료는 유지됩니다.')
      } else if (action === 'import') {
        const config = connections?.mysql
        if (!config || !workspaceId) return
        const location = `${config.host}:${config.port}/${config.database}`
        const sources = await request<Source[]>(`/workspaces/${workspaceId}/sources`)
        const existing = sources.find(source => source.kind === 'MYSQL' && source.location === location)
        const result = existing
          ? await request<Source>(`/workspaces/${workspaceId}/sources/${existing.id}/sync`, { method: 'POST' })
          : await request<Source>(`/workspaces/${workspaceId}/sources`, { method: 'POST', body: JSON.stringify({ name: `${config.database} 구조`, kind: 'MYSQL', location, domain: '', sync: true }) })
        if (result.status === 'ERROR') throw new Error(result.error || 'DB 구조를 불러오지 못했습니다.')
        setMessage('자료에 DB 구조를 추가했습니다.')
      } else {
        const value = await request<Connections | undefined>(path + (action === 'test' ? '/test' : ''), {
          method: 'POST', body: JSON.stringify(mysql),
        })
        if (action === 'test') setMessage('연결 테스트에 성공했습니다. 저장하려면 연결을 누르세요.')
        else if (value) {
          setConnections(value); setMysql(value.mysql || emptyMysql); setMessage('연결했습니다.')
        }
      }
    } catch (reason) { setError(reason instanceof Error ? reason.message : '연결하지 못했습니다.') }
    finally { if (action === 'save' || action === 'import') onChanged?.(); setBusy(false) }
  }
  const valid = !!mysql.host.trim() && !!mysql.database.trim() && !!mysql.username.trim() && mysql.port > 0 && mysql.port <= 65535
  return <section className="plugins-page" aria-label="플러그인">
    <div className="page-heading"><h1>플러그인</h1></div>
    {!plugin && error && <p role="alert">{error}</p>}
    <div className="plugin-list">{plugins.map(item => <article className="plugin-card" key={item.id} aria-label={item.name}>
      <div className={`plugin-icon ${item.id.toLowerCase()}`} aria-hidden="true">{item.id === 'ATLASSIAN' ? <><img src={jiraIcon} alt="" /><img src={confluenceIcon} alt="" /></> : <img src={item.id === 'NOTION' ? notionIcon : mysqlIcon} alt="" />}</div>
      <div className="plugin-details"><h2>{item.name}</h2></div>
      <span className="plugin-state">{!connections ? '확인 중' : isConnected(item.id) ? '연결됨' : '연결 안 됨'}</span>
      <Button variant="outline" disabled={busy} onClick={() => open(item.id)} aria-label={`${item.name} 연결 설정`}><Plug size={16} />연결 설정</Button>
    </article>)}</div>
    <Dialog.Root open={!!plugin} onOpenChange={value => { if (!value) open(null) }}>
      <Dialog.Portal><Dialog.Overlay className="dialog-overlay" /><Dialog.Content className="dialog-content plugin-dialog" aria-describedby={undefined} onEscapeKeyDown={event => { if (busy) event.preventDefault() }} onPointerDownOutside={event => { if (busy) event.preventDefault() }}>
        <Dialog.Title>{plugin?.name} 연결</Dialog.Title>
        {selected !== 'MYSQL' && error && <p className="plugin-message error" role="alert">{error}</p>}
        {selected && selected !== 'MYSQL' ? <><BrowserPluginConnect key={selected} provider={selected.toLowerCase()} connected={connected} ready={!!connections} onBusyChange={setBusy} onConnected={syncConnected} onRefresh={refreshConnections} onClose={() => open(null)} />{message && <p className="plugin-message" role="status">{message}</p>}</> : <form onSubmit={event => { event.preventDefault(); if (valid) void run('save') }}>
          <fieldset className="plugin-fields" disabled={busy || !connections}>
              <div className="plugin-host-row"><label>호스트<input value={mysql.host} autoComplete="off" onChange={event => changeMysql('host', event.target.value)} required maxLength={253} /></label><label>포트<input type="number" min={1} max={65535} value={mysql.port} onChange={event => changeMysql('port', Number(event.target.value))} required /></label></div>
              <label>데이터베이스<input value={mysql.database} onChange={event => changeMysql('database', event.target.value)} required maxLength={64} /></label>
              <label>사용자 ID<input value={mysql.username} autoComplete="off" onChange={event => changeMysql('username', event.target.value)} required maxLength={128} /></label>
              <label>비밀번호<input type="password" autoComplete="new-password" value={mysql.password} maxLength={4096} placeholder={connected ? '변경 시 비밀번호를 다시 입력하세요' : ''} onChange={event => changeMysql('password', event.target.value)} /></label>
              <label>TLS<select value={mysql.sslMode} onChange={event => changeMysql('sslMode', event.target.value)}><option value="VERIFY_IDENTITY">인증서·호스트 검증 (권장)</option><option value="REQUIRED">암호화만 (인증서 검증 안 함)</option><option value="DISABLED">해제 (로컬 DB만)</option></select></label>
              {connections?.mysqlConnected && <Button type="button" variant="outline" disabled={!workspaceId} onClick={() => void run('import')}><Database size={16} />DB 구조를 자료에 추가</Button>}
              <small>테이블·열 구조만 수집합니다. 행 데이터·SQL 편집·SSH 터널은 지원하지 않습니다.</small>
          </fieldset>
          {error && <p className="plugin-message error" role="alert">{error}</p>}
          {(busy || message) && <p className="plugin-message" role="status">{busy && <LoaderCircle className="animate-spin" size={15} />}{message || '연결 확인 중…'}</p>}
          <div className="dialog-actions plugin-actions">
            {connected && <Button type="button" variant="ghost" disabled={busy} onClick={() => void run('disconnect')}>연결 해제</Button>}
            <Button type="button" variant="outline" disabled={busy} onClick={() => open(null)}>닫기</Button>
            <Button type="button" variant="outline" disabled={busy || !valid || !connections} onClick={() => void run('test')}>연결 테스트</Button>
            <Button type="submit" disabled={busy || !valid || !connections}>연결</Button>
          </div>
        </form>}
      </Dialog.Content></Dialog.Portal>
    </Dialog.Root>
  </section>
}
