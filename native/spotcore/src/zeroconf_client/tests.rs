//! Offline proof that the client encoder is the exact inverse of the librespot device side:
//! the envelope is decrypted exactly as `librespot-discovery` 0.8.0 `server.rs::handle_add_user`
//! does, and the inner blob is decoded by librespot's own `Credentials::with_blob`. If the
//! bytes come back as the credentials we started from, a real librespot/spotifyd speaker would
//! accept them.

use super::{credentials_blob, credentials_for_test, decode_public_key, seal, seal_with};
use aes::cipher::{KeyIvInit, StreamCipher};
use base64::engine::general_purpose::STANDARD as BASE64;
use base64::Engine as _;
use hmac::{Hmac, Mac};
use librespot_core::authentication::Credentials;
use librespot_core::diffie_hellman::DhLocalKeys;
use librespot_protocol::authentication::AuthenticationType;
use protobuf::Enum;
use sha1::{Digest, Sha1};

type Aes128Ctr = ctr::Ctr128BE<aes::Aes128>;
type HmacSha1 = Hmac<Sha1>;

const DEVICE_ID: &str = "0123456789abcdef0123456789abcdef01234567";

/// Decrypts the ZeroConf blob exactly as librespot-discovery 0.8.0 `handle_add_user` does, from
/// the shared secret the device would compute. Returns the inner (still base64-ECB) blob bytes.
fn server_decrypt(shared_key: &[u8], encrypted_blob_b64: &str) -> Vec<u8> {
    let encrypted_blob = BASE64.decode(encrypted_blob_b64).expect("base64");
    let len = encrypted_blob.len();
    assert!(len >= 16, "blob too short");
    let iv = &encrypted_blob[0..16];
    let encrypted = &encrypted_blob[16..len - 20];
    let cksum = &encrypted_blob[len - 20..len];

    let base_key = Sha1::digest(shared_key);
    let base_key = &base_key[..16];

    let checksum_key = {
        let mut h = HmacSha1::new_from_slice(base_key).unwrap();
        h.update(b"checksum");
        h.finalize().into_bytes()
    };
    let encryption_key = {
        let mut h = HmacSha1::new_from_slice(base_key).unwrap();
        h.update(b"encryption");
        h.finalize().into_bytes()
    };

    let mut mac = HmacSha1::new_from_slice(&checksum_key).unwrap();
    mac.update(encrypted);
    mac.verify_slice(cksum).expect("checksum must verify (server would reject with ERROR-MAC)");

    let mut data = encrypted.to_vec();
    let mut cipher = Aes128Ctr::new_from_slices(&encryption_key[0..16], iv).unwrap();
    cipher.apply_keystream(&mut data);
    data
}

/// Full round trip: act as the device (hold its DH keys), run the client encoder, decrypt as the
/// server, decode with `Credentials::with_blob`, and check we recovered the input.
fn round_trip(username: &str, auth_data: &[u8]) {
    let creds = credentials_for_test(username, auth_data);
    let device_keys = DhLocalKeys::random(&mut rand::rng());
    let device_public = device_keys.public_key();

    let inner = credentials_blob(
        creds.username.as_deref().unwrap(),
        creds.auth_type.value(),
        &creds.auth_data,
        DEVICE_ID,
    )
    .expect("encode");

    // What the client sends on the wire.
    let envelope = seal(&device_public, inner.as_bytes()).expect("seal");
    let client_public = BASE64.decode(&envelope.client_key).expect("client key base64");

    // The device recomputes the shared secret from the client's public key.
    let shared = device_keys.shared_secret(&client_public);
    let decrypted = server_decrypt(&shared, &envelope.blob);

    // The decrypted payload is the inner blob's base64 text; `with_blob` base64-decodes it.
    assert_eq!(decrypted, inner.as_bytes(), "server decrypt yields the inner blob");

    let recovered = Credentials::with_blob(username, &decrypted, DEVICE_ID).expect("with_blob");
    assert_eq!(recovered.username.as_deref(), Some(username));
    assert_eq!(recovered.auth_type, AuthenticationType::AUTHENTICATION_STORED_SPOTIFY_CREDENTIALS);
    assert_eq!(recovered.auth_data, auth_data, "auth blob survives the full round trip");
}

