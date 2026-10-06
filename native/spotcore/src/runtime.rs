use serde::Deserialize;
use std::path::PathBuf;
use std::sync::OnceLock;
use tokio::runtime::{Builder, Handle, Runtime};

/// `nativeInit` configuration (docs/ARCHITECTURE.md §3.1).
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct InitConfig {
    pub files_dir: PathBuf,
    pub cache_dir: PathBuf,
    pub no_backup_dir: PathBuf,
    pub device_id: String,
    pub device_name: String,
    #[serde(default)]
    pub log_level: Option<String>,
}

struct Globals {
    runtime: Runtime,
    config: InitConfig,
}

static GLOBALS: OnceLock<Globals> = OnceLock::new();

/// Creates the process-wide runtime. Idempotent: later calls keep the first configuration.
pub fn init(config: InitConfig) -> Result<(), String> {
    if GLOBALS.get().is_some() {
        return Ok(());
    }
    let runtime = Builder::new_multi_thread()
        .worker_threads(2)
        .max_blocking_threads(4)
        .thread_name("spotcore-rt")
        .enable_all()
        .build()
        .map_err(|e| format!("tokio runtime: {e}"))?;
    let _ = GLOBALS.set(Globals { runtime, config });
    Ok(())
}

pub fn is_initialized() -> bool {
    GLOBALS.get().is_some()
}

/// Handle of the process runtime. Panics if `nativeInit` was not called (caught at the JNI boundary).
pub fn handle() -> Handle {
    GLOBALS.get().expect("spotcore not initialised").runtime.handle().clone()
}

pub fn config() -> &'static InitConfig {
    &GLOBALS.get().expect("spotcore not initialised").config
}

/// Directory helpers (all created on demand by their users).
pub fn librespot_tmp_dir() -> PathBuf {
    config().cache_dir.join("librespot-tmp")
}
pub fn streaming_cache_dir() -> PathBuf {
    config().cache_dir.join("librespot-audio")
}
pub fn credentials_dir() -> PathBuf {
    config().no_backup_dir.join("librespot")
}
