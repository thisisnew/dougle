use knowledge_forge_desktop::supervisor::Backend;
use serde_json::{Value, json};
use std::path::PathBuf;
use std::sync::atomic::AtomicBool;
use std::time::{Duration, SystemTime};

fn post(agent: &ureq::Agent, backend: &Backend, path: &str, body: Value) -> Value {
    agent
        .post(format!("http://127.0.0.1:{}/api{path}", backend.port))
        .header("X-Forge-Session", &backend.token)
        .header("X-Forge-Client", "1")
        .send_json(body)
        .unwrap()
        .body_mut()
        .read_json()
        .unwrap()
}

fn main() {
    let resources = PathBuf::from(
        std::env::args_os()
            .nth(1)
            .expect("App Contents/Resources required"),
    );
    let data = std::env::temp_dir().join(format!(
        "forge-git-smoke-{}-{:?}",
        std::process::id(),
        SystemTime::now()
    ));
    let backend = Backend::launch(&resources, &data, &AtomicBool::new(false)).unwrap();
    let agent: ureq::Agent = ureq::Agent::config_builder()
        .proxy(None)
        .max_redirects(0)
        .timeout_global(Some(Duration::from_secs(150)))
        .build()
        .into();
    let workspace = post(
        &agent,
        &backend,
        "/workspaces",
        json!({"name": "내장 Git 검증"}),
    );
    let path = format!("/workspaces/{}/sources", workspace["id"].as_str().unwrap());
    let source = post(
        &agent,
        &backend,
        &path,
        json!({
            "name": "공개 예제", "kind": "GITHUB", "location": "https://github.com/octocat/Hello-World", "domain": "검증"
        }),
    );
    let sync = format!("{}/{}/sync", path, source["id"].as_str().unwrap());
    let first = post(&agent, &backend, &sync, json!({}));
    assert_eq!(first["status"], "READY", "{first}");
    assert_eq!(first["revision"].as_str().unwrap().len(), 40);
    let search: Value = agent
        .get(format!(
            "http://127.0.0.1:{}/api/workspaces/{}/search?query=Hello",
            backend.port,
            workspace["id"].as_str().unwrap()
        ))
        .header("X-Forge-Session", &backend.token)
        .call()
        .unwrap()
        .body_mut()
        .read_json()
        .unwrap();
    assert!(
        !search.as_array().unwrap().is_empty(),
        "Collected README must be searchable"
    );
    let second = post(&agent, &backend, &sync, json!({}));
    assert_eq!(second["status"], "READY", "{second}");
    assert_eq!(second["revision"], first["revision"]);
    drop(backend);
    std::fs::remove_dir_all(data).unwrap();
    println!(
        "PASS: native supervisor, packaged Java/JAR/Git, public GitHub HTTPS collection, search and unchanged revision; no AI"
    );
}
