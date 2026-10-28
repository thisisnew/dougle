use std::path::PathBuf;
use tauri_plugin_updater::UpdaterExt;

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let arguments: Vec<_> = std::env::args().skip(1).collect();
    if arguments.len() != 2 {
        return Err("Expected isolated installed app and previous version".into());
    }
    let installed = PathBuf::from(&arguments[0]).canonicalize()?;
    let temporary = std::env::temp_dir().canonicalize()?;
    if !installed.starts_with(&temporary)
        || !installed.ancestors().any(|path| {
            path.file_name().is_some_and(|name| {
                name.to_string_lossy()
                    .starts_with("forge-published-update-")
            })
        })
        || installed.file_name().and_then(|name| name.to_str()) != Some("Dougle.app")
    {
        return Err("Only an isolated test installation is allowed".into());
    }
    let mut context = tauri::generate_context!();
    context.package_info_mut().version = arguments[1].parse()?;
    let application = tauri::Builder::default()
        .plugin(tauri_plugin_updater::Builder::new().build())
        .setup(move |app| {
            let handle = app.handle().clone();
            tauri::async_runtime::spawn(async move {
                let result: Result<(), Box<dyn std::error::Error>> = async {
                    let updater = handle
                        .updater_builder()
                        .executable_path(installed.join("Contents/MacOS/dougle-desktop"))
                        .timeout(std::time::Duration::from_secs(300))
                        .build()?;
                    let update = updater.check().await?.ok_or("No newer public update")?;
                    if update.download_url.scheme() != "https"
                        || update.download_url.host_str() != Some("github.com")
                        || !update
                            .download_url
                            .path()
                            .starts_with("/thisisnew/dougle/releases/download/")
                    {
                        return Err("Unexpected update destination".into());
                    }
                    let bytes = update.download(|_, _| {}, || {}).await?;
                    update.install(bytes)?;
                    println!(
                        "PASS: public signed update installed into isolated real app: {}",
                        update.version
                    );
                    Ok(())
                }
                .await;
                if let Err(error) = &result {
                    eprintln!("FAIL: published update: {error}");
                }
                handle.exit(if result.is_ok() { 0 } else { 1 });
            });
            Ok(())
        })
        .build(context)?;
    application.run(|_, _| {});
    Ok(())
}
