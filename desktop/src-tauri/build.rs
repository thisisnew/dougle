fn main() {
    tauri_build::try_build(tauri_build::Attributes::new().app_manifest(
        tauri_build::AppManifest::new().commands(&["select_source_folder"]),
    )).expect("desktop build failed")
}