#[test]
fn inner_blob_decodes_with_librespot() {
    // The inner layer alone, through librespot's own decoder.
    let username = "spotify_user_42";
    let auth_data = b"reusable-auth-blob-bytes";
    let blob = credentials_blob(username, 1, auth_data, DEVICE_ID).expect("encode");
    let creds = Credentials::with_blob(username, &blob, DEVICE_ID).expect("with_blob");
    assert_eq!(creds.username.as_deref(), Some(username));
    assert_eq!(creds.auth_type, AuthenticationType::AUTHENTICATION_STORED_SPOTIFY_CREDENTIALS);
    assert_eq!(creds.auth_data, auth_data);
}

#[test]
fn full_round_trip_short() {
    round_trip("alice", b"short");
}

#[test]
fn full_round_trip_realistic_length() {
    // A realistic reusable-credentials blob is ~150 bytes, exercising the two-byte length varint
    // and multiple AES blocks.
    let auth_data: Vec<u8> = (0..160u16).map(|i| (i % 256) as u8).collect();
    round_trip("31xq7abcdefghijklmnopqrstuvwx", &auth_data);
}

#[test]
fn full_round_trip_block_aligned() {
    // Lengths around the AES block boundary exercise the padding branch.
    for n in [1usize, 15, 16, 17, 31, 32, 33] {
        let auth_data: Vec<u8> = (0..n).map(|i| (i as u8).wrapping_mul(7)).collect();
        round_trip("boundary_user", &auth_data);
    }
}

#[test]
fn deterministic_envelope_matches_manual_decrypt() {
    // With fixed DH keys and IV the whole thing is reproducible: a regression guard on the
    // exact key-derivation and framing.
    let device_keys = DhLocalKeys::random(&mut rand::rng());
    let device_public = device_keys.public_key();
    let client_keys = DhLocalKeys::random(&mut rand::rng());
    let iv = [7u8; 16];
    let inner = credentials_blob("bob", 1, b"xyzzy", DEVICE_ID).expect("encode");
    let envelope = seal_with(&client_keys, &device_public, iv, inner.as_bytes());

    let blob = BASE64.decode(&envelope.blob).expect("base64");
    assert_eq!(&blob[..16], &iv, "the IV is the first 16 bytes");
    let shared = device_keys.shared_secret(&BASE64.decode(&envelope.client_key).unwrap());
    let decrypted = server_decrypt(&shared, &envelope.blob);
    let creds = Credentials::with_blob("bob", &decrypted, DEVICE_ID).expect("with_blob");
    assert_eq!(creds.auth_data, b"xyzzy");
}

#[test]
fn rejects_unusable_public_key() {
    assert!(decode_public_key(&BASE64.encode(b"INVALID")).is_err());
    assert!(decode_public_key("").is_err());
    assert!(decode_public_key(&BASE64.encode([0u8; 32])).is_err());
    assert!(decode_public_key("not base64!!!").is_err());
    // A plausible 96-byte key is accepted.
    assert!(decode_public_key(&BASE64.encode([0x42u8; 96])).is_ok());
    // seal refuses to encrypt to a bad key rather than leaking a blob.
    assert!(seal(b"", b"x").is_err());
    assert!(seal(&[1u8], b"x").is_err());
}

// ---------------------------------------------------------------------------------------------
// The device-facing login flow against a scripted fake device over real HTTP (127.0.0.1)
// ---------------------------------------------------------------------------------------------

use super::{add_user_flow, needs_wake_up, wake_up_form, Account, Endpoint};
use crate::device_token::{TokenSource, ZEROCONF_SOURCES};
use crate::error::{AppError, AppResult, ErrorCode};
use crate::models::StoredCredentials;
use std::collections::BTreeMap;
use std::sync::{Arc, Mutex};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};

