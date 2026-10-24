use crate::Lifecycle;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};
use tauri::Manager;
use tauri_plugin_dialog::{DialogExt, MessageDialogButtons};
use tauri_plugin_updater::UpdaterExt;

pub const MENU_ID: &str = "forge-check-update";

#[derive(Default)]
pub struct UpdateState {
    busy: AtomicBool,
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
    tauri::async_runtime::spawn(async move {
        let _reset = Reset(app.clone());
        let outcome = apply(&app, &lifecycle, manual).await;
        if outcome.is_err() && !lifecycle.cancelled.load(Ordering::SeqCst) {
            message(
                &app,
                "업데이트를 완료하지 못했습니다. 인터넷 연결을 확인한 뒤 메뉴에서 다시 시도해 주세요.",
            );
        }
    });
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
        && url
            .path()
            .starts_with("/thisisnew/knowledge-forge/releases/download/")
        && url.path().ends_with(&format!(
            "/Knowledge.Forge_{version}_{architecture}.app.tar.gz"
        ))
}

async fn apply(app: &tauri::AppHandle, lifecycle: &Lifecycle, manual: bool) -> Result<(), ()> {
    let updater = app
        .updater_builder()
        .timeout(Duration::from_secs(15))
        .build()
        .map_err(|_| ())?;
    let checked = updater.check().await;
    let mut update = match checked {
        Ok(Some(update)) => update,
        Ok(None) => {
            if manual {
                message(app, "최신 버전을 사용 중입니다.");
            }
            return Ok(());
        }
        Err(_) if !manual => return Ok(()),
        Err(_) => return Err(()),
    };
    if lifecycle.cancelled.load(Ordering::SeqCst) {
        return Ok(());
    }
    if !trusted_package(&update.download_url, &update.version) {
        return Err(());
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
                        title(
                            app,
                            &format!("Dougle — 업데이트 다운로드 {percent}%"),
                        );
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
    fn accepts_only_owned_https_packages_for_this_architecture_and_version() {
        let architecture = if cfg!(target_arch = "aarch64") {
            "arm64"
        } else {
            "x86_64"
        };
        let valid = format!(
            "https://github.com/thisisnew/knowledge-forge/releases/download/v0.1.4-preview.1/Knowledge.Forge_0.1.4_{architecture}.app.tar.gz"
        );
        assert!(trusted_package(&valid.parse().unwrap(), "0.1.4"));
        for invalid in [
            valid.replace("https:", "http:"),
            valid.replace("github.com", "evil.test"),
            valid.replace("thisisnew", "other"),
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
