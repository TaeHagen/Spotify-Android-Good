use jni::objects::JClass;
use jni::sys::jstring;
use jni::JNIEnv;

#[no_mangle]
pub extern "system" fn Java_com_taehagen_spotifygood_nativebridge_NativeBridge_nativeVersion(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let v = format!("spotcore {} / librespot {}", env!("CARGO_PKG_VERSION"), librespot_core::version::SEMVER);
    env.new_string(v).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
