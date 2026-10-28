use crate::Lifecycle;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use tauri::Manager;
use tauri_plugin_dialog::{DialogExt, MessageDialogButtons};
use tauri_plugin_updater::UpdaterExt;

pub const MENU_ID: &str = "forge-check-update";

#[derive(Default)]
pub struct UpdateState {
    busy: AtomicBool,
    status: Mutex<serde_json::Value>,
    checked: Mutex<Option<Instant>>,
}

fn update_status_value(
    app: &tauri::AppHandle,
    phase: &str,
    version: Option<&str>,
    progress: Option<u64>,
) {
    let state = app.state::<UpdateState>();
    let mut status = state.status.lock().unwrap();
    let version = version
        .map(str::to_owned)
        .or_else(|| status["version"].as_str().map(str::to_owned));
    *status = serde_json::json!({ "phase": phase, "version": version, "progress": progress });
}

fn validate_window(window: &tauri::WebviewWindow) -> Result<(), String> {
    let port = window
        .state::<crate::folder_picker::FolderPicker>()
        .port
        .load(Ordering::SeqCst);
    let url = window.url().map_err(|_| "앱 창을 확인할 수 없습니다.")?;
    if window.label() != "main" || !dougle_desktop::protocol::owned_origin(&url, port) {
        return Err("앱에서 업데이트를 확인해 주세요.".into());
    }
    Ok(())
}

#[tauri::command]
pub fn update_status(window: tauri::WebviewWindow) -> Result<serde_json::Value, String> {
    validate_window(&window)?;
    let state = window.state::<UpdateState>();
    let due = state
        .checked
        .lock()
        .unwrap()
        .is_none_or(|time| time.elapsed() >= Duration::from_secs(3600));
    if due {
        check(
            window.app_handle().clone(),
            window.state::<Arc<Lifecycle>>().inner().clone(),
            false,
        );
    }
    let status = state.status.lock().unwrap().clone();
    Ok(if status.is_null() {
        serde_json::json!({"phase":"idle"})
    } else {
        status
    })
}

#[tauri::command]
pub async fn install_update(window: tauri::WebviewWindow) -> Result<(), String> {
    validate_window(&window)?;
    check(
        window.app_handle().clone(),
        window.state::<Arc<Lifecycle>>().inner().clone(),
        true,
    );
    Ok(())
}

struct Reset(tauri::AppHandle);

struct InstallGuard<'a>(&'a Lifecycle);

impl Drop for InstallGuard<'_> {
    fn drop(&mut self) {
        self.0.finish_update();
    }
}

impl Drop for Reset {
    fn drop(&mut self) {
        self.0
            .state::<UpdateState>()
            .busy
            .store(false, Ordering::SeqCst);
        menu(&self.0, "업데이트 확인…", true);
        title(&self.0, "Dougle");
        let state = self.0.state::<UpdateState>();
        let mut status = state.status.lock().unwrap();
        if status["phase"] != "error" && status["phase"] != "current" {
            status["phase"] = if status["version"].is_string() {
                "available"
            } else {
                "idle"
            }
            .into();
        }
    }
}

fn menu(app: &tauri::AppHandle, text: &str, enabled: bool) {
    let item = app
        .menu()
        .and_then(|menu| menu.items().ok())
        .and_then(|items| {
            items.into_iter().find_map(|item| match item {
                tauri::menu::MenuItemKind::Submenu(submenu) => submenu.get(MENU_ID),
                _ => None,
            })
        });
    if let Some(tauri::menu::MenuItemKind::MenuItem(item)) = item {
        let _ = item.set_text(text);
        let _ = item.set_enabled(enabled);
    }
}

fn title(app: &tauri::AppHandle, text: &str) {
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.set_title(text);
    }
}

fn message(app: &tauri::AppHandle, text: &str) {
    app.dialog()
        .message(text)
        .title("Dougle 업데이트")
        .blocking_show();
}

fn confirm(app: &tauri::AppHandle, text: String, action: &str) -> bool {
    app.dialog()
        .message(text)
        .title("Dougle 업데이트")
        .buttons(MessageDialogButtons::OkCancelCustom(
            action.into(),
            "나중에".into(),
        ))
        .blocking_show()
}

pub fn check(app: tauri::AppHandle, lifecycle: Arc<Lifecycle>, manual: bool) {
    tauri::async_runtime::spawn(run_check(app, lifecycle, manual));
}

