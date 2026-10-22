use crate::protocol::{drain_output, session_token};
use std::io::Write;
use std::os::unix::process::CommandExt;
use std::path::Path;
use std::process::{Child, ChildStdin, Command, Stdio};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, mpsc};
use std::thread;
use std::time::{Duration, Instant};

pub const STARTUP_TIMEOUT: Duration = Duration::from_secs(60);
pub const SHUTDOWN_TIMEOUT: Duration = Duration::from_secs(8);
pub const POLL_INTERVAL: Duration = Duration::from_millis(100);

fn readiness_agent() -> ureq::Agent {
    ureq::Agent::config_builder()
        .proxy(None)
        .max_redirects(0)
        .timeout_global(Some(Duration::from_secs(2)))
        .build()
        .into()
}

fn authenticated_ready(agent: &ureq::Agent, port: u16, token: &str) -> bool {
    let url = format!("http://127.0.0.1:{port}/api/desktop/ready");
    let Ok(mut response) = agent.get(&url).header("X-Forge-Session", token).call() else {
        return false;
    };
    if response.status() != 200 {
        return false;
    }
    response
        .body_mut()
        .with_config()
        .limit(1024)
        .read_json::<serde_json::Value>()
        .is_ok_and(|body| body.get("ready") == Some(&serde_json::Value::Bool(true)))
}

pub struct Backend {
    child: Child,
    input: Option<ChildStdin>,
    output_failed: Arc<AtomicBool>,
    pub port: u16,
    pub token: String,
    agent: ureq::Agent,
}

impl Backend {
    pub fn launch(
        resources: &Path,
        data: &Path,
        cancelled: &AtomicBool,
    ) -> Result<Self, &'static str> {
        let java = resources
            .join("resources/runtime/bin/java")
            .canonicalize()
            .map_err(|_| "내장 Java 실행 환경이 없습니다. 앱을 다시 설치해 주세요.")?;
        let jar = resources
            .join("resources/knowledge-forge.jar")
            .canonicalize()
            .map_err(|_| "앱 실행 파일이 없습니다. 앱을 다시 설치해 주세요.")?;
        if !java.is_file() || !jar.is_file() || !data.is_absolute() {
            return Err("앱 실행 경로를 확인할 수 없습니다. 앱을 다시 설치해 주세요.");
        }
        std::fs::create_dir_all(data).map_err(
            |_| "앱 데이터 폴더를 만들 수 없습니다. 저장 공간과 폴더 권한을 확인해 주세요.",
        )?;
        let token = session_token()?;
        let child = Command::new(java)
            .process_group(0)
            .arg("-jar")
            .arg(jar)
            .arg("--forge.desktop=true")
            .env("FORGE_DESKTOP", "true")
            .env("FORGE_DATA_DIR", data)
            .env_remove("JAVA_TOOL_OPTIONS")
            .env_remove("JDK_JAVA_OPTIONS")
            .env_remove("_JAVA_OPTIONS")
            .current_dir(data)
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn()
            .map_err(
                |_| "내장 Java를 실행할 수 없습니다. Mac 아키텍처와 앱 설치 상태를 확인해 주세요.",
            )?;
        let mut backend = Self {
            child,
            input: None,
            output_failed: Arc::new(AtomicBool::new(false)),
            port: 0,
            token,
            agent: readiness_agent(),
        };
        backend.input = backend.child.stdin.take();
        let (sender, receiver) = mpsc::sync_channel(1);
        let total = Arc::new(AtomicUsize::new(0));
        let startup = Arc::new(AtomicBool::new(true));
        let stdout = backend
            .child
            .stdout
            .take()
            .ok_or("앱 출력 연결에 실패했습니다.")?;
        let stderr = backend
            .child
            .stderr
            .take()
            .ok_or("앱 출력 연결에 실패했습니다.")?;
        for (reader, ready) in [
            (
                Box::new(stdout) as Box<dyn std::io::Read + Send>,
                Some(sender),
            ),
            (Box::new(stderr), None),
        ] {
            let total = total.clone();
            let failed = backend.output_failed.clone();
            let startup = startup.clone();
            thread::spawn(move || drain_output(reader, ready, total, failed, startup));
        }
        let input = backend
            .input
            .as_mut()
            .ok_or("앱 입력 연결에 실패했습니다.")?;
        writeln!(input, "{}", backend.token)
            .and_then(|_| input.flush())
            .map_err(|_| "보안 세션을 전달할 수 없습니다. 앱을 다시 실행해 주세요.")?;
        let deadline = Instant::now() + STARTUP_TIMEOUT;
        while Instant::now() < deadline {
            if cancelled.load(Ordering::SeqCst) {
                return Err("앱 시작이 취소되었습니다.");
            }
            backend.check_process()?;
            if backend.port == 0
                && let Ok(port) = receiver.try_recv()
            {
                backend.port = port;
            }
            if backend.port != 0 && backend.authenticated_ready() {
                backend.check_process()?;
                startup.store(false, Ordering::SeqCst);
                return Ok(backend);
            }
            thread::sleep(POLL_INTERVAL);
        }
        Err(
            "앱 준비 시간이 초과되었습니다. 다른 Knowledge Forge 창을 닫고 저장 공간을 확인한 뒤 다시 실행해 주세요.",
        )
    }

    pub fn check_process(&mut self) -> Result<(), &'static str> {
        if self.output_failed.load(Ordering::SeqCst) {
            return Err(
                "앱 출력 한도를 초과했거나 출력 연결이 끊어졌습니다. 앱을 종료한 뒤 다시 실행해 주세요.",
            );
        }
        match self.child.try_wait() {
            Ok(None) => Ok(()),
            _ => Err(
                "로컬 서비스가 종료되었습니다. 다른 Knowledge Forge 창이 열려 있는지, 저장 공간과 앱 설치 상태를 확인해 주세요. 작업은 자동 재전송되지 않습니다.",
            ),
        }
    }

    pub fn authenticated_ready(&self) -> bool {
        authenticated_ready(&self.agent, self.port, &self.token)
    }
}

