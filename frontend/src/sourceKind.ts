export function sourceKindForLocation(kind: string, location: string): string {
  if (kind !== 'URL' && kind !== 'GITHUB') return kind
  const address = location.trim()
  return /^https:\/\/github\.com\/[A-Za-z0-9_-]+\/[A-Za-z0-9_.-]+\/?$/.test(address)
    && !/\/\.{1,2}\/?$/.test(address) ? 'GITHUB' : 'URL'
}
