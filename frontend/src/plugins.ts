export type PluginId = 'NOTION' | 'ATLASSIAN' | 'MYSQL'

export const plugins = [
  { id: 'NOTION' as const, name: 'Notion' },
  { id: 'ATLASSIAN' as const, name: 'Jira · Confluence' },
  { id: 'MYSQL' as const, name: 'MySQL' },
]

export function pluginForUrl(location: string): PluginId | null {
  try {
    const url = new URL(location)
    if (url.protocol !== 'https:') return null
    const host = url.hostname.toLowerCase().replace(/\.$/, '')
    if (host.endsWith('.atlassian.net')) return 'ATLASSIAN'
    if (host === 'app.notion.com' || host === 'notion.so' || host.endsWith('.notion.so')
      || host === 'notion.site' || host.endsWith('.notion.site')
      || (['notion.com', 'www.notion.com'].includes(host) && url.pathname.startsWith('/p/'))) return 'NOTION'
  } catch { return null }
  return null
}
