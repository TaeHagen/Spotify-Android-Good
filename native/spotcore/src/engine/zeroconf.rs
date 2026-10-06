//! `session.zeroconfLogin`: another Spotify app on the LAN hands credentials over through
//! Spotify Connect zeroconf (libmdns + a small HTTP server, librespot-discovery).
//!
//! The discovery runs only for the duration of the call. A guard shuts it down on every exit
//! path, including `nativeCancel` (the task is aborted and the guard dropped).

use super::config::{self, KEYMASTER_CLIENT_ID};
use crate::error::{AppError, AppResult, ErrorCode};
use crate::{rpc, runtime};
use futures_util::StreamExt;
use librespot_discovery::{DeviceType, Discovery};
use serde::Deserialize;
use serde_json::{json, Value};
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

const DEFAULT_TIMEOUT_MS: u64 = 180_000;
const MAX_TIMEOUT_MS: u64 = 600_000;
const SHUTDOWN_TIMEOUT: Duration = Duration::from_secs(2);

static RUNNING: AtomicBool = AtomicBool::new(false);

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct ZeroconfArgs {
    #[serde(default)]
    timeout_ms: Option<u64>,
}

/// Owns the discovery; dropping it (normal end or cancellation) shuts the service down.
struct Guard {
    discovery: Option<Discovery>,
}

impl Guard {
    async fn shutdown(&mut self) {
        if let Some(d) = self.discovery.take() {
            if tokio::time::timeout(SHUTDOWN_TIMEOUT, d.shutdown()).await.is_err() {
                log::warn!("zeroconf shutdown timed out");
            }
        }
    }
}

impl Drop for Guard {
    fn drop(&mut self) {
        if let Some(d) = self.discovery.take() {
            // Cancelled: finish the shutdown (mDNS goodbye, server stop) in the background.
            runtime::handle().spawn(async move {
                let _ = tokio::time::timeout(SHUTDOWN_TIMEOUT, d.shutdown()).await;
            });
        }
        RUNNING.store(false, Ordering::Release);
    }
}

pub(crate) async fn login(args: ZeroconfArgs) -> AppResult<Value> {
    if RUNNING.swap(true, Ordering::AcqRel) {
        return Err(AppError::unavailable("A zeroconf login is already in progress"));
    }
    let timeout_ms = args.timeout_ms.unwrap_or(DEFAULT_TIMEOUT_MS).clamp(1_000, MAX_TIMEOUT_MS);
    let mut guard = Guard { discovery: None };
    let discovery = Discovery::builder(runtime::config().device_id.clone(), KEYMASTER_CLIENT_ID.to_string())
        .name(config::device_name(&super::settings()))
        .device_type(DeviceType::Smartphone)
        .launch()
        .map_err(AppError::from)?;
    guard.discovery = Some(discovery);
    log::info!("zeroconf discovery started");
    let next = async {
        match guard.discovery.as_mut() {
            Some(d) => d.next().await,
            None => None,
        }
    };
    let outcome = tokio::time::timeout(Duration::from_millis(timeout_ms), next).await;
    guard.shutdown().await;
    match outcome {
        Ok(Some(credentials)) => {
            let stored = config::from_librespot(&credentials)
                .ok_or_else(|| AppError::new(ErrorCode::BadCredentials, "Received incomplete credentials"))?;
            log::info!("zeroconf credentials received");
            rpc::to_value(&json!({ "credentials": stored }))
        }
        Ok(None) => Err(AppError::unavailable("Zeroconf discovery stopped (network or mDNS failure)")),
        Err(_) => Err(AppError::new(ErrorCode::Network, "No device handed over credentials in time")),
    }
}
