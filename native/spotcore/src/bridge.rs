//! JNI plumbing: exported functions, global references, callbacks into Kotlin.
//!
//! Threading rules:
//! * Every native thread that calls into Java attaches itself once as a daemon thread
//!   (`attach_current_thread_as_daemon`); it detaches automatically when the thread exits.
//! * Callbacks run inside a local reference frame so permanently attached threads never
//!   accumulate local references.
//! * Java exceptions raised by callbacks are described and cleared, never propagated.

use crate::{rpc, runtime};
use jni::objects::{GlobalRef, JByteBuffer, JClass, JMethodID, JObject, JString};
use jni::signature::{Primitive, ReturnType};
use jni::sys::{jint, jlong, jvalue, JNI_VERSION_1_6};
use jni::{JNIEnv, JavaVM};
use std::ffi::c_void;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::OnceLock;

static JVM: OnceLock<JavaVM> = OnceLock::new();
static CALLBACKS: OnceLock<Callbacks> = OnceLock::new();
static AUDIO: OnceLock<AudioBridge> = OnceLock::new();

struct Callbacks {
    obj: GlobalRef,
    on_result: JMethodID,
    on_event: JMethodID,
}

/// Handle to the Kotlin `AudioSinkBridge` (docs/ARCHITECTURE.md §3.2).
pub struct AudioBridge {
    obj: GlobalRef,
    start: JMethodID,
    stop: JMethodID,
    write: JMethodID,
    on_volume: JMethodID,
    /// Address of the shared direct buffer (`AudioSinkBridge.buffer`).
    buffer: *mut u8,
    buffer_frames: usize,
}

// SAFETY: the buffer pointer refers to a direct ByteBuffer kept alive by the GlobalRef'd bridge
// object; it is only written by the single librespot player thread, so sharing the handle is fine.
unsafe impl Send for AudioBridge {}
unsafe impl Sync for AudioBridge {}

/// Frames that fit into the shared PCM buffer (must match `AudioSinkBridge.BUFFER_FRAMES`).
pub const BUFFER_FRAMES: usize = 4096;
const BYTES_PER_FRAME: usize = 2 * 4;

pub fn jvm() -> Option<&'static JavaVM> {
    JVM.get()
}

/// Runs `f` with a JNIEnv for the current thread inside a fresh local frame.
pub fn with_env<R>(f: impl FnOnce(&mut JNIEnv) -> jni::errors::Result<R>) -> Option<R> {
    let vm = JVM.get()?;
    let mut env = match vm.attach_current_thread_as_daemon() {
        Ok(env) => env,
        Err(e) => {
            log::error!("JNI attach failed: {e}");
            return None;
        }
    };
    let result = env.with_local_frame(8, |env| f(env));
    if env.exception_check().unwrap_or(false) {
        let _ = env.exception_describe();
        let _ = env.exception_clear();
    }
    match result {
        Ok(r) => Some(r),
        Err(e) => {
            log::error!("JNI call failed: {e}");
            None
        }
    }
}

/// Delivers an RPC result to Kotlin (`NativeCallbacks.onResult`).
pub fn post_result(request_id: i64, ok: bool, json: &str) {
    let Some(cb) = CALLBACKS.get() else { return };
    with_env(|env| {
        let payload = env.new_string(json)?;
        let args = [
            jvalue { j: request_id },
            jvalue { z: ok as u8 },
            jvalue { l: payload.as_raw() },
        ];
        // SAFETY: method id and signature `(JZLjava/lang/String;)V` were resolved in nativeInit.
        unsafe {
            env.call_method_unchecked(cb.obj.as_obj(), cb.on_result, ReturnType::Primitive(Primitive::Void), &args)?;
        }
        Ok(())
    });
}

