type DesktopWindow = Window & { __TAURI_INTERNALS__?: { invoke: <T>(command: string) => Promise<T> } }

export function hasFolderPicker() {
  return typeof (window as DesktopWindow).__TAURI_INTERNALS__?.invoke === 'function'
}

export async function selectFolder(): Promise<string | null> {
  const desktop = (window as DesktopWindow).__TAURI_INTERNALS__
  if (!desktop) throw new Error('폴더 연결은 설치한 Dougle 앱에서 사용할 수 있습니다. 개별 파일은 파일 추가를 이용하세요.')
  return desktop.invoke<string | null>('select_source_folder')
}
