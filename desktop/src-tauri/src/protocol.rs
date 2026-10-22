use std::io::{self, Read};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, mpsc::SyncSender};
use tauri::Url;

pub const OUTPUT_LIMIT: usize = 8 * 1024 * 1024;
pub const LINE_LIMIT: usize = 4096;

pub fn ready_port(line: &[u8]) -> Option<u16> {
    let line = line.strip_suffix(b"\r").unwrap_or(line);
    let digits = line.strip_prefix(b"FORGE_DESKTOP_READY:")?;
    if digits.is_empty() || digits.len() > 5 || !digits.iter().all(u8::is_ascii_digit) {
        return None;
    }
    let port = std::str::from_utf8(digits).ok()?.parse().ok()?;
    (port != 0).then_some(port)
}

pub fn owned_origin(url: &Url, port: u16) -> bool {
    url.scheme() == "http"
        && url.host_str() == Some("127.0.0.1")
        && url.port_or_known_default() == Some(port)
        && url.username().is_empty()
        && url.password().is_none()
}

pub fn initialization_script(port: u16, token: &str) -> String {
    let origin = format!("http://127.0.0.1:{port}");
    let origin = serde_json::to_string(&origin).unwrap();
    let token = serde_json::to_string(token).unwrap();
    format!(
        "(() => {{ if (window === window.top && window.location.protocol === 'http:' && window.location.origin === {origin}) {{ Object.defineProperty(window, '__FORGE_DESKTOP_SESSION__', {{ value: {token}, writable: false, configurable: false, enumerable: false }}); window.addEventListener('DOMContentLoaded', () => {{ setTimeout(() => {{ const root = document.getElementById('root'); if (!root || !root.hasChildNodes()) {{ document.body.textContent = '앱 화면을 불러오지 못했습니다. 앱을 종료한 뒤 다시 실행해 주세요. 작업은 자동 재전송되지 않습니다.'; }} }}, 15000); }}, {{ once: true }}); }} }})();"
    )
}

pub fn session_token() -> Result<String, &'static str> {
    let mut bytes = [0u8; 32];
    getrandom::fill(&mut bytes)
        .map_err(|_| "보안 세션을 만들 수 없습니다. 앱을 다시 실행해 주세요.")?;
    Ok(bytes.iter().map(|byte| format!("{byte:02x}")).collect())
}

pub fn owned_download(url: &Url, port: u16) -> bool {
    url.scheme() == "blob"
        && url.origin().ascii_serialization() == format!("http://127.0.0.1:{port}")
}