async fn run_check(app: tauri::AppHandle, lifecycle: Arc<Lifecycle>, manual: bool) {
    if lifecycle.cancelled.load(Ordering::SeqCst) || app.get_webview_window("main").is_none() {
        return;
    }
    if app
        .state::<UpdateState>()
        .busy
        .compare_exchange(false, true, Ordering::SeqCst, Ordering::SeqCst)
        .is_err()
    {
        return;
    }
    menu(&app, "업데이트 확인 중…", false);
    update_status_value(&app, "checking", None, None);
    *app.state::<UpdateState>().checked.lock().unwrap() = Some(Instant::now());
    let _reset = Reset(app.clone());
    let outcome = apply(&app, &lifecycle, manual).await;
    if outcome.is_err() && !lifecycle.cancelled.load(Ordering::SeqCst) {
        update_status_value(&app, "error", None, None);
    }
}

fn trusted_package(url: &tauri::Url, version: &str) -> bool {
    let architecture = if cfg!(target_arch = "aarch64") {
        "arm64"
    } else {
        "x86_64"
    };
    url.scheme() == "https"
        && url.host_str() == Some("github.com")
        && url.username().is_empty()
        && url.password().is_none()
        && url.port().is_none()
        && url.query().is_none()
        && url.fragment().is_none()
        && url.path().starts_with("/thisisnew/dougle/releases/download/")
        && url.path().ends_with(&format!(
            "/Dougle_{version}_{architecture}.app.tar.gz"
        ))
}

fn release_manifest(releases: &serde_json::Value) -> Option<tauri::Url> {
    releases
        .as_array()?
        .iter()
        .filter(|release| release["draft"] == false)
        .filter_map(|release| {
            let tag = release["tag_name"].as_str()?;
            let version = tag.strip_prefix('v')?.split('-').next()?;
            let parts: Vec<u64> = version
                .split('.')
                .map(str::parse)
                .collect::<Result<_, _>>()
                .ok()?;
            if parts.len() != 3 {
                return None;
            }
            let expected =
                format!("https://github.com/thisisnew/dougle/releases/download/{tag}/latest.json");
            let asset = release["assets"].as_array()?.iter().find(|asset| {
                asset["name"] == "latest.json"
                    && asset["state"] == "uploaded"
                    && asset["browser_download_url"] == expected
            })?;
            let url = asset["browser_download_url"]
                .as_str()?
                .parse::<tauri::Url>()
                .ok()?;
            Some((parts, url))
        })
        .max_by(|left, right| left.0.cmp(&right.0))
        .map(|(_, url)| url)
}

async fn discover_manifest() -> Option<tauri::Url> {
    let client = reqwest::Client::builder()
        .timeout(Duration::from_secs(10))
        .user_agent("Dougle-Updater")
        .build()
        .ok()?;
    let mut response = client
        .get("https://api.github.com/repos/thisisnew/dougle/releases?per_page=20")
        .send()
        .await
        .ok()?
        .error_for_status()
        .ok()?;
    let mut bytes = Vec::new();
    while let Some(chunk) = response.chunk().await.ok()? {
        if bytes.len() + chunk.len() > 2_000_000 {
            return None;
        }
        bytes.extend_from_slice(&chunk);
    }
    release_manifest(&serde_json::from_slice(&bytes).ok()?)
}

