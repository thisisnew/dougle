use knowledge_forge_desktop::{protocol, supervisor};

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};
use tauri::webview::{DownloadEvent, NewWindowResponse, PageLoadEvent, PermissionResponse};
use tauri::{Manager, RunEvent, WebviewUrl, WebviewWindowBuilder, WindowEvent};

mod updates;

#[derive(Default, PartialEq)]
enum Shutdown {
    #[default]
    Running,
    Quit,
    Update,
}

#[derive(Default)]
struct Lifecycle {
    cancelled: AtomicBool,
    stopped: AtomicBool,
    shutdown: Mutex<Shutdown>,
}

impl Lifecycle {
    fn request_quit(&self) -> bool {
        let mut reason = self
            .shutdown
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        if *reason == Shutdown::Update {
            return false;
        }
        *reason = Shutdown::Quit;
        self.cancelled.store(true, Ordering::SeqCst);
        true
    }

    fn request_update(&self) -> bool {
        let mut reason = self
            .shutdown
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        if *reason != Shutdown::Running {
            return false;
        }
        *reason = Shutdown::Update;
        self.cancelled.store(true, Ordering::SeqCst);
        true
    }

    fn updating(&self) -> bool {
        *self
            .shutdown
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            == Shutdown::Update
    }

    fn finish_update(&self) {
        *self
            .shutdown
            .lock()
            .unwrap_or_else(|error| error.into_inner()) = Shutdown::Quit;
    }
}

#[derive(Default)]
struct FailureMessage(Mutex<Option<&'static str>>);

fn render_failure(status: &tauri::WebviewWindow) {
    let state = status.state::<FailureMessage>();
    if let Ok(message) = state.0.lock()
        && let Some(message) = *message
    {
        let message = serde_json::to_string(message).unwrap();
        let _ = status.eval(format!("document.getElementById('heading').textContent = '앱을 열 수 없습니다'; document.getElementById('message').textContent = {message};"));
    }
}

fn failure(app: &tauri::AppHandle, message: &'static str) {
    if let Ok(mut stored) = app.state::<FailureMessage>().0.lock() {
        *stored = Some(message);
    }
    let handle = app.clone();
    let _ = app.run_on_main_thread(move || {
        if let Some(status) = handle.get_webview_window("status") {
            render_failure(&status);
            let _ = status.show();
            let _ = status.set_focus();
        }
        if let Some(main) = handle.get_webview_window("main") {
            let _ = main.destroy();
        }
    });
}

fn supervise(app: tauri::AppHandle, lifecycle: Arc<Lifecycle>) {
    let result = run_backend(&app, &lifecycle);
    lifecycle.stopped.store(true, Ordering::SeqCst);
    if lifecycle.cancelled.load(Ordering::SeqCst) && !lifecycle.updating() {
        app.exit(0);
    } else if let Err(message) = result {
        failure(&app, message);
    }
}

fn run_backend(app: &tauri::AppHandle, lifecycle: &Arc<Lifecycle>) -> Result<(), &'static str> {
    let resources = app
        .path()
        .resource_dir()
        .map_err(|_| "앱 설치 폴더를 찾을 수 없습니다.")?;
    let data = app
        .path()
        .app_data_dir()
        .map_err(|_| "앱 데이터 폴더를 찾을 수 없습니다.")?;
    let mut backend = supervisor::Backend::launch(&resources, &data, &lifecycle.cancelled)?;
    if lifecycle.cancelled.load(Ordering::SeqCst) {
        return Ok(());
    }
    let port = backend.port;
    let loaded = Arc::new(AtomicBool::new(false));
    let page_loaded = loaded.clone();
    let update_lifecycle = lifecycle.clone();
    let origin = format!("http://127.0.0.1:{port}");
    let downloads = app.path().download_dir().ok();
    let app_window =
        WebviewWindowBuilder::new(app, "main", WebviewUrl::External(origin.parse().unwrap()))
            .title("Knowledge Forge")
            .inner_size(1280.0, 850.0)
            .min_inner_size(900.0, 600.0)
            .visible(false)
            .incognito(true)
            .devtools(false)
            .initialization_script(protocol::initialization_script(port, &backend.token))
            .on_navigation(move |url| protocol::owned_origin(url, port))
            .on_new_window(|_, _| NewWindowResponse::Deny)
            .on_permission_request(|_, _| PermissionResponse::Deny)
            .on_download(move |_, event| match event {
                DownloadEvent::Requested { url, destination } => {
                    protocol::owned_download(&url, port)
                        && downloads
                            .as_deref()
                            .is_some_and(|directory| destination.parent() == Some(directory))
                    && matches!(std::fs::symlink_metadata(destination), Err(error) if error.kind() == std::io::ErrorKind::NotFound)
                }
                DownloadEvent::Finished { .. } => true,
                _ => false,
            })
            .on_page_load(move |window, payload| {
                if payload.event() == PageLoadEvent::Finished
                    && protocol::owned_origin(payload.url(), port)
                {
                    let first_load = !page_loaded.swap(true, Ordering::SeqCst);
                    let _ = window.show();
                    if let Some(status) = window.app_handle().get_webview_window("status") {
                        let _ = status.hide();
                    }
                    if first_load && !cfg!(debug_assertions) {
                        updates::check(window.app_handle().clone(), update_lifecycle.clone(), false);
                    }
                }
            })
            .build()
            .map_err(|_| "앱 화면을 만들 수 없습니다. 앱을 종료한 뒤 다시 실행해 주세요.")?;
    let load_deadline = Instant::now() + Duration::from_secs(20);
    let mut next_health = Instant::now() + Duration::from_secs(2);
    while !lifecycle.cancelled.load(Ordering::SeqCst) {
        backend.check_process()?;
        if !loaded.load(Ordering::SeqCst) && Instant::now() >= load_deadline {
            return Err("앱 화면을 불러오지 못했습니다. 앱을 종료한 뒤 다시 실행해 주세요.");
        }
        if Instant::now() >= next_health {
            if !backend.authenticated_ready() {
                let _ = app_window.hide();
                return Err(
                    "로컬 서비스와의 연결이 끊어졌습니다. 앱을 종료한 뒤 다시 실행해 주세요. 작업은 자동 재전송되지 않습니다.",
                );
            }
            next_health = Instant::now() + Duration::from_secs(2);
        }
        thread::sleep(supervisor::POLL_INTERVAL);
    }
    Ok(())
}