/// Delivers an event to Kotlin (`NativeCallbacks.onEvent`).
pub fn post_event(kind: &str, json: &str) {
    let Some(cb) = CALLBACKS.get() else { return };
    with_env(|env| {
        let kind = env.new_string(kind)?;
        let payload = env.new_string(json)?;
        let args = [jvalue { l: kind.as_raw() }, jvalue { l: payload.as_raw() }];
        // SAFETY: method id and signature `(Ljava/lang/String;Ljava/lang/String;)V` resolved in nativeInit.
        unsafe {
            env.call_method_unchecked(cb.obj.as_obj(), cb.on_event, ReturnType::Primitive(Primitive::Void), &args)?;
        }
        Ok(())
    });
}

pub fn audio() -> Option<&'static AudioBridge> {
    AUDIO.get()
}

impl AudioBridge {
    /// `AudioSinkBridge.start()`; returns false if the AudioTrack could not be started.
    pub fn start(&self) -> bool {
        with_env(|env| {
            // SAFETY: `start()Z`.
            let v = unsafe { env.call_method_unchecked(self.obj.as_obj(), self.start, ReturnType::Primitive(Primitive::Boolean), &[])? };
            v.z()
        })
        .unwrap_or(false)
    }

    /// `AudioSinkBridge.stop()`.
    pub fn stop(&self) {
        with_env(|env| {
            // SAFETY: `stop()V`.
            unsafe { env.call_method_unchecked(self.obj.as_obj(), self.stop, ReturnType::Primitive(Primitive::Void), &[])? };
            Ok(())
        });
    }

    /// Copies interleaved stereo f32 samples into the shared buffer and performs blocking writes.
    /// Returns false on a fatal sink error.
    pub fn write_f32(&self, samples: &[f32]) -> bool {
        let frames_total = samples.len() / 2;
        let mut offset = 0usize;
        while offset < frames_total {
            let frames = (frames_total - offset).min(self.buffer_frames);
            let src = &samples[offset * 2..(offset + frames) * 2];
            // SAFETY: the buffer holds `buffer_frames * 8` bytes and only this thread writes to it.
            unsafe {
                std::ptr::copy_nonoverlapping(src.as_ptr() as *const u8, self.buffer, frames * BYTES_PER_FRAME);
            }
            let written = with_env(|env| {
                // SAFETY: `write(I)I`.
                let v = unsafe {
                    env.call_method_unchecked(self.obj.as_obj(), self.write, ReturnType::Primitive(Primitive::Int), &[jvalue { i: frames as jint }])?
                };
                v.i()
            })
            .unwrap_or(-1);
            if written < 0 {
                return false;
            }
            offset += frames;
        }
        true
    }

    /// `AudioSinkBridge.onVolume(int)` (Connect volume 0..65535).
    pub fn on_volume(&self, volume: u16) {
        with_env(|env| {
            // SAFETY: `onVolume(I)V`.
            unsafe {
                env.call_method_unchecked(self.obj.as_obj(), self.on_volume, ReturnType::Primitive(Primitive::Void), &[jvalue { i: volume as jint }])?
            };
            Ok(())
        });
    }
}

fn jstring_to_string(env: &mut JNIEnv, s: &JString) -> Option<String> {
    if s.is_null() {
        return None;
    }
    env.get_string(s).ok().map(Into::into)
}

fn init_callbacks(env: &mut JNIEnv, callbacks: &JObject) -> jni::errors::Result<Callbacks> {
    let class = env.get_object_class(callbacks)?;
    let on_result = env.get_method_id(&class, "onResult", "(JZLjava/lang/String;)V")?;
    let on_event = env.get_method_id(&class, "onEvent", "(Ljava/lang/String;Ljava/lang/String;)V")?;
    Ok(Callbacks { obj: env.new_global_ref(callbacks)?, on_result, on_event })
}