pub fn stop_child(child: &mut Child, input: &mut Option<ChildStdin>, timeout: Duration) {
    drop(input.take());
    let deadline = Instant::now() + timeout;
    loop {
        match child.try_wait() {
            Ok(Some(_)) => return,
            Ok(None) if Instant::now() < deadline => thread::sleep(Duration::from_millis(25)),
            Ok(None) => {
                if let Ok(process_id) = i32::try_from(child.id())
                    && process_id > 1
                    && unsafe { libc::getpgid(process_id) } == process_id
                {
                    unsafe {
                        libc::kill(-process_id, libc::SIGKILL);
                    }
                }
                break;
            }
            _ => break,
        }
    }
    let _ = child.kill();
    let _ = child.wait();
}

impl Drop for Backend {
    fn drop(&mut self) {
        stop_child(&mut self.child, &mut self.input, SHUTDOWN_TIMEOUT);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{BufRead, BufReader};
    use std::net::TcpListener;

    fn readiness_response(status: &str, body: &str, extra_headers: &str) -> bool {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let response = format!(
            "HTTP/1.1 {status}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n{extra_headers}\r\n{body}",
            body.len()
        );
        let server = thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            stream
                .set_read_timeout(Some(Duration::from_secs(3)))
                .unwrap();
            let mut reader = BufReader::new(&stream);
            let mut request = String::new();
            loop {
                let mut line = String::new();
                reader.read_line(&mut line).unwrap();
                if line == "\r\n" {
                    break;
                }
                request.push_str(&line);
            }
            assert!(request.starts_with("GET /api/desktop/ready HTTP/1.1\r\n"));
            assert!(
                request
                    .to_ascii_lowercase()
                    .contains("x-forge-session: synthetic-token\r\n")
            );
            stream.write_all(response.as_bytes()).unwrap();
        });
        let ready = authenticated_ready(&readiness_agent(), port, "synthetic-token");
        server.join().unwrap();
        ready
    }

    #[test]
    fn readiness_requires_authenticated_success_and_true_json() {
        assert!(readiness_response("200 OK", "{\"ready\":true}", ""));
        for (status, body) in [
            ("403 Forbidden", "{\"ready\":true}"),
            ("503 Service Unavailable", "{\"ready\":false}"),
            ("200 OK", "{\"ready\":false}"),
            ("200 OK", "{}"),
            ("200 OK", "invalid"),
        ] {
            assert!(!readiness_response(status, body, ""));
        }
    }

    #[test]
    fn readiness_rejects_redirects_and_oversized_bodies() {
        assert!(!readiness_response(
            "302 Found",
            "",
            "Location: http://127.0.0.1:1/forbidden\r\n"
        ));
        let body = format!("{{\"ready\":true,\"padding\":\"{}\"}}", "x".repeat(2048));
        assert!(!readiness_response("200 OK", &body, ""));
    }

    #[test]
    fn eof_allows_graceful_exit() {
        let mut child = Command::new("/bin/sh")
            .process_group(0)
            .args(["-c", "cat >/dev/null; exit 23"])
            .stdin(Stdio::piped())
            .stdout(Stdio::null())
            .spawn()
            .unwrap();
        let mut input = child.stdin.take();
        stop_child(&mut child, &mut input, Duration::from_secs(2));
        assert!(input.is_none());
        assert_eq!(child.wait().unwrap().code(), Some(23));
    }

    #[test]
    fn unresponsive_owned_child_is_killed_and_reaped() {
        let mut child = Command::new("/bin/sh")
            .process_group(0)
            .args(["-c", "exec sleep 30"])
            .stdin(Stdio::piped())
            .spawn()
            .unwrap();
        let mut input = child.stdin.take();
        let started = Instant::now();
        stop_child(&mut child, &mut input, Duration::from_millis(50));
        assert!(started.elapsed() < Duration::from_secs(2));
        assert!(!child.wait().unwrap().success());
    }

    #[test]
    fn forced_shutdown_kills_owned_descendants_but_not_another_group() {
        use std::io::Read;
        let mut unrelated = Command::new("/bin/sleep")
            .arg("30")
            .process_group(0)
            .spawn()
            .unwrap();
        let mut child = Command::new("/bin/sh")
            .process_group(0)
            .args(["-c", "sleep 30 & echo ready; wait"])
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .spawn()
            .unwrap();
        let mut input = child.stdin.take();
        let mut output = BufReader::new(child.stdout.take().unwrap());
        let mut ready = String::new();
        output.read_line(&mut ready).unwrap();
        assert_eq!(ready, "ready\n");
        let (sender, receiver) = mpsc::channel();
        thread::spawn(move || {
            let mut remaining = Vec::new();
            let _ = sender.send(output.read_to_end(&mut remaining).is_ok());
        });
        stop_child(&mut child, &mut input, Duration::from_millis(50));
        let descendant_closed_output = receiver.recv_timeout(Duration::from_secs(2));
        let unrelated_alive = unrelated.try_wait().unwrap().is_none();
        stop_child(&mut unrelated, &mut None, Duration::ZERO);
        assert_eq!(descendant_closed_output, Ok(true));
        assert!(unrelated_alive);
        assert!(!child.wait().unwrap().success());
    }
}
