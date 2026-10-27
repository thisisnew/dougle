export function sourceKindForLocation(kind: string, location: string): string {
  return sourceAddress(kind, location).kind
}

export function sourceAddress(kind: string, location: string): { kind: string; location: string } {
  const address = location.trim()
  if (kind !== 'URL' && kind !== 'GITHUB') return { kind, location: address }
  const repository = /^https:\/\/github\.com(?::443)?(\/[A-Za-z0-9_-]+\/[A-Za-z0-9_.-]+\/?)(?:\?tab=readme-ov-file)?(?:#readme)?$/i.exec(address)
  if (repository && !/\/\.{1,2}\/?$/.test(repository[1])) {
    return { kind: 'GITHUB', location: 'https://github.com' + repository[1] }
  }
  return { kind: 'URL', location: address }
}
