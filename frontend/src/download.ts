import { apiFetch } from './desktopTransport'

function downloadName(disposition: string | null, fallback: string) {
  const extended = disposition?.match(/filename\*=UTF-8''([^;]+)/i)
  if (extended) {
    try { return decodeURIComponent(extended[1].trim()) } catch {}
  }
  const plain = disposition?.match(/filename="((?:\\.|[^"\\])*)"|filename=([^;]+)/i)
  return plain ? (plain[1]?.replace(/\\(.)/g, '$1') ?? plain[2].trim()) : fallback
}

export async function downloadApi(path: string, filename: string): Promise<void> {
  const response = await apiFetch(path)
  if (!response.ok) throw new Error(`다운로드하지 못했습니다. (${response.status})`)
  const blob = await response.blob()
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  try {
    anchor.href = url
    anchor.download = downloadName(response.headers.get('Content-Disposition'), filename)
    document.body.append(anchor)
    anchor.click()
  } finally {
    anchor.remove()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  }
}