fn init_audio(env: &mut JNIEnv, audio: &JObject) -> jni::errors::Result<AudioBridge> {
    let class = env.get_object_class(audio)?;
    let start = env.get_method_id(&class, "start", "()Z")?;
    let stop = env.get_method_id(&class, "stop", "()V")?;
    let write = env.get_method_id(&class, "write", "(I)I")?;
    let on_volume = env.get_method_id(&class, "onVolume", "(I)V")?;
    let buffer_obj = env.get_field(audio, "buffer", "Ljava/nio/ByteBuffer;")?.l()?;
    let buffer_obj = JByteBuffer::from(buffer_obj);
    let buffer = env.get_direct_buffer_address(&buffer_obj)?;
    let capacity = env.get_direct_buffer_capacity(&buffer_obj)?;
    let buffer_frames = (capacity / BYTES_PER_FRAME).min(BUFFER_FRAMES);
    Ok(AudioBridge { obj: env.new_global_ref(audio)?, start, stop, write, on_volume, buffer, buffer_frames })
}

fn install_logger(level: Option<&str>) {
    let filter = match level.unwrap_or("info") {
        "trace" | "debug" => log::LevelFilter::Debug, // trace would log tokens; never enable it
        "warn" => log::LevelFilter::Warn,
        "error" => log::LevelFilter::Error,
        _ => log::LevelFilter::Info,
    };
    #[cfg(target_os = "android")]
    {
        android_logger::init_once(
            android_logger::Config::default()
                .with_tag("spotcore")
                .with_max_level(filter)
                // librespot logs request URLs with tokens at trace/debug level in a few places.
                .with_filter(
                    android_logger::FilterBuilder::new()
                        .parse(&format!("{},librespot_core::http_client=info,librespot_core::dealer=info,hyper=warn,rustls=warn,h2=warn", filter.as_str().to_lowercase()))
                        .build(),
                ),
        );
    }
    #[cfg(not(target_os = "android"))]
    {
        log::set_max_level(filter);
    }
}

#[no_mangle]
pub extern "system" fn JNI_OnLoad(vm: JavaVM, _reserved: *mut c_void) -> jint {
    let _ = JVM.set(vm);
    JNI_VERSION_1_6
}

#[no_mangle]
pub extern "system" fn Java_com_taehagen_spotifygood_nativebridge_NativeBridge_nativeInit<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    callbacks: JObject<'l>,
    audio: JObject<'l>,
    config_json: JString<'l>,
) {
    let result = catch_unwind(AssertUnwindSafe(|| -> Result<(), String> {
        let json = jstring_to_string(&mut env, &config_json).ok_or("missing config")?;
        let config: runtime::InitConfig = serde_json::from_str(&json).map_err(|e| e.to_string())?;
        install_logger(config.log_level.as_deref());
        if CALLBACKS.get().is_none() {
            let cb = init_callbacks(&mut env, &callbacks).map_err(|e| e.to_string())?;
            let _ = CALLBACKS.set(cb);
        }
        if AUDIO.get().is_none() {
            let ab = init_audio(&mut env, &audio).map_err(|e| e.to_string())?;
            let _ = AUDIO.set(ab);
        }
        runtime::init(config)?;
        log::info!("spotcore {} initialised (librespot {})", env!("CARGO_PKG_VERSION"), librespot_core::version::SEMVER);
        Ok(())
    }));
    match result {
        Ok(Ok(())) => {}
        Ok(Err(e)) => {
            log::error!("nativeInit failed: {e}");
            let _ = env.throw_new("java/lang/IllegalStateException", format!("nativeInit failed: {e}"));
        }
        Err(_) => {
            let _ = env.throw_new("java/lang/IllegalStateException", "nativeInit panicked");
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_taehagen_spotifygood_nativebridge_NativeBridge_nativeCall<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    request_id: jlong,
    method: JString<'l>,
    args_json: JString<'l>,
) {
    let method = jstring_to_string(&mut env, &method).unwrap_or_default();
    let args = jstring_to_string(&mut env, &args_json).unwrap_or_else(|| "{}".into());
    let outcome = catch_unwind(AssertUnwindSafe(|| rpc::dispatch(request_id, method, args)));
    if outcome.is_err() {
        log::error!("nativeCall panicked");
        if request_id != 0 {
            post_result(request_id, false, &crate::AppError::internal("panic").to_json());
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_taehagen_spotifygood_nativebridge_NativeBridge_nativeCancel<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    request_id: jlong,
) {
    let _ = catch_unwind(|| rpc::cancel(request_id));
}