pub fn drain_output(
    mut reader: impl Read,
    ready: Option<SyncSender<u16>>,
    total: Arc<AtomicUsize>,
    failed: Arc<AtomicBool>,
    startup: Arc<AtomicBool>,
) {
    let mut buffer = [0u8; 4096];
    let mut line = Vec::with_capacity(LINE_LIMIT);
    let mut overflow = false;
    loop {
        let count = match reader.read(&mut buffer) {
            Ok(0) => return,
            Ok(count) => count,
            Err(error) if error.kind() == io::ErrorKind::Interrupted => continue,
            Err(_) => {
                failed.store(true, Ordering::SeqCst);
                return;
            }
        };
        if !startup.load(Ordering::SeqCst) {
            line.clear();
            continue;
        }
        if total.fetch_add(count, Ordering::SeqCst) > OUTPUT_LIMIT.saturating_sub(count) {
            failed.store(true, Ordering::SeqCst);
            return;
        }
        if let Some(sender) = &ready {
            for byte in &buffer[..count] {
                if *byte == b'\n' {
                    if !overflow && let Some(port) = ready_port(&line) {
                        let _ = sender.try_send(port);
                    }
                    line.clear();
                    overflow = false;
                } else if line.len() < LINE_LIMIT && !overflow {
                    line.push(*byte);
                } else {
                    overflow = true;
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::mpsc::sync_channel;

    #[test]
    fn parses_only_complete_exact_ready_lines() {
        assert_eq!(ready_port(b"FORGE_DESKTOP_READY:65535\r"), Some(65535));
        for invalid in [
            "FORGE_DESKTOP_READY:0",
            "FORGE_DESKTOP_READY:65536",
            "FORGE_DESKTOP_READY:-1",
            "FORGE_DESKTOP_READY:12x",
            " FORGE_DESKTOP_READY:80",
            "FORGE_DESKTOP_READY:80 ",
            "FORGE_DESKTOP_READY:",
        ] {
            assert_eq!(ready_port(invalid.as_bytes()), None);
        }
    }

    #[test]
    fn rejects_other_origins_and_credentials() {
        assert!(owned_origin(
            &Url::parse("http://127.0.0.1:1234/index.html").unwrap(),
            1234
        ));
        for invalid in [
            "https://127.0.0.1:1234/",
            "http://localhost:1234/",
            "http://127.0.0.1:1235/",
            "http://127.0.0.1:1234.evil.test/",
            "http://user@127.0.0.1:1234/",
            "http://[::1]:1234/",
            "file:///index.html",
            "about:blank",
        ] {
            assert!(!Url::parse(invalid).is_ok_and(|url| owned_origin(&url, 1234)));
        }
    }

    #[test]
    fn bounds_lines_and_does_not_accept_truncated_markers() {
        let (sender, receiver) = sync_channel(1);
        let mut input = vec![b'x'; LINE_LIMIT + 1];
        input.extend_from_slice(
            b"FORGE_DESKTOP_READY:1111\nFORGE_DESKTOP_READY:2345\nFORGE_DESKTOP_READY:9999",
        );
        let failed = Arc::new(AtomicBool::new(false));
        drain_output(
            input.as_slice(),
            Some(sender),
            Arc::new(AtomicUsize::new(0)),
            failed.clone(),
            Arc::new(AtomicBool::new(true)),
        );
        assert_eq!(receiver.try_recv().unwrap(), 2345);
        assert!(receiver.try_recv().is_err());
        assert!(!failed.load(Ordering::SeqCst));
    }

    #[test]
    fn output_budget_is_shared_and_fails_closed() {
        let failed = Arc::new(AtomicBool::new(false));
        drain_output(
            &b"too much"[..],
            None,
            Arc::new(AtomicUsize::new(OUTPUT_LIMIT)),
            failed.clone(),
            Arc::new(AtomicBool::new(true)),
        );
        assert!(failed.load(Ordering::SeqCst));
    }

    #[test]
    fn runtime_output_is_discarded_without_a_lifetime_budget() {
        let failed = Arc::new(AtomicBool::new(false));
        let total = Arc::new(AtomicUsize::new(OUTPUT_LIMIT));
        drain_output(
            io::repeat(b'x').take((OUTPUT_LIMIT * 2) as u64),
            None,
            total.clone(),
            failed.clone(),
            Arc::new(AtomicBool::new(false)),
        );
        assert!(!failed.load(Ordering::SeqCst));
        assert_eq!(total.load(Ordering::SeqCst), OUTPUT_LIMIT);
    }

    #[test]
    fn tokens_are_random_64_lowercase_hex() {
        let first = session_token().unwrap();
        assert_eq!(first.len(), 64);
        assert!(
            first
                .bytes()
                .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
        );
        assert_ne!(first, session_token().unwrap());
    }

    #[test]
    fn blob_downloads_do_not_grant_navigation() {
        let blob = Url::parse("blob:http://127.0.0.1:1234/unique-id").unwrap();
        assert!(owned_download(&blob, 1234));
        assert!(!owned_origin(&blob, 1234));
        for invalid in [
            "blob:http://localhost:1234/id",
            "blob:http://127.0.0.1:4321/id",
            "blob:https://evil.test/id",
            "http://127.0.0.1:1234/api/file",
            "data:text/plain,hello",
        ] {
            assert!(!owned_download(&Url::parse(invalid).unwrap(), 1234));
        }
    }
}
