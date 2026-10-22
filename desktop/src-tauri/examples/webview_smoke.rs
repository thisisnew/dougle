use knowledge_forge_desktop::protocol::{initialization_script, owned_download, owned_origin};
use std::io::{BufRead, BufReader, Write};
use std::net::TcpListener;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread;
use std::time::{Duration, Instant};
use tauri::webview::{DownloadEvent, NewWindowResponse};
use tauri::{WebviewUrl, WebviewWindowBuilder};

const TOKEN: &str = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
const CONTENT: &str = "Knowledge Forge synthetic download";
const SCRIPT: &str = r#"
(async () => {
  const descriptor = Object.getOwnPropertyDescriptor(window, '__FORGE_DESKTOP_SESSION__');
  const valid = descriptor && !descriptor.writable && !descriptor.configurable && !descriptor.enumerable && descriptor.value === 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';
  const response = await fetch(valid ? '/passed' : '/failed', { headers: { 'X-Forge-Session': window.__FORGE_DESKTOP_SESSION__ || '' } });
  if (!response.ok) return;
  window.open('https://example.invalid/blocked');
  const blob = URL.createObjectURL(new Blob(['Knowledge Forge synthetic download'], { type: 'text/plain' }));
  const anchor = document.createElement('a');
  anchor.href = blob;
  anchor.download = 'knowledge-forge-synthetic.txt';
  document.body.append(anchor);
  anchor.click();
  setTimeout(() => { URL.revokeObjectURL(blob); location.href = 'http://localhost:' + location.port + '/blocked'; }, 1000);
})();
"#;

fn main() {
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let port = listener.local_addr().unwrap().port();
    let authenticated = Arc::new(AtomicBool::new(false));
    let downloaded = Arc::new(AtomicBool::new(false));
    let navigation_denied = Arc::new(AtomicBool::new(false));
    let popup_denied = Arc::new(AtomicBool::new(false));
    let server_authenticated = authenticated.clone();
    thread::spawn(move || {
        for incoming in listener.incoming() {
            let Ok(mut stream) = incoming else { break };
            stream
                .set_read_timeout(Some(Duration::from_secs(2)))
                .unwrap();
            let mut reader = BufReader::new(&stream);
            let mut request = String::new();
            loop {
                let mut line = String::new();
                if reader.read_line(&mut line).unwrap_or(0) == 0 || line == "\r\n" {
                    break;
                }
                request.push_str(&line);
                if request.len() > 8192 {
                    break;
                }
            }
            let path = request.split_whitespace().nth(1).unwrap_or("");
            let (status, content_type, body) = match path {
                "/" => (
                    "200 OK",
                    "text/html",
                    "<!doctype html><html><body><div id='root'>Synthetic WebKit smoke</div><script src='/probe.js'></script></body></html>",
                ),
                "/probe.js" => ("200 OK", "text/javascript", SCRIPT),
                "/passed"
                    if request
                        .to_ascii_lowercase()
                        .contains(&format!("x-forge-session: {TOKEN}\r\n")) =>
                {
                    server_authenticated.store(true, Ordering::SeqCst);
                    ("200 OK", "application/json", "{}")
                }
                _ => ("403 Forbidden", "text/plain", "Denied"),
            };
            let response = format!(
                "HTTP/1.1 {status}\r\nContent-Type: {content_type}\r\nContent-Length: {}\r\nContent-Security-Policy: default-src 'self'; script-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'\r\nConnection: close\r\n\r\n{body}",
                body.len()
            );
            let _ = stream.write_all(response.as_bytes());
        }
    });
    let destination = std::env::temp_dir().join(format!(
        "knowledge-forge-webview-smoke-{}.txt",
        std::process::id()
    ));
    let download_destination = destination.clone();
    let application = tauri::Builder::default()
        .setup(move |app| {
            let downloaded_event = downloaded.clone();
            let denied_navigation = navigation_denied.clone();
            let denied_popup = popup_denied.clone();
            WebviewWindowBuilder::new(app, "smoke", WebviewUrl::External(format!("http://127.0.0.1:{port}/").parse().unwrap()))
                .title("Knowledge Forge — synthetic WebKit verification")
                .visible(false)
                .incognito(true)
                .initialization_script(initialization_script(port, TOKEN))
                .on_navigation(move |url| {
                    let allowed = owned_origin(url, port);
                    if !allowed { denied_navigation.store(true, Ordering::SeqCst); }
                    allowed
                })
                .on_new_window(move |_, _| {
                    denied_popup.store(true, Ordering::SeqCst);
                    NewWindowResponse::Deny
                })
                .on_download(move |_, event| match event {
                    DownloadEvent::Requested { url, destination } => {
                        if !owned_download(&url, port) { return false; }
                        *destination = download_destination.clone();
                        true
                    }
                    DownloadEvent::Finished { success, .. } => {
                        downloaded_event.store(success, Ordering::SeqCst);
                        true
                    }
                    _ => false,
                })
                .build()?;
            let handle = app.handle().clone();
            thread::spawn(move || {
                let deadline = Instant::now() + Duration::from_secs(20);
                while Instant::now() < deadline {
                    if authenticated.load(Ordering::SeqCst) && downloaded.load(Ordering::SeqCst)
                        && navigation_denied.load(Ordering::SeqCst) && popup_denied.load(Ordering::SeqCst)
                        && std::fs::read_to_string(&destination).is_ok_and(|body| body == CONTENT)
                    {
                        let _ = std::fs::remove_file(&destination);
                        println!("PASS: document-start token under CSP, authenticated fetch, owned blob download, blocked navigation and popup");
                        handle.exit(0);
                        return;
                    }
                    thread::sleep(Duration::from_millis(100));
                }
                let _ = std::fs::remove_file(&destination);
                eprintln!("FAIL: WebKit verification timed out (auth={}, download={}, navigation={}, popup={})", authenticated.load(Ordering::SeqCst), downloaded.load(Ordering::SeqCst), navigation_denied.load(Ordering::SeqCst), popup_denied.load(Ordering::SeqCst));
                handle.exit(1);
            });
            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("synthetic WebKit test window");
    application.run(|_, _| {});
}
