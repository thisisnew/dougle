export function readMaterialSync(workspaceId: string): string | null {
  try {
    const value = localStorage.getItem(`dougle.material-sync.${workspaceId}`)
    return value && Number.isFinite(Date.parse(value)) ? value : null
  } catch { return null }
}

export function recordMaterialSync(workspaceId: string): string {
  const value = new Date().toISOString()
  try { localStorage.setItem(`dougle.material-sync.${workspaceId}`, value) } catch {}
  return value
}
