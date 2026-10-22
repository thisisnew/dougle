use knowledge_forge_desktop::{protocol, supervisor};
use std::path::PathBuf;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use tauri::{WebviewUrl, WebviewWindowBuilder};

const SCRIPT: &str = r#"
window.addEventListener('DOMContentLoaded', () => {
  const started = Date.now();
  const timer = setInterval(async () => {
    if (!document.querySelector('#root button[aria-label="Workspace 생성"]')) {
      if (Date.now() - started > 20000) clearInterval(timer);
      return;
    }
    clearInterval(timer);
    const headers = { 'X-Forge-Session': window.__FORGE_DESKTOP_SESSION__, 'X-Forge-Client': '1' };
    async function api(path, method = 'GET', body) {
      const response = await fetch('/api' + path, {
        method, headers: { ...headers, ...(body && !(body instanceof FormData) ? { 'Content-Type': 'application/json' } : {}) },
        body: body instanceof FormData ? body : body ? JSON.stringify(body) : undefined,
      });
      if (!response.ok) throw Error('API ' + response.status);
      return response.json();
    }
    try {
      if ((await fetch('/api/session')).status !== 403) throw Error('Missing auth protection');
      const workspace = await api('/workspaces', 'POST', { name: 'Synthetic native runtime QA' });
      const base = '/workspaces/' + workspace.id;
      const form = new FormData();
      form.append('file', new Blob(['Synthetic telescope observations.']), 'telescope.txt');
      const file = await api(base + '/files', 'POST', form);
      const preview = await api(base + '/entries/' + file.id + '/preview');
      if (preview.text !== 'Synthetic telescope observations.') throw Error('Preview mismatch');
      const downloaded = await fetch('/api' + base + '/entries/' + file.id + '/content', { headers });
      if (await downloaded.text() !== preview.text) throw Error('Download mismatch');
      const hits = await api(base + '/search?query=telescope');
      if (!hits.some(hit => hit.entryId === file.id)) throw Error('Search mismatch');
      const draft = await api(base + '/knowledge', 'POST', {
        title: 'Synthetic native publication', markdown: 'Synthetic telescope observations. [E1]',
        citations: [{ entryId: file.id, startLine: 1, endLine: 1 }],
      });
      const card = base + '/knowledge/' + draft.card.id;
      await api(card + '/review', 'POST', {
        expectedVersion: 1, reviewer: 'Synthetic native reviewer', note: 'checked fixture',
        claimsSupported: true, uncertaintyRemoved: true,
      });
      await api(card + '/publication', 'POST', { expectedVersion: 1, expectedPublicationRevision: 0 });
      await api(card + '/versions', 'POST', {
        expectedVersion: 1, title: 'Synthetic human edit', markdown: 'Human edit retained independently. [E1]',
      });
      if ((await api(card + '/versions/1')).version.markdown !== draft.version.markdown) throw Error('Old knowledge version overwritten');
      if ((await api(card + '/versions/2')).version.title !== 'Synthetic human edit') throw Error('Human edit missing');
      const relations = await api(base + '/relations?kind=ORIGINAL&id=' + file.id);
      if (!relations.items.some(item => item.target.id === draft.card.id && item.target.version === 1)) throw Error('Published relation mismatch');
      const source = await api(base + '/sources', 'POST', {
        name: 'Synthetic disabled URL', kind: 'URL', location: 'https://example.org/synthetic', domain: '',
      });
      const polling = await api(base + '/sources/' + source.id + '/polling', 'PATCH', {
        enabled: false, intervalSeconds: 60, expectedRevision: 0,
      });
      if (polling.pollingEnabled || polling.nextPollAt !== null || polling.status !== 'NEW') throw Error('OFF collected unexpectedly');
      const settings = await api('/ai/launch-settings');
      const saved = await api('/ai/launch-settings', 'PUT', {
        codexPath: '', claudePath: '', expectedRevision: settings.saved.revision, acknowledged: true,
      });
      if (saved.restartRequired || saved.saved.revision !== settings.saved.revision + 1) throw Error('CLI settings mismatch');
      await api('/workspaces', 'POST', { name: 'NATIVE_RUNTIME_QA_PASSED' });
    } catch {
      await api('/workspaces', 'POST', { name: 'NATIVE_RUNTIME_QA_FAILED' }).catch(() => {});
    }
  }, 100);
}, { once: true });
"#;