const INVALID_KEY_B64: &str = "SU5WQUxJRA==";
const FAKE_DEVICE_ID: &str = "fakedevice0001";
const FAKE_CLIENT_ID: &str = "0123456789abcdef0123456789abcdef";
const ADD_USER_OK: &str = r#"{"status":101,"spotifyError":0,"statusString":"OK"}"#;
const ADD_USER_INVALID_KEY: &str = r#"{"status":203,"spotifyError":0,"statusString":"ERROR-INVALID-PUBLICKEY"}"#;
const ADD_USER_LOGIN_FAILED: &str = r#"{"status":202,"spotifyError":0,"statusString":"ERROR-LOGIN-FAILED"}"#;

fn account(auth_data: &[u8]) -> Account {
    Account {
        username: "alice".into(),
        credentials: Some(StoredCredentials { username: "alice".into(), auth_type: 1, auth_data: BASE64.encode(auth_data) }),
        device_name: "Test Phone".into(),
        device_id: "phone-device-id".into(),
    }
}

async fn no_token(_: TokenSource, _: String, _: String) -> AppResult<String> {
    Err(AppError::internal("this test mints no token"))
}

/// A token naming its source and what it was minted for.
async fn token_for(source: TokenSource, client_id: String, device_id: String) -> AppResult<String> {
    Ok(format!("token-{source:?}-{client_id}-{device_id}"))
}

type Minted = std::pin::Pin<Box<dyn std::future::Future<Output = AppResult<String>> + Send>>;
type MintCalls = Arc<Mutex<Vec<(TokenSource, String, String)>>>;

/// A minter that records every request and answers like [`token_for`].
fn recording_minter(calls: &MintCalls) -> impl Fn(TokenSource, String, String) -> Minted {
    let calls = calls.clone();
    move |source, client_id, device_id| {
        calls.lock().unwrap().push((source, client_id.clone(), device_id.clone()));
        Box::pin(token_for(source, client_id, device_id))
    }
}

#[derive(Default)]
struct FakeState {
    /// The service is unloaded (NOT-LOADED + "INVALID" key) until an addUser arrives.
    needs_wake_up: bool,
    woken: bool,
    /// After the wake-up, this many more getInfo calls still report NOT-LOADED.
    loading_polls: u32,
    /// The action of every request, in order.
    actions: Vec<String>,
    /// Params of every addUser.
    add_users: Vec<BTreeMap<String, String>>,
    /// What a key-based addUser decoded to (through librespot's own `with_blob`).
    credentials: Option<Credentials>,
    /// The first this many token addUsers are refused (202 ERROR-LOGIN-FAILED).
    refuse_tokens: usize,
}

/// A ZeroConf device: getInfo / addUser handled like librespot-discovery 0.8.0's server (same
/// decrypt and `with_blob`), plus the NOT-LOADED wake-up behaviour of eSDK speakers.
struct FakeDevice {
    keys: DhLocalKeys,
    token_type: &'static str,
    /// The getInfo `clientID`.
    client_id: &'static str,
    state: Mutex<FakeState>,
}

impl FakeDevice {
    fn new(token_type: &'static str, needs_wake_up: bool, loading_polls: u32) -> Arc<Self> {
        Arc::new(Self {
            keys: DhLocalKeys::random(&mut rand::rng()),
            token_type,
            client_id: FAKE_CLIENT_ID,
            state: Mutex::new(FakeState { needs_wake_up, loading_polls, ..Default::default() }),
        })
    }

    /// An `accesstoken` device reporting `client_id` that refuses the first `refuse_tokens` tokens.
    fn token_device(client_id: &'static str, refuse_tokens: usize) -> Arc<Self> {
        Arc::new(Self {
            keys: DhLocalKeys::random(&mut rand::rng()),
            token_type: "accesstoken",
            client_id,
            state: Mutex::new(FakeState { refuse_tokens, ..Default::default() }),
        })
    }

