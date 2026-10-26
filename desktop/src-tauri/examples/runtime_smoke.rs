use knowledge_forge_desktop::{protocol, supervisor};
use std::path::PathBuf;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use tauri::{WebviewUrl, WebviewWindowBuilder};

const SCRIPT: &str = r#"
window.__FORGE_QA_PHASE__ = 'initialization';
window.addEventListener('DOMContentLoaded', () => {
  window.__FORGE_QA_PHASE__ = 'waiting for React';
  const started = Date.now();
  const timer = setInterval(async () => {
    const workspaceButton = document.querySelector('#root button[aria-label="Workspace 생성"]');
    if (!workspaceButton || workspaceButton.disabled || workspaceButton.getBoundingClientRect().height === 0) {
      if (Date.now() - started > 20000) clearInterval(timer);
      return;
    }
    clearInterval(timer);
    const headers = { 'X-Forge-Session': window.__FORGE_DESKTOP_SESSION__, 'X-Forge-Client': '1' };
    async function api(path, method = 'GET', body) {
      phase = method + ' ' + path;
      const response = await fetch('/api' + path, {
        mode: 'same-origin', redirect: 'error',
        method, headers: { ...headers, ...(body && !(body instanceof FormData) ? { 'Content-Type': 'application/json' } : {}) },
        body: body instanceof FormData ? body : body ? JSON.stringify(body) : undefined,
      });
      if (!response.ok) throw Error('API ' + response.status);
      return response.json();
    }
    async function until(ready, timeout = 10000) {
      const deadline = Date.now() + timeout;
      while (Date.now() < deadline) {
        if (await ready()) return;
        await new Promise(done => setTimeout(done, 50));
      }
      throw Error('UI timeout');
    }
    let phase = 'workspace button';
    Object.defineProperty(window, '__FORGE_QA_PHASE__', { get: () => phase, configurable: true });
    try {
      workspaceButton.click();
      phase = 'workspace dialog';
      await until(() => document.querySelector('#item-name'));
      const input = document.querySelector('#item-name');
      if (input.maxLength !== 20) throw Error('Workspace name limit mismatch');
      Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(input, '가상 데스크톱 검증');
      input.dispatchEvent(new Event('input', { bubbles: true }));
      phase = 'workspace input';
      await until(() => !document.querySelector('[role="dialog"] button[type="submit"]').disabled);
      document.querySelector('[role="dialog"] button[type="submit"]').click();
      phase = 'workspace saved';
      const picker = () => document.querySelector('button[role="combobox"][aria-label="Workspace 선택"]');
      await until(() => picker()?.textContent.trim() === '가상 데스크톱 검증' && !picker().disabled);
      picker().click();
      phase = 'workspace dropdown';
      await until(() => picker().getAttribute('aria-expanded') === 'true'
        && document.querySelector('[role="listbox"][aria-label="Workspace 목록"]'));
      const selected = document.querySelectorAll('[role="listbox"][aria-label="Workspace 목록"] [role="option"][aria-selected="true"]');
      if (selected.length !== 1 || selected[0].textContent.trim() !== '가상 데스크톱 검증'
        || selected[0].getBoundingClientRect().height === 0) throw Error('Workspace dropdown selection mismatch');
      picker().dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
      await until(() => picker().getAttribute('aria-expanded') === 'false'
        && !document.querySelector('[role="listbox"][aria-label="Workspace 목록"]'));
      const session = await api('/session');
      const workspace = session.workspaces.find(item => item.name === '가상 데스크톱 검증');
      if (!workspace || session.activeWorkspaceId !== workspace.id) throw Error('Workspace UI mismatch');
      phase = 'Korean forbidden response';
      const denied = await fetch('/api/session', { mode: 'same-origin' });
      if (denied.status !== 403 || (await denied.json()).detail !== '허용되지 않은 로컬 요청입니다.') throw Error('Missing UTF-8 auth protection');
      phase = 'AI connection button';
      if (document.querySelector('.search-form')) throw Error('Search form should not be in the material tab');
      [...document.querySelectorAll('#root button')].find(button => button.textContent.trim() === '대화').click();
      phase = 'chat view';
      await until(() => document.querySelector('#chat-question'));
      if (!document.querySelector('#chat-question').disabled) throw Error('Unauthenticated chat must be disabled');
      phase = 'provider readiness';
      await until(() => !document.querySelector('.provider-trigger').disabled);
      localStorage.setItem('forge.ai.provider', 'CLAUDE');
      document.querySelector('.provider-trigger').click();
      phase = 'connection dialog';
      await until(() => {
        const opened = document.querySelector('dialog[aria-label="AI 연결"][open] select');
        return opened && opened.getBoundingClientRect().height > 0;
      });
      const dialog = document.querySelector('dialog[aria-label="AI 연결"]');
      const model = dialog.querySelector('select');
      const claude = [...dialog.querySelectorAll('.provider-card')].find(button => button.textContent.includes('Claude'));
      if (claude.getAttribute('aria-pressed') !== 'true') throw Error('Provider preference mismatch');
      const appearance = getComputedStyle(model).getPropertyValue('appearance') || getComputedStyle(model).getPropertyValue('-webkit-appearance');
      if (model.getBoundingClientRect().height < 40 || appearance !== 'none') throw Error('Native select appearance mismatch');
      const connect = () => [...dialog.querySelectorAll('button')].find(button => button.textContent.trim() === '연결');
      phase = 'Claude unavailable in isolated PATH';
      connect().click();
      await until(() => dialog.textContent.includes('실행 도구를 찾지 못했습니다.') || dialog.querySelector('[role="alert"]'));
      if (dialog.querySelector('[role="alert"]')) throw Error('AI connection UI mismatch');
      [...dialog.querySelectorAll('.provider-card')].find(button => button.textContent.includes('Codex')).click();
      phase = 'Codex selected without redundant status';
      await until(() => [...dialog.querySelectorAll('.provider-card')].some(button => button.textContent.includes('Codex') && button.getAttribute('aria-pressed') === 'true') && !dialog.querySelector('.connection-status') && connect() && !connect().disabled);
      phase = 'Codex private profile readiness';
      connect().click();
      await until(() => dialog.textContent.includes('로그인이 필요합니다.') || dialog.querySelector('[role="alert"]'));
      if (dialog.querySelector('[role="alert"]') || !dialog.textContent.includes('로그인이 필요합니다.')) throw Error('Private profile login UI mismatch');
      const loginButton = [...dialog.querySelectorAll('button')].find(button => button.textContent.trim() === '브라우저에서 로그인');
      if (!loginButton?.disabled) throw Error('Login must require explicit consent');
      const checkBox = dialog.querySelector('input[type=checkbox]').getBoundingClientRect();
      const checkText = dialog.querySelector('.check-option span').getBoundingClientRect();
      if (checkBox.width < 16 || checkText.left - checkBox.right < 8) throw Error('Checkbox label spacing mismatch');
      [...dialog.querySelectorAll('button')].find(button => button.textContent.trim() === '닫기').click();
      phase = 'bundled local search model readiness';
      await until(async () => {
        const model = await api('/search-model');
        if (!['STARTING', 'LOADING', 'INDEXING', 'READY'].includes(model.state)) throw Error('Bundled search model unavailable: ' + model.state);
        return model.state === 'READY';
      }, 120000);
      phase = 'library operations';
      const base = '/workspaces/' + workspace.id;
      const form = new FormData();
      form.append('file', new Blob(['Synthetic telescope observations.']), 'telescope.txt');
      const file = await api(base + '/files', 'POST', form);
      const preview = await api(base + '/entries/' + file.id + '/preview');
      if (preview.text !== 'Synthetic telescope observations.') throw Error('Preview mismatch');
      const downloaded = await fetch('/api' + base + '/entries/' + file.id + '/content', { headers });
      if (await downloaded.text() !== preview.text) throw Error('Download mismatch');
      await until(async () => {
        const hits = await api(base + '/search?query=telescope');
        return hits.some(hit => hit.entryId === file.id && hit.snippet.includes('Synthetic telescope observations.'));
      }, 30000);
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
      phase = 'Source metadata';
      const editedSource = await api(base + '/sources/' + source.id, 'PATCH', {
        name: '가상 자료 이름 수정', domain: '가상 분류', expectedRevision: polling.pollingRevision,
      });
      if (editedSource.name !== '가상 자료 이름 수정' || editedSource.domain !== '가상 분류'
        || editedSource.location !== source.location || editedSource.pollingEnabled
        || editedSource.nextPollAt !== null || editedSource.status !== 'NEW'
        || editedSource.pollingRevision !== polling.pollingRevision + 1) throw Error('Source metadata mismatch');
      const settings = await api('/ai/launch-settings');
      const saved = await api('/ai/launch-settings', 'PUT', {
        codexPath: '', claudePath: '', expectedRevision: settings.saved.revision, acknowledged: true,
      });
      if (!saved.restartRequired || saved.saved.revision !== settings.saved.revision + 1) throw Error('CLI settings mismatch');
      await api('/workspaces', 'POST', { name: 'NATIVE_QA_PASSED' });
    } catch (error) {
      const opened = document.querySelector('dialog[aria-label="AI 연결"]');
      const select = opened?.querySelector('select');
      const diagnostic = `dialog=${!!opened},open=${!!opened?.open},height=${select?.getBoundingClientRect().height ?? -1},loading=${document.body.innerText.includes('AI 연결을 여는 중')}`;
      window.__FORGE_QA_FAILURE__ = phase + ' ' + diagnostic + ' ' + String(error);
      location.hash = 'forge-qa-' + encodeURIComponent(window.__FORGE_QA_FAILURE__);
      await fetch('/api/workspaces', { method: 'POST', headers: { ...headers, 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: 'NATIVE_QA_FAILED' }) }).catch(() => {});
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
                if passed {
                    let _ = std::fs::remove_dir_all(&data);
                    println!("PASS: actual React Workspace/AI buttons, Korean names/errors, same-origin mutations, upload/download/search, publication, relations, OFF polling, Source metadata and CLI settings; no inference");
                } else {
                    eprintln!("Retained synthetic diagnostics: {}", data.display());
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
    let mut backend = match supervisor::Backend::launch(resources, data, &cancelled) {
        Ok(backend) => backend,
        Err(error) => {
            eprintln!("FAIL: backend startup: {error}");
            return false;
        }
    };
    let missing_claude = data.join("unavailable-synthetic-claude");
    let isolated = (|| -> Result<(), Box<dyn std::error::Error>> {
        use std::os::unix::fs::PermissionsExt;
        std::fs::write(&missing_claude, "#!/bin/sh\nexit 1\n")?;
        std::fs::set_permissions(&missing_claude, std::fs::Permissions::from_mode(0o700))?;
        let agent: ureq::Agent = ureq::Agent::config_builder()
            .proxy(None)
            .max_redirects(0)
            .timeout_global(Some(Duration::from_secs(5)))
            .build()
            .into();
        agent.put(format!("http://127.0.0.1:{}/api/ai/launch-settings", backend.port))
            .header("X-Forge-Session", &backend.token)
            .header("X-Forge-Client", "1")
            .send_json(serde_json::json!({
                "codexPath": "", "claudePath": missing_claude.to_str().unwrap(),
                "expectedRevision": 0, "acknowledged": true
            }))?;
        std::fs::remove_file(&missing_claude)?;
        Ok(())
    })();
    if isolated.is_err() {
        eprintln!("FAIL: isolate synthetic Claude command");
        return false;
    }
    drop(backend);
    backend = match supervisor::Backend::launch(resources, data, &cancelled) {
        Ok(backend) => backend,
        Err(error) => {
            eprintln!("FAIL: isolated backend restart: {error}");
            return false;
        }
    };
    let port = backend.port;
    let origin = format!("http://127.0.0.1:{port}");
    let window = WebviewWindowBuilder::new(
        app,
        "runtime-smoke",
        WebviewUrl::External(origin.parse().unwrap()),
    )
    .title("Knowledge Forge · synthetic runtime QA")
    .inner_size(1280.0, 850.0)
    .incognito(true)
    .devtools(false)
    .initialization_script(protocol::initialization_script(port, &backend.token))
    .initialization_script(SCRIPT)
    .on_navigation(move |url| protocol::owned_origin(url, port))
    .on_new_window(|_, _| tauri::webview::NewWindowResponse::Deny)
    .build();
    let _window = match window {
        Ok(window) => window,
        Err(error) => {
            eprintln!("FAIL: test window: {error}");
            return false;
        }
    };
    if let Err(error) = _window.set_focus() {
        eprintln!("Test window focus request failed: {error}");
    }
    let agent: ureq::Agent = ureq::Agent::config_builder()
        .proxy(None)
        .max_redirects(0)
        .timeout_global(Some(Duration::from_secs(2)))
        .build()
        .into();
    let deadline = Instant::now() + Duration::from_secs(180);
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
                .any(|workspace| workspace["name"] == "NATIVE_QA_PASSED");
            if passed
                || workspaces.iter().any(|workspace| {
                    workspace["name"]
                        .as_str()
                        .is_some_and(|name| name == "NATIVE_QA_FAILED")
                })
            {
                if !passed {
                    for workspace in workspaces {
                        if let Some(name) = workspace["name"]
                            .as_str()
                            .filter(|name| *name == "NATIVE_QA_FAILED")
                        {
                            eprintln!("{name}");
                        }
                    }
                }
                break;
            }
        }
        thread::sleep(Duration::from_millis(200));
    }
    if !passed {
        eprintln!("FAIL: runtime completion marker not received");
        eprintln!(
            "Window visible={:?}, focused={:?}, url={:?}",
            _window.is_visible(),
            _window.is_focused(),
            _window.url()
        );
        match agent
            .get(format!("{origin}/api/session"))
            .header("X-Forge-Session", &backend.token)
            .call()
        {
            Ok(mut response) => eprintln!(
                "Session diagnostic: {:?}",
                response.body_mut().read_json::<serde_json::Value>()
            ),
            Err(error) => eprintln!("Session diagnostic: {error}"),
        }
        let _ = _window.eval("location.hash = 'forge-qa-' + encodeURIComponent(String(window.__FORGE_QA_FAILURE__ || window.__FORGE_QA_PHASE__ || 'script missing')); ");
        let diagnostic = r#"fetch('/api/workspaces', {
          method: 'POST', headers: { 'X-Forge-Session': SESSION_VALUE, 'X-Forge-Client': '1', 'Content-Type': 'application/json' },
          body: JSON.stringify({ name: 'NATIVE_QA_FAILED' })
        }).catch(() => {});"#
            .replace("SESSION_VALUE", &serde_json::to_string(&backend.token).unwrap());
        let _ = _window.eval(diagnostic);
        for _ in 0..10 {
            thread::sleep(Duration::from_millis(200));
            if let Ok(mut response) = agent
                .get(format!("{origin}/api/session"))
                .header("X-Forge-Session", &backend.token)
                .call()
                && let Ok(session) = response.body_mut().read_json::<serde_json::Value>()
                && let Some(workspaces) = session["workspaces"].as_array()
                && let Some(name) = workspaces.iter().find_map(|workspace| {
                    workspace["name"]
                        .as_str()
                        .filter(|name| *name == "NATIVE_QA_FAILED")
                })
            {
                eprintln!("{name}");
                break;
            }
        }
        eprintln!("Diagnostic URL: {:?}", _window.url());
    }
    drop(backend);
    passed
}