async fn apply(app: &tauri::AppHandle, lifecycle: &Lifecycle, manual: bool) -> Result<(), ()> {
    let mut builder = app.updater_builder().timeout(Duration::from_secs(15));
    if let Some(manifest) = discover_manifest().await {
        builder = builder
            .endpoints(vec![
                manifest,
                "https://raw.githubusercontent.com/thisisnew/dougle/main/updates/preview.json"
                    .parse()
                    .map_err(|_| ())?,
            ])
            .map_err(|_| ())?;
    }
    let updater = builder.build().map_err(|_| ())?;
    let checked = updater.check().await;
    let mut update = match checked {
        Ok(Some(update)) => update,
        Ok(None) => {
            *app.state::<UpdateState>().status.lock().unwrap() =
                serde_json::json!({"phase":"current"});
            return Ok(());
        }
        Err(_) => return Err(()),
    };
    if lifecycle.cancelled.load(Ordering::SeqCst) {
        return Ok(());
    }
    if !trusted_package(&update.download_url, &update.version) {
        return Err(());
    }
    update_status_value(app, "available", Some(&update.version), None);
    if !manual {
        return Ok(());
    }
    if !confirm(
        app,
        format!(
            "새 버전 {}이 있습니다. 다운로드하는 동안 앱을 계속 사용할 수 있습니다. 완료 후 다시 시작할지 한 번 더 확인합니다.",
            update.version
        ),
        "다운로드",
    ) {
        return Ok(());
    }
    menu(app, "업데이트 다운로드 중…", false);
    update_status_value(app, "downloading", None, Some(0));
    title(app, "Dougle — 업데이트 다운로드 중…");
    update.timeout = Some(Duration::from_secs(1800));
    let mut previous = 0;
    let mut downloaded = 0u64;
    let bytes = update
        .download(
            |size, total| {
                downloaded = downloaded.saturating_add(size as u64);
                if let Some(total) = total.filter(|total| *total > 0) {
                    let percent = downloaded
                        .saturating_mul(100)
                        .checked_div(total)
                        .unwrap_or(0)
                        .min(100);
                    if percent != previous {
                        previous = percent;
                        update_status_value(app, "downloading", None, Some(percent));
                        title(app, &format!("Dougle — 업데이트 다운로드 {percent}%"));
                    }
                }
            },
            || {},
        )
        .await
        .map_err(|_| ())?;
    title(app, "Dougle");
    if lifecycle.cancelled.load(Ordering::SeqCst) {
        return Ok(());
    }
    if !confirm(app, "다운로드를 마쳤습니다. 작성 중인 내용을 저장했는지 확인해 주세요. 설치 후 앱이 다시 시작됩니다.".into(), "설치 후 다시 시작") {
        return Ok(());
    }
    if !lifecycle.request_update() {
        return Ok(());
    }
    let _install_guard = InstallGuard(lifecycle);
    title(app, "Dougle — 업데이트 설치 준비 중…");
    update_status_value(app, "installing", None, None);
    let deadline = Instant::now() + Duration::from_secs(15);
    while !lifecycle.stopped.load(Ordering::SeqCst) {
        if Instant::now() >= deadline {
            lifecycle.finish_update();
            message(
                app,
                "저장 서비스를 종료하지 못해 설치를 중단했습니다. 앱을 다시 열고 업데이트를 시도해 주세요.",
            );
            app.exit(1);
            return Ok(());
        }
        std::thread::sleep(Duration::from_millis(50));
    }
    let installed = update.install(bytes).is_ok();
    if !installed {
        message(
            app,
            "업데이트 설치를 완료하지 못했습니다. 앱을 다시 열어 시도해 주세요.",
        );
    }
    lifecycle.finish_update();
    app.restart();
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn finds_newest_published_release_with_owned_manifest_not_a_draft_or_external_asset() {
        let release = |tag: &str, draft: bool, url: String| {
            serde_json::json!({
                "tag_name": tag, "draft": draft, "assets": [{ "name": "latest.json", "state": "uploaded", "browser_download_url": url }]
            })
        };
        let url = |tag: &str| {
            format!("https://github.com/thisisnew/dougle/releases/download/{tag}/latest.json")
        };
        let releases = serde_json::json!([
            release("v0.1.18-preview.1", false, url("v0.1.18-preview.1")),
            release("v0.1.20-preview.1", true, url("v0.1.20-preview.1")),
            release(
                "v0.1.19-preview.1",
                false,
                "https://evil.test/latest.json".into()
            ),
            release("v0.1.17-preview.1", false, url("v0.1.17-preview.1"))
        ]);
        assert_eq!(
            release_manifest(&releases).unwrap().as_str(),
            url("v0.1.18-preview.1")
        );
        assert!(release_manifest(&serde_json::json!([])).is_none());
    }

    #[test]
    fn accepts_only_owned_https_packages_for_this_architecture_and_version() {
        let architecture = if cfg!(target_arch = "aarch64") {
            "arm64"
        } else {
            "x86_64"
        };
        let valid = format!(
            "https://github.com/thisisnew/dougle/releases/download/v0.1.4-preview.1/Dougle_0.1.4_{architecture}.app.tar.gz"
        );
        assert!(trusted_package(&valid.parse().unwrap(), "0.1.4"));
        for invalid in [
            valid.replace("https:", "http:"),
            valid.replace("github.com", "evil.test"),
            valid.replace("thisisnew", "other"),
            valid.replace("dougle", "dougle-evil"),
            format!("{valid}?token=untrusted"),
            valid.replace("0.1.4", "0.1.3"),
        ] {
            assert!(!trusted_package(&invalid.parse().unwrap(), "0.1.4"));
        }
    }

    #[test]
    fn shutdown_choice_is_exclusive_and_installation_blocks_early_quit() {
        let lifecycle = Lifecycle::default();
        assert!(lifecycle.request_update());
        assert!(!lifecycle.request_quit());
        assert!(lifecycle.cancelled.load(Ordering::SeqCst));
        assert!(lifecycle.updating());
        lifecycle.finish_update();
        assert!(lifecycle.request_quit());
        let quitting = Lifecycle::default();
        assert!(quitting.request_quit());
        assert!(!quitting.request_update());
    }

    #[test]
    fn abandoned_installation_restores_quit_handling() {
        let lifecycle = Lifecycle::default();
        assert!(lifecycle.request_update());
        {
            let _guard = InstallGuard(&lifecycle);
            assert!(lifecycle.updating());
        }
        assert!(!lifecycle.updating());
        assert!(lifecycle.request_quit());
    }
}