    fn loaded(state: &FakeState) -> bool {
        !state.needs_wake_up || (state.woken && state.loading_polls == 0)
    }

    fn handle(&self, method: &str, path: &str, params: &BTreeMap<String, String>) -> String {
        assert_eq!(path, "/zc", "requests go to the CPath");
        let action = params.get("action").cloned().unwrap_or_default();
        let mut state = self.state.lock().unwrap();
        state.actions.push(action.clone());
        match (method, action.as_str()) {
            ("GET", "getInfo") => {
                let loaded = Self::loaded(&state);
                if state.woken && state.loading_polls > 0 {
                    state.loading_polls -= 1;
                }
                let (availability, key) = if loaded {
                    ("", BASE64.encode(self.keys.public_key()))
                } else {
                    ("NOT-LOADED", INVALID_KEY_B64.to_string())
                };
                serde_json::json!({
                    "status": 101, "statusString": "OK", "spotifyError": 0, "version": "2.9.0",
                    "deviceID": FAKE_DEVICE_ID, "deviceType": "SPEAKER", "remoteName": "Fake Speaker",
                    "publicKey": key, "tokenType": self.token_type, "clientID": self.client_id,
                    "availability": availability, "activeUser": ""
                })
                .to_string()
            }
            ("POST", "addUser") => {
                state.add_users.push(params.clone());
                if !Self::loaded(&state) {
                    // The first addUser loads the service; its key isn't valid yet.
                    state.woken = true;
                    return ADD_USER_INVALID_KEY.into();
                }
                if params.get("tokenType").map(String::as_str) == Some("accesstoken") {
                    if state.refuse_tokens > 0 {
                        state.refuse_tokens -= 1;
                        return ADD_USER_LOGIN_FAILED.into();
                    }
                    return ADD_USER_OK.into();
                }
                let username = params.get("userName").cloned().unwrap_or_default();
                let client_key = BASE64.decode(params.get("clientKey").cloned().unwrap_or_default()).expect("clientKey");
                let shared = self.keys.shared_secret(&client_key);
                let decrypted = server_decrypt(&shared, params.get("blob").map(String::as_str).unwrap_or_default());
                state.credentials = Some(Credentials::with_blob(username, decrypted, FAKE_DEVICE_ID).expect("with_blob"));
                ADD_USER_OK.into()
            }
            _ => r#"{"status":302,"statusString":"ERROR-INVALID-ACTION"}"#.into(),
        }
    }
}

async fn serve_one(mut stream: TcpStream, device: Arc<FakeDevice>) {
    let mut buf = Vec::new();
    let mut chunk = [0u8; 4096];
    let header_end = loop {
        let n = stream.read(&mut chunk).await.expect("read");
        if n == 0 {
            return;
        }
        buf.extend_from_slice(&chunk[..n]);
        if let Some(pos) = buf.windows(4).position(|w| w == b"\r\n\r\n") {
            break pos + 4;
        }
    };
    let head = String::from_utf8_lossy(&buf[..header_end]).to_string();
    let mut lines = head.split("\r\n");
    let mut request_line = lines.next().expect("request line").split(' ');
    let method = request_line.next().expect("method").to_string();
    let target = request_line.next().expect("target").to_string();
    let content_length = lines
        .filter_map(|l| l.split_once(':'))
        .find(|(k, _)| k.trim().eq_ignore_ascii_case("content-length"))
        .map(|(_, v)| v.trim().parse::<usize>().expect("content-length"))
        .unwrap_or(0);
    while buf.len() < header_end + content_length {
        let n = stream.read(&mut chunk).await.expect("read body");
        if n == 0 {
            break;
        }
        buf.extend_from_slice(&chunk[..n]);
    }
    let body = &buf[header_end..header_end + content_length];
    let (path, query) = target.split_once('?').unwrap_or((target.as_str(), ""));
    // Like librespot's server: query parameters, then the form body.
    let mut params = BTreeMap::new();
    params.extend(form_urlencoded::parse(query.as_bytes()).into_owned());
    params.extend(form_urlencoded::parse(body).into_owned());
    let json = device.handle(&method, path, &params);
    let response = format!(
        "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{json}",
        json.len()
    );
    let _ = stream.write_all(response.as_bytes()).await;
    let _ = stream.shutdown().await;
}

