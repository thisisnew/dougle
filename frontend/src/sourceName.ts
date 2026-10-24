export function sourceName(kind: string, location: string): string {
  let candidate = location.trim().split('/').filter(Boolean).at(-1) || '폴더'
  if (kind !== 'LOCAL_FOLDER') {
    const url = new URL(location.trim())
    candidate = url.pathname.split('/').filter(Boolean).at(-1) || url.hostname
    try { candidate = decodeURIComponent(candidate) } catch {}
    if (kind === 'GITHUB') candidate = candidate.replace(/\.git$/i, '')
  }
  const cleaned = candidate.replace(/[\\/\u0000-\u001f\u007f-\u009f]/g, ' ').trim()
  const fallback = kind === 'LOCAL_FOLDER' ? '폴더' : kind === 'GITHUB' ? 'GitHub' : '웹 링크'
  const name = cleaned && cleaned !== '.' && cleaned !== '..' ? cleaned : fallback
  let limited = ''
  for (const character of name) {
    if (limited.length + character.length > 120) break
    limited += character
  }
  return limited.trim()
}