fn main() {
    std::panic::set_hook(Box::new(|_| {
        eprintln!("Knowledge Forge encountered an internal error.")
    }));
    let lifecycle = Arc::new(Lifecycle::default());
    let setup_lifecycle = lifecycle.clone();
    let menu_lifecycle = lifecycle.clone();
    let application = tauri::Builder::default()
        .plugin(tauri_plugin_updater::Builder::new().build())
        .plugin(tauri_plugin_dialog::init())
        .manage(updates::UpdateState::default())
        .manage(FailureMessage::default())
        .setup(move |app| {
            let menu = tauri::menu::Menu::default(app.handle())?;
            let check = tauri::menu::MenuItem::with_id(
                app,
                updates::MENU_ID,
                "업데이트 확인…",
                true,
                None::<&str>,
            )?;
            if let Some(tauri::menu::MenuItemKind::Submenu(application_menu)) =
                menu.items()?.first()
            {
                application_menu.insert(&check, 1)?;
            }
            app.set_menu(menu)?;
            WebviewWindowBuilder::new(app, "status", WebviewUrl::App("index.html".into()))
                .title("Knowledge Forge")
                .inner_size(700.0, 460.0)
                .devtools(false)
                .on_navigation(|url| url.scheme() == "tauri" && url.host_str() == Some("localhost"))
                .on_new_window(|_, _| NewWindowResponse::Deny)
                .on_permission_request(|_, _| PermissionResponse::Deny)
                .on_page_load(|window, payload| {
                    if payload.event() == PageLoadEvent::Finished {
                        render_failure(&window);
                    }
                })
                .build()?;
            let handle = app.handle().clone();
            thread::spawn(move || {
                let outcome = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                    supervise(handle.clone(), setup_lifecycle.clone())
                }));
                if outcome.is_err() {
                    setup_lifecycle.stopped.store(true, Ordering::SeqCst);
                    if setup_lifecycle.cancelled.load(Ordering::SeqCst)
                        && !setup_lifecycle.updating()
                    {
                        handle.exit(1);
                    } else {
                        failure(
                            &handle,
                            "예기치 않은 오류가 발생했습니다. 앱을 종료한 뒤 다시 실행해 주세요.",
                        );
                    }
                }
            });
            Ok(())
        })
        .on_menu_event(move |app, event| {
            if event.id().as_ref() == updates::MENU_ID {
                updates::check(app.clone(), menu_lifecycle.clone(), true);
            }
        })
        .on_window_event(|window, event| {
            if matches!(event, WindowEvent::CloseRequested { .. }) {
                window.app_handle().exit(0);
            }
        })
        .build(tauri::generate_context!())
        .expect("Knowledge Forge could not initialize its native window");
    application.run(move |_, event| {
        if let RunEvent::ExitRequested { api, .. } = event {
            if lifecycle.updating() {
                api.prevent_exit();
            } else if !lifecycle.stopped.load(Ordering::SeqCst) {
                api.prevent_exit();
                lifecycle.request_quit();
            }
        }
    });
}
