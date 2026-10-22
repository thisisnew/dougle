use std::path::{Path, PathBuf};
use tauri_plugin_updater::UpdaterExt;

async fn run(
    app: tauri::AppHandle,
    endpoint: String,
    executable: PathBuf,
    public_key: String,
) -> Result<(), Box<dyn std::error::Error>> {
    let original = std::fs::read(&executable)?;
    let user_data = executable
        .ancestors()
        .nth(5)
        .ok_or("Missing test root")?
        .join("user-data.txt");
    let data = std::fs::read(&user_data)?;
    let updater = |case: &str| {
        app.updater_builder()
            .pubkey(public_key.clone())
            .executable_path(&executable)
            .timeout(std::time::Duration::from_secs(5))
            .endpoints(vec![format!("{endpoint}/{case}").parse().unwrap()])
            .unwrap()
            .build()
            .unwrap()
    };
    assert!(updater("older").check().await?.is_none());
    assert!(updater("missing-target").check().await.is_err());
    for case in ["wrong-version", "tampered"] {
        let update = updater(case).check().await?.ok_or("Expected update")?;
        assert!(update.download(|_, _| {}, || {}).await.is_err());
        assert_eq!(std::fs::read(&executable)?, original);
    }
    let invalid = updater("invalid-archive")
        .check()
        .await?
        .ok_or("Expected update")?;
    let invalid_bytes = invalid.download(|_, _| {}, || {}).await?;
    assert!(invalid.install(invalid_bytes).is_err());
    assert_eq!(std::fs::read(&executable)?, original);
    let update = updater("valid").check().await?.ok_or("Expected update")?;
    let bytes = update.download(|_, _| {}, || {}).await?;
    let mut corrupted = bytes.clone();
    corrupted[0] ^= 1;
    assert!(update.install(corrupted).is_err());
    assert_eq!(std::fs::read(&executable)?, original);
    update.install(bytes)?;
    assert_eq!(
        std::fs::read_to_string(&executable)?,
        "synthetic updated executable"
    );
    assert_eq!(std::fs::read(&user_data)?, data);
    assert!(
        !executable
            .ancestors()
            .nth(3)
            .unwrap()
            .join("old-marker")
            .exists()
    );
    println!(
        "PASS: signed update installation, older version, missing target, tampering, signed version mismatch, invalid archive rollback and user data preservation"
    );
    Ok(())
}

fn main() {
    let arguments: Vec<_> = std::env::args().skip(1).collect();
    assert_eq!(arguments.len(), 3);
    let endpoint = arguments[0].clone();
    let executable = Path::new(&arguments[1]).to_path_buf();
    let public_key = arguments[2].clone();
    let application = tauri::Builder::default()
        .plugin(tauri_plugin_updater::Builder::new().build())
        .setup(move |app| {
            tauri::WebviewWindowBuilder::new(
                app,
                "updater-test",
                tauri::WebviewUrl::App("index.html".into()),
            )
            .visible(false)
            .build()?;
            let handle = app.handle().clone();
            tauri::async_runtime::spawn(async move {
                let result = run(handle.clone(), endpoint, executable, public_key).await;
                if let Err(error) = &result {
                    eprintln!("FAIL: updater fixture: {error}");
                }
                handle.exit(if result.is_ok() { 0 } else { 1 });
            });
            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("updater test application");
    application.run(|_, _| {});
}
