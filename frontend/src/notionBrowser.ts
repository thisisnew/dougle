export async function openNotionConnections() {
  const desktop = (window as Window & { __TAURI_INTERNALS__?: { invoke: (command: string) => Promise<unknown> } }).__TAURI_INTERNALS__
  if (desktop) await desktop.invoke('open_notion_connections')
  else window.open('https://www.notion.so/profile/integrations', '_blank', 'noopener,noreferrer')
}
