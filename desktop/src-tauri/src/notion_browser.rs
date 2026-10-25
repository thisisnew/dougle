use std::sync::atomic::Ordering;
use tauri::Manager;

#[tauri::command]
pub async fn open_notion_connections(window: tauri::WebviewWindow) -> Result<(), String> {
    let state = window.state::<crate::folder_picker::FolderPicker>();
    let url = window.url().map_err(|_| "앱 창을 확인할 수 없습니다.")?;
    if window.label() != "main"
        || !knowledge_forge_desktop::protocol::owned_origin(&url, state.port.load(Ordering::SeqCst))
    {
        return Err("앱의 플러그인 화면에서 다시 시도하세요.".into());
    }
    tauri::async_runtime::spawn_blocking(|| {
        let status = std::process::Command::new("/usr/bin/open")
            .arg("https://www.notion.so/profile/integrations")
            .stdin(std::process::Stdio::null())
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .status()
            .map_err(|_| "브라우저를 열지 못했습니다.".to_owned())?;
        if status.success() { Ok(()) } else { Err("브라우저를 열지 못했습니다.".to_owned()) }
    }).await.map_err(|_| "브라우저를 열지 못했습니다.".to_owned())?
}
