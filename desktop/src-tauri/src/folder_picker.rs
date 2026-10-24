use std::sync::atomic::{AtomicBool, AtomicU16, Ordering};
use tauri::Manager;
use tauri_plugin_dialog::DialogExt;

#[derive(Default)]
pub struct FolderPicker {
    pub port: AtomicU16,
    open: AtomicBool,
}

#[tauri::command]
pub async fn select_source_folder(window: tauri::WebviewWindow) -> Result<Option<String>, String> {
    let state = window.state::<FolderPicker>();
    let url = window.url().map_err(|_| "앱 창을 확인할 수 없습니다.")?;
    if window.label() != "main"
        || !knowledge_forge_desktop::protocol::owned_origin(&url, state.port.load(Ordering::SeqCst))
    {
        return Err("앱의 자료 화면에서 다시 시도하세요.".into());
    }
    if state.open.swap(true, Ordering::SeqCst) {
        return Err("이미 폴더를 선택하고 있습니다.".into());
    }
    let picker_window = window.clone();
    let result = tauri::async_runtime::spawn_blocking(move || {
        picker_window.dialog().file().set_parent(&picker_window)
            .set_title("연결할 폴더 선택").blocking_pick_folder()
            .map(|selected| {
                let path = selected.into_path().map_err(|_| "폴더 경로를 읽을 수 없습니다.")?;
                let metadata = std::fs::symlink_metadata(&path).map_err(|_| "폴더에 접근할 수 없습니다.")?;
                if !metadata.is_dir() || metadata.file_type().is_symlink() {
                    return Err("심볼릭 링크가 아닌 실제 폴더를 선택하세요.");
                }
                path.to_str().map(str::to_owned).ok_or("지원하지 않는 폴더 이름입니다.")
            }).transpose().map_err(str::to_owned)
    }).await.map_err(|_| "폴더 선택기를 열지 못했습니다.".to_owned());
    state.open.store(false, Ordering::SeqCst);
    result?
}