/// Serves `device` on 127.0.0.1; returns its endpoint and the server task.
async fn serve(device: Arc<FakeDevice>) -> (Endpoint, tokio::task::JoinHandle<()>) {
    let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind");
    let port = listener.local_addr().expect("addr").port();
    let task = tokio::spawn(async move {
        while let Ok((stream, _)) = listener.accept().await {
            tokio::spawn(serve_one(stream, device.clone()));
        }
    });
    let endpoint = Endpoint::parse(&format!("http://127.0.0.1:{port}/zc"), None).expect("endpoint");
    (endpoint, task)
}

fn info_with(availability: &str, key: &str, token_type: &str) -> super::LocalDeviceInfo {
    let body = serde_json::json!({
        "deviceID": "d", "availability": availability, "publicKey": key, "tokenType": token_type
    })
    .to_string();
    super::info::parse_info(body.as_bytes()).expect("info")
}

#[test]
fn wake_up_only_for_unloaded_key_based_devices() {
    let good_key = BASE64.encode([0x42u8; 96]);
    assert!(needs_wake_up(&info_with("NOT-LOADED", INVALID_KEY_B64, "default")));
    assert!(needs_wake_up(&info_with("NOT-LOADED", "", "default")), "a missing key is as bad");
    assert!(!needs_wake_up(&info_with("NOT-LOADED", &good_key, "default")), "a usable key: just wait");
    assert!(!needs_wake_up(&info_with("", INVALID_KEY_B64, "default")), "loaded: nothing to wake");
    assert!(!needs_wake_up(&info_with("NOT-LOADED", INVALID_KEY_B64, "accesstoken")), "a token login wakes it");
}

#[test]
fn wake_up_form_carries_no_credentials() {
    let auth_data = b"secret-reusable-credentials";
    let account = account(auth_data);
    let info = info_with("NOT-LOADED", INVALID_KEY_B64, "default");
    let form: BTreeMap<String, String> = wake_up_form(&info, &account).into_iter().collect();
    assert_eq!(form["action"], "addUser");
    assert_eq!(form["blob"], "", "no blob");
    assert_eq!(form["clientKey"], "", "no key exchange");
    assert_eq!(form["tokenType"], "default");
    assert_eq!(form["userName"], "alice");
    assert_eq!(form["deviceName"], "Test Phone");
    assert_eq!(form["deviceId"], "phone-device-id");
    assert_eq!(form["version"], "2.7.1", "fallback version when the device reports none");
    let stored = BASE64.encode(auth_data);
    assert!(form.values().all(|v| !v.contains(&stored)), "credential material never in the wake-up");
}

#[tokio::test]
async fn logs_into_a_loaded_device_over_http() {
    let device = FakeDevice::new("default", false, 0);
    let (endpoint, server) = serve(device.clone()).await;
    let auth_data: Vec<u8> = (0..150u8).collect();
    let info = add_user_flow(&endpoint, Some(FAKE_DEVICE_ID), &account(&auth_data), &ZEROCONF_SOURCES, no_token).await.expect("login");
    server.abort();
    assert_eq!(info.device_id, FAKE_DEVICE_ID);
    let state = device.state.lock().unwrap();
    assert_eq!(state.actions, ["getInfo", "addUser"]);
    let creds = state.credentials.as_ref().expect("the device decoded credentials");
    assert_eq!(creds.username.as_deref(), Some("alice"));
    assert_eq!(creds.auth_type, AuthenticationType::AUTHENTICATION_STORED_SPOTIFY_CREDENTIALS);
    assert_eq!(creds.auth_data, auth_data);
    assert_eq!(state.add_users[0]["tokenType"], "default");
    assert_eq!(state.add_users[0]["version"], "2.9.0", "echoes the device's version");
}

