//! Request dispatch: `nativeCall(id, method, args)` → module handler → `onResult(id, ok, json)`.
//!
//! Every call runs as its own tokio task. The task's abort handle is registered so that
//! `nativeCancel(id)` can abort it. Exactly one result is delivered per non-zero request id:
//! whoever removes the id from the registry first (the finishing task or the canceller) posts it.

use crate::error::{AppError, AppResult};
use crate::{bridge, catalog, connect, engine, offline, runtime, zeroconf_client};
use futures_util::FutureExt;
use parking_lot::Mutex;
use serde_json::Value;
use std::collections::HashMap;
use std::panic::AssertUnwindSafe;
use std::sync::OnceLock;
use tokio::task::AbortHandle;

fn registry() -> &'static Mutex<HashMap<i64, AbortHandle>> {
    static REG: OnceLock<Mutex<HashMap<i64, AbortHandle>>> = OnceLock::new();
    REG.get_or_init(|| Mutex::new(HashMap::new()))
}

pub fn dispatch(request_id: i64, method: String, args_json: String) {
    if !runtime::is_initialized() {
        if request_id != 0 {
            bridge::post_result(request_id, false, &AppError::internal("nativeInit not called").to_json());
        }
        return;
    }
    let args: Value = match serde_json::from_str(&args_json) {
        Ok(v) => v,
        Err(e) => {
            if request_id != 0 {
                bridge::post_result(request_id, false, &AppError::invalid(format!("args: {e}")).to_json());
            }
            return;
        }
    };
    let handle = runtime::handle();
    // Hold the registry lock while spawning so the task cannot finish before it is registered.
    let mut reg = registry().lock();
    let task_method = method.clone();
    let join = handle.spawn(async move {
        let outcome = AssertUnwindSafe(route(&task_method, args)).catch_unwind().await;
        let result = match outcome {
            Ok(r) => r,
            Err(_) => Err(AppError::internal(format!("panic in {task_method}"))),
        };
        finish(request_id, &task_method, result);
    });
    if request_id != 0 {
        reg.insert(request_id, join.abort_handle());
    }
}

fn finish(request_id: i64, method: &str, result: AppResult<Value>) {
    if request_id == 0 {
        if let Err(e) = result {
            log::warn!("{method} failed: {e}");
        }
        return;
    }
    if registry().lock().remove(&request_id).is_none() {
        return; // cancelled; the canceller already answered
    }
    match result {
        Ok(v) => bridge::post_result(request_id, true, &v.to_string()),
        Err(e) => bridge::post_result(request_id, false, &e.to_json()),
    }
}

pub fn cancel(request_id: i64) {
    if request_id == 0 {
        return;
    }
    if let Some(h) = registry().lock().remove(&request_id) {
        h.abort();
        bridge::post_result(request_id, false, &AppError::cancelled().to_json());
    }
}

async fn route(method: &str, args: Value) -> AppResult<Value> {
    // ZeroConf local-network login (connect.localInfo / connect.localLogin) before the generic
    // connect route; see docs/ARCHITECTURE.md §6.2, §8.
    if method.starts_with("connect.local") {
        return zeroconf_client::handle(method, args).await;
    }
    let namespace = method.split('.').next().unwrap_or_default();
    match namespace {
        "session" => engine::handle(method, args).await,
        "player" | "queue" | "connect" => {
            if method == "player.load" {
                // A load that may be routed offline (no session online, or no network, also
                // while the session still reads Online) needs the downloads index; returns at
                // once while streaming is possible.
                engine::await_offline_index(engine::OFFLINE_INDEX_WAIT).await;
            }
            connect::handle(method, args).await
        }
        "catalog" | "library" | "playlist" => catalog::handle(method, args).await,
        "download" | "offline" => {
            let result = offline::handle(method, args).await;
            if method == "offline.setIndex" && result.is_ok() {
                engine::offline_index_received();
            }
            result
        }
        _ => Err(AppError::invalid(format!("unknown method {method}"))),
    }
}

/// Deserialises RPC arguments into a typed struct.
pub fn parse_args<T: serde::de::DeserializeOwned>(args: Value) -> AppResult<T> {
    serde_json::from_value(args).map_err(|e| AppError::invalid(format!("bad arguments: {e}")))
}

/// Serialises a handler result.
pub fn to_value<T: serde::Serialize>(v: &T) -> AppResult<Value> {
    serde_json::to_value(v).map_err(|e| AppError::internal(format!("serialise: {e}")))
}

pub fn ok() -> AppResult<Value> {
    Ok(Value::Object(Default::default()))
}