fn main() {
    let resources = PathBuf::from(
        std::env::args()
            .nth(1)
            .expect("Pass the bundle Contents/Resources directory"),
    );
    assert!(resources.is_absolute());
    let stamp = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_nanos();
    let data = std::env::temp_dir().join(format!(
        "forge-native-runtime-{}-{stamp}",
        std::process::id()
    ));
    let finished = Arc::new(AtomicBool::new(false));
    let setup_finished = finished.clone();
    let application = tauri::Builder::default()
        .setup(move |app| {
            let handle = app.handle().clone();
            thread::spawn(move || {
                let passed = run(&handle, &resources, &data);
                let _ = std::fs::remove_dir_all(&data);
                if passed {
                    println!("PASS: bundled Java, authenticated React, upload/download/search, knowledge edit/publication, relations, OFF polling and CLI settings; no AI");
                } else {
                    eprintln!("FAIL: native runtime smoke");
                }
                setup_finished.store(true, Ordering::SeqCst);
                handle.exit(if passed { 0 } else { 1 });
            });
            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("Initialize synthetic runtime test");
    application.run(move |_, event| {
        if let tauri::RunEvent::ExitRequested { api, .. } = event
            && !finished.load(Ordering::SeqCst)
        {
            api.prevent_exit();
        }
    });
}

fn run(app: &tauri::AppHandle, resources: &std::path::Path, data: &std::path::Path) -> bool {
    let cancelled = AtomicBool::new(false);
    let Ok(mut backend) = supervisor::Backend::launch(resources, data, &cancelled) else {
        return false;
    };
    let port = backend.port;
    let origin = format!("http://127.0.0.1:{port}");
    let window = WebviewWindowBuilder::new(
        app,
        "runtime-smoke",
        WebviewUrl::External(origin.parse().unwrap()),
    )
    .title("Knowledge Forge · synthetic runtime QA")
    .incognito(true)
    .devtools(false)
    .initialization_script(protocol::initialization_script(port, &backend.token))
    .initialization_script(SCRIPT)
    .on_navigation(move |url| protocol::owned_origin(url, port))
    .on_new_window(|_, _| tauri::webview::NewWindowResponse::Deny)
    .build();
    let Ok(_window) = window else { return false };
    let agent: ureq::Agent = ureq::Agent::config_builder()
        .proxy(None)
        .max_redirects(0)
        .timeout_global(Some(Duration::from_secs(2)))
        .build()
        .into();
    let deadline = Instant::now() + Duration::from_secs(45);
    let mut passed = false;
    while Instant::now() < deadline && backend.check_process().is_ok() {
        if let Ok(mut response) = agent
            .get(format!("{origin}/api/session"))
            .header("X-Forge-Session", &backend.token)
            .call()
            && let Ok(session) = response
                .body_mut()
                .with_config()
                .limit(65536)
                .read_json::<serde_json::Value>()
            && let Some(workspaces) = session["workspaces"].as_array()
        {
            passed = workspaces
                .iter()
                .any(|workspace| workspace["name"] == "NATIVE_RUNTIME_QA_PASSED");
            if passed
                || workspaces
                    .iter()
                    .any(|workspace| workspace["name"] == "NATIVE_RUNTIME_QA_FAILED")
            {
                break;
            }
        }
        thread::sleep(Duration::from_millis(200));
    }
    drop(backend);
    passed
}