#[tokio::test]
async fn wakes_a_not_loaded_device_then_logs_in() {
    // Unloaded until the first addUser, then still loading for one more getInfo.
    let device = FakeDevice::new("default", true, 1);
    let (endpoint, server) = serve(device.clone()).await;
    let info = add_user_flow(&endpoint, None, &account(b"reusable"), &ZEROCONF_SOURCES, no_token).await.expect("login");
    server.abort();
    assert!(!info.not_loaded());
    let state = device.state.lock().unwrap();
    // getInfo (NOT-LOADED) → wake-up addUser → getInfo (still loading) → getInfo (loaded) → addUser.
    assert_eq!(state.actions, ["getInfo", "addUser", "getInfo", "getInfo", "addUser"]);
    assert_eq!(state.add_users.len(), 2);
    let wake = &state.add_users[0];
    assert_eq!(wake["blob"], "", "the wake-up holds no credentials");
    assert_eq!(wake["clientKey"], "");
    assert_eq!(wake["deviceName"], "Test Phone");
    assert!(!state.add_users[1]["blob"].is_empty(), "the real addUser carries the blob");
    let creds = state.credentials.as_ref().expect("decoded after the wake-up");
    assert_eq!(creds.auth_data, b"reusable");
}

#[tokio::test]
async fn a_device_that_never_loads_gets_one_wake_up_and_a_clear_error() {
    let device = FakeDevice::new("default", true, u32::MAX);
    let (endpoint, server) = serve(device.clone()).await;
    let err = add_user_flow(&endpoint, None, &account(b"reusable"), &ZEROCONF_SOURCES, no_token).await.expect_err("never loads");
    server.abort();
    assert!(err.message.contains("public key"), "{}", err.message);
    let state = device.state.lock().unwrap();
    assert_eq!(state.add_users.len(), 1, "exactly one wake-up, never credentials to an unloaded device");
    assert_eq!(state.add_users[0]["blob"], "");
    assert!(state.actions.iter().filter(|a| *a == "getInfo").count() >= 3, "polled while loading");
}

#[tokio::test]
async fn sends_accesstoken_devices_a_token_for_their_own_client() {
    let device = FakeDevice::new("accesstoken", false, 0);
    let (endpoint, server) = serve(device.clone()).await;
    let mut account = account(b"unused");
    account.credentials = None; // a token login must not need stored credentials
    let calls = Arc::new(Mutex::new(Vec::new()));
    add_user_flow(&endpoint, None, &account, &ZEROCONF_SOURCES, recording_minter(&calls)).await.expect("login");
    server.abort();
    // One token, minted for the client id and device id the device's getInfo reported.
    assert_eq!(
        *calls.lock().unwrap(),
        [(TokenSource::DeviceAuth, FAKE_CLIENT_ID.to_string(), FAKE_DEVICE_ID.to_string())]
    );
    let state = device.state.lock().unwrap();
    assert_eq!(state.add_users.len(), 1);
    let form = &state.add_users[0];
    assert_eq!(form["tokenType"], "accesstoken");
    assert_eq!(form["blob"], format!("token-DeviceAuth-{FAKE_CLIENT_ID}-{FAKE_DEVICE_ID}"));
    assert_eq!(form["clientKey"], FAKE_CLIENT_ID, "the device's own client id");
    assert!(state.credentials.is_none());
}

fn blobs(device: &FakeDevice) -> Vec<String> {
    device.state.lock().unwrap().add_users.iter().map(|f| f["blob"].clone()).collect()
}

