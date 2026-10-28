use std::fs::{self, OpenOptions};
use std::os::fd::AsRawFd;
use std::os::unix::fs::OpenOptionsExt;
use std::path::Path;

pub fn migrate(data: &Path) -> Result<(), &'static str> {
    let parent = data.parent().ok_or("앱 데이터 경로를 확인하지 못했습니다.")?;
    let legacy = parent.join("dev.knowledgeforge.desktop");
    if !legacy.try_exists().map_err(|_| "이전 자료 경로를 확인하지 못했습니다.")? {
        return Ok(());
    }
    if fs::symlink_metadata(&legacy).map_err(|_| "이전 자료를 확인하지 못했습니다.")?.file_type().is_symlink() {
        return Err("이전 자료 경로가 링크입니다. 원래 폴더를 확인해 주세요.");
    }
    if data.exists() {
        return Err("이전 자료와 새 자료 폴더가 모두 있습니다. 자료 보호를 위해 자동 이전하지 않습니다.");
    }
    let lock = OpenOptions::new().write(true).create(true).truncate(false).mode(0o600)
        .custom_flags(libc::O_NOFOLLOW).open(legacy.join(".desktop.lock"))
        .map_err(|_| "이전 자료 잠금을 확인하지 못했습니다.")?;
    let mut region: libc::flock = unsafe { std::mem::zeroed() };
    region.l_type = libc::F_WRLCK as _;
    region.l_whence = libc::SEEK_SET as _;
    if unsafe { libc::fcntl(lock.as_raw_fd(), libc::F_SETLK, &region) } == -1 {
        return Err("이전 앱이 실행 중입니다. 이전 앱을 완전히 종료한 뒤 Dougle을 다시 여세요.");
    }
    fs::rename(&legacy, data).map_err(|_| "기존 자료를 이전하지 못했습니다. 폴더 권한과 저장 공간을 확인하세요.")?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    fn root() -> std::path::PathBuf {
        let path = std::env::temp_dir().join(format!("dougle-migration-{}-{}", std::process::id(), crate::protocol::session_token().unwrap()));
        fs::create_dir(&path).unwrap();
        path
    }
    #[test]
    fn fresh_install_and_repeat_are_noops() {
        let root = root(); let target = root.join("dev.dougle.desktop");
        migrate(&target).unwrap(); fs::create_dir(&target).unwrap(); migrate(&target).unwrap();
        fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn moves_all_data_without_reading_or_copying_secrets() {
        let root = root(); let legacy = root.join("dev.knowledgeforge.desktop");
        let target = root.join("dev.dougle.desktop"); fs::create_dir(&legacy).unwrap();
        fs::write(legacy.join("synthetic-data"), b"unchanged").unwrap();
        migrate(&target).unwrap(); migrate(&target).unwrap();
        assert_eq!(fs::read(target.join("synthetic-data")).unwrap(), b"unchanged");
        assert!(!legacy.exists()); fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn refuses_collision_and_symlink() {
        let root = root(); let legacy = root.join("dev.knowledgeforge.desktop");
        let target = root.join("dev.dougle.desktop"); fs::create_dir(&legacy).unwrap(); fs::create_dir(&target).unwrap();
        assert!(migrate(&target).is_err()); fs::remove_dir(&legacy).unwrap();
        std::os::unix::fs::symlink(&target, &legacy).unwrap(); assert!(migrate(&target).is_err());
        fs::remove_dir_all(root).unwrap();
    }
}