#[tokio::test]
async fn a_refused_token_falls_back_in_order() {
    // The device's client first (device-auth, then keymaster), this session's own token last.
    let device = FakeDevice::token_device(FAKE_CLIENT_ID, 2);
    let (endpoint, server) = serve(device.clone()).await;
    let calls = Arc::new(Mutex::new(Vec::new()));
    add_user_flow(&endpoint, None, &account(b"x"), &ZEROCONF_SOURCES, recording_minter(&calls)).await.expect("third token");
    server.abort();
    let sources: Vec<TokenSource> = calls.lock().unwrap().iter().map(|c| c.0).collect();
    assert_eq!(sources, [TokenSource::DeviceAuth, TokenSource::Keymaster, TokenSource::Session]);
    assert!(calls.lock().unwrap().iter().all(|c| c.1 == FAKE_CLIENT_ID && c.2 == FAKE_DEVICE_ID));
    assert_eq!(
        blobs(&device),
        [
            format!("token-DeviceAuth-{FAKE_CLIENT_ID}-{FAKE_DEVICE_ID}"),
            format!("token-Keymaster-{FAKE_CLIENT_ID}-{FAKE_DEVICE_ID}"),
            format!("token-Session-{FAKE_CLIENT_ID}-{FAKE_DEVICE_ID}"),
        ]
    );
}

#[tokio::test]
async fn a_device_refusing_every_token_is_a_refusal() {
    let device = FakeDevice::token_device(FAKE_CLIENT_ID, usize::MAX);
    let (endpoint, server) = serve(device.clone()).await;
    let err = add_user_flow(&endpoint, None, &account(b"x"), &ZEROCONF_SOURCES, token_for).await.expect_err("refused");
    server.abort();
    assert_eq!(err.code, ErrorCode::Unavailable);
    assert_eq!(err.message, "The device could not sign in to Spotify (ERROR-LOGIN-FAILED)");
    assert_eq!(blobs(&device).len(), 3, "one addUser per token source, then it gives up");
}

#[tokio::test]
async fn a_source_that_mints_nothing_is_skipped() {
    let device = FakeDevice::token_device(FAKE_CLIENT_ID, 0);
    let (endpoint, server) = serve(device.clone()).await;
    let minter = |source, client_id, device_id| async move {
        match source {
            TokenSource::DeviceAuth => Err(AppError::unavailable("device-auth refused")),
            _ => token_for(source, client_id, device_id).await,
        }
    };
    add_user_flow(&endpoint, None, &account(b"x"), &ZEROCONF_SOURCES, minter).await.expect("keymaster token");
    server.abort();
    assert_eq!(blobs(&device), [format!("token-Keymaster-{FAKE_CLIENT_ID}-{FAKE_DEVICE_ID}")], "no addUser without a token");
}

#[tokio::test]
async fn without_a_usable_client_id_the_session_token_is_sent() {
    for client_id in ["", "not a client id!"] {
        let device = FakeDevice::token_device(client_id, 0);
        let (endpoint, server) = serve(device.clone()).await;
        let calls = Arc::new(Mutex::new(Vec::new()));
        add_user_flow(&endpoint, None, &account(b"x"), &ZEROCONF_SOURCES, recording_minter(&calls)).await.expect("login");
        server.abort();
        let sources: Vec<TokenSource> = calls.lock().unwrap().iter().map(|c| c.0).collect();
        assert_eq!(sources, [TokenSource::Session], "{client_id:?}: no token for an unusable client");
        assert_eq!(blobs(&device), [format!("token-Session-{client_id}-{FAKE_DEVICE_ID}")], "{client_id:?}");
    }
}

#[tokio::test]
async fn refuses_a_different_device_at_the_address() {
    let device = FakeDevice::new("default", false, 0);
    let (endpoint, server) = serve(device.clone()).await;
    let err = add_user_flow(&endpoint, Some("someone-else"), &account(b"x"), &ZEROCONF_SOURCES, no_token).await.expect_err("mismatch");
    server.abort();
    assert!(err.message.contains("different device"), "{}", err.message);
    assert!(device.state.lock().unwrap().add_users.is_empty(), "nothing sent to the wrong device");
}
