//! The Cast exchange against a fake receiver over real TLS on 127.0.0.1: a throwaway self-signed
//! certificate (`testdata/`, generated for these tests only), Cast v2 framing, the connection
//! and heartbeat namespaces, the receiver's LAUNCH / RECEIVER_STATUS and the Spotify
//! namespace's getInfo / addUser. The client side is the production path: [`tls::connect`]
//! (which must accept the self-signed certificate) and [`login_flow`].

use super::channel::{NS_CONNECTION, NS_HEARTBEAT, NS_RECEIVER};
use super::frame::{take_frame, CastMessage};
use crate::device_token::TokenSource;
use super::*;
use crate::error::ErrorCode;
use bytes::BytesMut;
use std::sync::{Arc, Mutex};
use std::time::Instant;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::Notify;
use tokio_rustls::rustls::pki_types::pem::PemObject;
use tokio_rustls::rustls::pki_types::{CertificateDer, PrivateKeyDer};
use tokio_rustls::rustls::{self, ServerConfig};
use tokio_rustls::server::TlsStream;
use tokio_rustls::TlsAcceptor;

const CERT_PEM: &[u8] = include_bytes!("testdata/cert.pem");
const KEY_PEM: &[u8] = include_bytes!("testdata/key.pem");
const TRANSPORT: &str = "web-42";
const RECEIVER_CLIENT_ID: &str = "d7df0887fb71494ea994202cb473eae7";
const NAME: &str = "Living Room speaker";
/// md5("Living Room speaker"), also the Kotlin test's vector.
const NAME_ID: &str = "3c31e63dda8e146428f7b25084e98915";

const FAST: Timeouts = Timeouts {
    launch: Duration::from_millis(600),
    message: Duration::from_millis(600),
    add_user: Duration::from_millis(600),
    token: Duration::from_millis(600),
};
const BOTH: [TokenSource; 2] = [TokenSource::DeviceAuth, TokenSource::Keymaster];

#[derive(Debug, Clone, Copy, Default, PartialEq)]
enum Malformed {
    #[default]
    No,
    /// A length prefix above the 64 KiB limit.
    Length,
    /// A frame whose body is not a CastMessage.
    Body,
}

/// How the fake receiver behaves.
#[derive(Debug, Clone, Copy, Default)]
struct Script {
    /// Answers LAUNCH only with statuses that never list the Spotify app.
    never_launch: bool,
    /// Answers LAUNCH with LAUNCH_ERROR.
    launch_error: bool,
    /// Sends a CLOSE on the platform connection instead of answering LAUNCH.
    close_on_launch: bool,
    /// Sends a malformed frame instead of answering LAUNCH.
    malformed: Malformed,
    /// Never sends anything (not even a heartbeat).
    silent: bool,
    /// The first this many addUser get an addUserError.
    refuse_add_users: usize,
    /// getInfoResponse reports this deviceID (else the one it was given).
    reported_device_id: Option<&'static str>,
    /// Only TLS 1.2 (many Cast devices).
    tls12_only: bool,
}

#[derive(Debug, Default)]
struct Seen {
    /// (namespace, destination, payload) of every message from the client.
    messages: Vec<(String, String, Value)>,
    pongs: usize,
    /// The client ended the connection (EOF / close_notify).
    closed: bool,
}

impl Seen {
    fn of_type(&self, kind: &str) -> Vec<&(String, String, Value)> {
        self.messages.iter().filter(|(_, _, v)| v["type"] == kind).collect()
    }
}

struct Fake {
    script: Script,
    seen: Mutex<Seen>,
    /// Notified on every PONG (the token minter waits for one during its run).
    pong: Notify,
}

fn server_config(tls12_only: bool) -> Arc<ServerConfig> {
    let certs = vec![CertificateDer::from_pem_slice(CERT_PEM).expect("cert")];
    let key = PrivateKeyDer::from_pem_slice(KEY_PEM).expect("key");
    let provider = Arc::new(rustls::crypto::ring::default_provider());
    let builder = ServerConfig::builder_with_provider(provider);
    let builder = if tls12_only {
        builder.with_protocol_versions(&[&rustls::version::TLS12])
    } else {
        builder.with_safe_default_protocol_versions()
    }
    .expect("versions");
    Arc::new(builder.with_no_client_auth().with_single_cert(certs, key).expect("server config"))
}

async fn write(stream: &mut TlsStream<TcpStream>, source: &str, namespace: &str, payload: Value) {
    let frame = CastMessage::text(source, SENDER_ID_FOR_TESTS, namespace, payload.to_string()).to_frame();
    let _ = stream.write_all(&frame).await;
    let _ = stream.flush().await;
}

const SENDER_ID_FOR_TESTS: &str = "sender-0";

impl Fake {
    async fn serve(self: Arc<Self>, mut stream: TlsStream<TcpStream>) {
        let script = self.script;
        if !script.silent {
            // The device's heartbeat starts at once.
            write(&mut stream, RECEIVER_ID, NS_HEARTBEAT, json!({ "type": "PING" })).await;
        }
        let mut buf = BytesMut::new();
        let mut refused = 0;
        loop {
            let msg = loop {
                match take_frame(&mut buf) {
                    Ok(Some(msg)) => break msg,
                    Ok(None) => {}
                    Err(e) => panic!("the client sent a malformed frame: {e}"),
                }
                match stream.read_buf(&mut buf).await {
                    Ok(0) | Err(_) => {
                        self.seen.lock().unwrap().closed = true;
                        return;
                    }
                    Ok(_) => {}
                }
            };
            assert_eq!(msg.source_id, "sender-0");
            let payload: Value = serde_json::from_str(msg.payload_utf8.as_deref().expect("text payload")).expect("json");
            let kind = payload["type"].as_str().unwrap_or_default().to_string();
            self.seen.lock().unwrap().messages.push((msg.namespace.clone(), msg.destination_id.clone(), payload.clone()));
            if script.silent {
                continue;
            }
            match (msg.namespace.as_str(), kind.as_str()) {
                (NS_HEARTBEAT, "PONG") => {
                    self.seen.lock().unwrap().pongs += 1;
                    self.pong.notify_waiters();
                }
                (NS_HEARTBEAT, "PING") => write(&mut stream, RECEIVER_ID, NS_HEARTBEAT, json!({ "type": "PONG" })).await,
                (NS_CONNECTION, "CONNECT") => {}
                (NS_RECEIVER, "LAUNCH") => {
                    assert_eq!(payload["appId"], SPOTIFY_APP_ID);
                    let request_id = payload["requestId"].clone();
                    if script.malformed == Malformed::Length {
                        let _ = stream.write_all(&(70_000u32).to_be_bytes()).await;
                        let _ = stream.flush().await;
                        continue;
                    }
                    if script.malformed == Malformed::Body {
                        let garbage = b"<html>not a cast message</html>";
                        let _ = stream.write_all(&(garbage.len() as u32).to_be_bytes()).await;
                        let _ = stream.write_all(garbage).await;
                        let _ = stream.flush().await;
                        continue;
                    }
                    if script.close_on_launch {
                        write(&mut stream, RECEIVER_ID, NS_CONNECTION, json!({ "type": "CLOSE" })).await;
                        continue;
                    }
                    if script.launch_error {
                        let error = json!({ "type": "LAUNCH_ERROR", "requestId": request_id, "reason": "NOT_FOUND" });
                        write(&mut stream, RECEIVER_ID, NS_RECEIVER, error).await;
                        continue;
                    }
                    // A status while the app is still starting (the backdrop runs), then, unless
                    // it never launches, the Spotify app with its transport.
                    let starting = json!({ "type": "RECEIVER_STATUS", "requestId": 0, "status": {
                        "applications": [{ "appId": "E8C28D3C", "displayName": "Backdrop", "transportId": "web-1" }],
                        "volume": { "level": 0.5, "muted": false } } });
                    write(&mut stream, RECEIVER_ID, NS_RECEIVER, starting).await;
                    if !script.never_launch {
                        let running = json!({ "type": "RECEIVER_STATUS", "requestId": request_id, "status": {
                            "applications": [{ "appId": SPOTIFY_APP_ID, "displayName": "Spotify", "sessionId": TRANSPORT,
                                "transportId": TRANSPORT, "namespaces": [{ "name": NS_SPOTIFY }] }] } });
                        write(&mut stream, RECEIVER_ID, NS_RECEIVER, running).await;
                    }
                }
                (NS_SPOTIFY, "getInfo") => {
                    assert_eq!(msg.destination_id, TRANSPORT, "getInfo goes to the app's transport");
                    let device_id = script
                        .reported_device_id
                        .map(str::to_string)
                        .unwrap_or_else(|| payload["payload"]["deviceID"].as_str().unwrap_or_default().to_string());
                    let reply = json!({ "type": "getInfoResponse", "payload": {
                        "deviceID": device_id, "clientID": RECEIVER_CLIENT_ID, "remoteName": payload["payload"]["remoteName"],
                        "deviceType": "cast_audio", "tokenType": "accesstoken", "version": "2.9.0" } });
                    write(&mut stream, TRANSPORT, NS_SPOTIFY, reply).await;
                    // The heartbeat goes on while the sender mints the token (which has started
                    // by then).
                    tokio::time::sleep(Duration::from_millis(100)).await;
                    write(&mut stream, RECEIVER_ID, NS_HEARTBEAT, json!({ "type": "PING" })).await;
                }
                (NS_SPOTIFY, "addUser") => {
                    assert_eq!(msg.destination_id, TRANSPORT);
                    let reply = if refused < script.refuse_add_users {
                        refused += 1;
                        json!({ "type": "addUserError", "payload": { "status": 102, "statusString": "ERROR-INVALID-TOKEN" } })
                    } else {
                        json!({ "type": "addUserResponse", "payload": { "status": 101, "statusString": "OK" } })
                    };
                    write(&mut stream, TRANSPORT, NS_SPOTIFY, reply).await;
                }
                _ => {}
            }
        }
    }
}

/// Runs one sign-in against a fake receiver with `script`; returns the result and what the
/// receiver saw (after the client closed the connection).
async fn run<M, F>(script: Script, sources: &[TokenSource], mint: M) -> (AppResult<ReceiverInfo>, Arc<Fake>)
where
    M: Fn(TokenSource, String, String, Arc<Fake>) -> F,
    F: Future<Output = AppResult<String>>,
{
    let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind");
    let port = listener.local_addr().expect("addr").port();
    let acceptor = TlsAcceptor::from(server_config(script.tls12_only));
    let fake = Arc::new(Fake { script, seen: Mutex::new(Seen::default()), pong: Notify::new() });
    let server_fake = fake.clone();
    let server = tokio::spawn(async move {
        let (tcp, _) = listener.accept().await.expect("accept");
        let tls = acceptor.accept(tcp).await.expect("tls accept");
        server_fake.serve(tls).await;
    });

    let addr = socket_addr("127.0.0.1", port, None).expect("addr");
    let identity = Identity::new(NAME, false);
    let minter_fake = fake.clone();
    let result = async {
        let stream = tls::connect(addr).await?;
        login_flow(stream, &identity, sources, FAST, |s, c, d| mint(s, c, d, minter_fake.clone())).await
    }
    .await;
    tokio::time::timeout(Duration::from_secs(5), server).await.expect("the receiver saw the connection end").expect("server");
    (result, fake)
}

async fn token_for(source: TokenSource, client_id: String, device_id: String, _fake: Arc<Fake>) -> AppResult<String> {
    Ok(format!("token-{source:?}-{client_id}-{device_id}"))
}

#[test]
fn connect_device_id_is_the_md5_of_the_name() {
    assert_eq!(connect_device_id(NAME), NAME_ID);
    assert_eq!(connect_device_id("Küche"), "9f1d6780e10dc626a9777fb3f8513c94", "UTF-8 bytes");
    assert_eq!(Identity::new("  Living Room speaker ", true).device_id, NAME_ID, "trimmed");
}

#[test]
fn only_local_ip_literals() {
    assert_eq!(socket_addr("192.168.1.30", 8009, None).expect("v4").to_string(), "192.168.1.30:8009");
    assert!(socket_addr("10.0.0.2", 32187, None).is_ok(), "a group's own port");
    assert!(socket_addr("[fd00::5]", 8009, None).is_ok(), "brackets are accepted");
    assert!(socket_addr("fd00::5", 8009, None).is_ok());
    match socket_addr("fe80::1", 8009, Some(4)).expect("scoped link-local") {
        SocketAddr::V6(v6) => assert_eq!(v6.scope_id(), 4),
        other => panic!("expected IPv6, got {other}"),
    }
    let unscoped = socket_addr("fe80::1", 8009, None).expect_err("needs a scope");
    assert!(unscoped.message.contains("scopeId"), "{}", unscoped.message);
    assert!(socket_addr("fe80::1", 8009, Some(0)).is_err());
    for bad in ["8.8.8.8", "2001:db8::1", "speaker.local", "chromecast", "", "192.168.1.30:8009"] {
        assert_eq!(socket_addr(bad, 8009, None).expect_err(bad).code, ErrorCode::InvalidArgument, "{bad}");
    }
    assert!(socket_addr("192.168.1.30", 0, None).is_err());
}

#[tokio::test]
async fn signs_the_receiver_in() {
    let started = Instant::now();
    let minted = Arc::new(Mutex::new(Vec::new()));
    let calls = minted.clone();
    let (result, fake) = run(Script::default(), &BOTH, move |source, client_id, device_id, fake| {
        let calls = calls.clone();
        async move {
            calls.lock().unwrap().push((source, client_id.clone(), device_id.clone()));
            // The minter waits for the PING the receiver sends after getInfoResponse to be
            // answered: the channel is served while a token is minted.
            let answered = async {
                loop {
                    let next = fake.pong.notified();
                    if fake.seen.lock().unwrap().pongs >= 2 {
                        return;
                    }
                    next.await;
                }
            };
            tokio::time::timeout(Duration::from_millis(500), answered)
                .await
                .map_err(|_| AppError::internal("no PONG while minting"))?;
            token_for(source, client_id, device_id, fake).await
        }
    })
    .await;
    let info = result.expect("signed in");
    assert!(started.elapsed() < FAST.launch, "no step waited for a timeout");
    assert_eq!(info, ReceiverInfo { device_id: NAME_ID.into(), client_id: RECEIVER_CLIENT_ID.into() });
    assert_eq!(
        *minted.lock().unwrap(),
        [(TokenSource::DeviceAuth, RECEIVER_CLIENT_ID.to_string(), NAME_ID.to_string())],
        "one token, for the receiver's client id"
    );

    let seen = fake.seen.lock().unwrap();
    let flow: Vec<(&str, &str, &str)> = seen
        .messages
        .iter()
        .filter(|(ns, _, _)| ns != NS_HEARTBEAT)
        .map(|(ns, dest, v)| (ns.as_str(), dest.as_str(), v["type"].as_str().unwrap_or_default()))
        .collect();
    assert_eq!(
        flow,
        [
            (NS_CONNECTION, "receiver-0", "CONNECT"),
            (NS_RECEIVER, "receiver-0", "LAUNCH"),
            (NS_CONNECTION, TRANSPORT, "CONNECT"),
            (NS_SPOTIFY, TRANSPORT, "getInfo"),
            (NS_SPOTIFY, TRANSPORT, "addUser"),
        ]
    );
    assert!(seen.pongs >= 2, "both PINGs answered (before the launch and while minting): {}", seen.pongs);
    let get_info = &seen.of_type("getInfo")[0].2["payload"];
    assert_eq!(get_info["remoteName"], NAME);
    assert_eq!(get_info["deviceID"], NAME_ID);
    assert_eq!(get_info["deviceAPI_isGroup"], false);
    let add_user = &seen.of_type("addUser")[0].2["payload"];
    assert_eq!(add_user["tokenType"], "accesstoken");
    assert_eq!(add_user["blob"], format!("token-DeviceAuth-{RECEIVER_CLIENT_ID}-{NAME_ID}"));
    assert!(seen.of_type("CLOSE").is_empty(), "no CLOSE: the receiver must not stop when we leave");
    assert!(seen.closed, "the socket is closed after the exchange");
}

#[tokio::test]
async fn speaks_tls_1_2_and_uses_the_receivers_own_device_id() {
    let script = Script { tls12_only: true, reported_device_id: Some("receiver-chosen-id"), ..Script::default() };
    let (result, fake) = run(script, &BOTH, token_for).await;
    let info = result.expect("signed in over TLS 1.2");
    assert_eq!(info.device_id, "receiver-chosen-id");
    let seen = fake.seen.lock().unwrap();
    assert_eq!(seen.of_type("addUser")[0].2["payload"]["blob"], format!("token-DeviceAuth-{RECEIVER_CLIENT_ID}-receiver-chosen-id"));
}

#[tokio::test]
async fn a_refused_token_falls_back_to_the_next_source() {
    let script = Script { refuse_add_users: 1, ..Script::default() };
    let (result, fake) = run(script, &BOTH, token_for).await;
    result.expect("the second token is accepted");
    let seen = fake.seen.lock().unwrap();
    let blobs: Vec<&Value> = seen.of_type("addUser").iter().map(|(_, _, v)| &v["payload"]["blob"]).collect();
    assert_eq!(blobs.len(), 2);
    assert!(blobs[0].as_str().unwrap().starts_with("token-DeviceAuth-"));
    assert!(blobs[1].as_str().unwrap().starts_with("token-Keymaster-"));
}

#[tokio::test]
async fn a_refused_add_user_is_reported_like_a_zeroconf_refusal() {
    let script = Script { refuse_add_users: usize::MAX, ..Script::default() };
    let (result, fake) = run(script, &BOTH, token_for).await;
    let err = result.expect_err("refused");
    assert_eq!(err.code, ErrorCode::Unavailable);
    assert_eq!(err.message, "The device refused the login (ERROR-INVALID-TOKEN)");
    let seen = fake.seen.lock().unwrap();
    assert_eq!(seen.of_type("addUser").len(), 2, "one attempt per token source, then it gives up");
    assert!(seen.closed);
}

#[tokio::test]
async fn a_failed_mint_tries_the_next_source() {
    let (result, fake) = run(Script::default(), &BOTH, |source, client_id, device_id, fake| async move {
        match source {
            TokenSource::DeviceAuth => Err(AppError::unavailable("device-auth refused")),
            _ => token_for(source, client_id, device_id, fake).await,
        }
    })
    .await;
    result.expect("keymaster token");
    {
        let seen = fake.seen.lock().unwrap();
        assert_eq!(seen.of_type("addUser").len(), 1, "no addUser without a token");
        assert!(seen.of_type("addUser")[0].2["payload"]["blob"].as_str().unwrap().starts_with("token-Keymaster-"));
    }

    // No token at all: the mint error is the result, and nothing was sent to the receiver.
    let (result, fake) = run(Script::default(), &BOTH, |_, _, _, _| async { Err(AppError::unavailable("no token")) }).await;
    assert_eq!(result.expect_err("no token").message, "no token");
    assert!(fake.seen.lock().unwrap().of_type("addUser").is_empty());
}

#[tokio::test]
async fn a_receiver_that_never_launches_times_out() {
    let started = Instant::now();
    let script = Script { never_launch: true, ..Script::default() };
    let (result, fake) = run(script, &BOTH, token_for).await;
    let err = result.expect_err("never launched");
    let elapsed = started.elapsed();
    assert!(elapsed >= FAST.launch && elapsed < FAST.launch * 3, "bounded by the launch timeout: {elapsed:?}");
    // The device answered (statuses, heartbeat): not a Wi-Fi problem.
    assert_eq!(err.code, ErrorCode::Unavailable);
    assert_eq!(err.message, "Spotify did not start on the device");
    let seen = fake.seen.lock().unwrap();
    assert!(seen.of_type("getInfo").is_empty() && seen.of_type("addUser").is_empty());
    assert!(seen.closed, "the socket is closed after the timeout");
}

#[tokio::test]
async fn a_launch_error_is_reported() {
    let (result, _) = run(Script { launch_error: true, ..Script::default() }, &BOTH, token_for).await;
    let err = result.expect_err("launch error");
    assert_eq!(err.code, ErrorCode::Unavailable);
    assert_eq!(err.message, "The device could not start Spotify (NOT_FOUND)");
}

#[tokio::test]
async fn a_silent_device_is_a_network_timeout() {
    let (result, fake) = run(Script { silent: true, ..Script::default() }, &BOTH, token_for).await;
    let err = result.expect_err("silent");
    assert_eq!(err.code, ErrorCode::Network, "nothing heard: like a ZeroConf device that doesn't answer");
    assert_eq!(err.message, "The device did not answer in time");
    assert!(fake.seen.lock().unwrap().closed);
}

#[tokio::test]
async fn malformed_frames_end_the_exchange_at_once() {
    for malformed in [Malformed::Length, Malformed::Body] {
        let started = Instant::now();
        let (result, fake) = run(Script { malformed, ..Script::default() }, &BOTH, token_for).await;
        let err = result.expect_err("malformed");
        assert!(started.elapsed() < FAST.launch, "{malformed:?}: no timeout waited");
        assert_eq!(err.code, ErrorCode::Unavailable, "{malformed:?}");
        assert_eq!(err.message, "The device did not answer like a Google Cast device", "{malformed:?}");
        assert!(fake.seen.lock().unwrap().closed, "{malformed:?}");
    }
}

#[tokio::test]
async fn a_closed_connection_ends_the_exchange() {
    let (result, _) = run(Script { close_on_launch: true, ..Script::default() }, &BOTH, token_for).await;
    let err = result.expect_err("closed");
    assert_eq!(err.code, ErrorCode::Unavailable);
    assert_eq!(err.message, "The device closed the connection");
}

#[tokio::test]
async fn nothing_listening_is_a_network_error() {
    let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind");
    let port = listener.local_addr().expect("addr").port();
    drop(listener);
    let err = tls::connect(socket_addr("127.0.0.1", port, None).expect("addr")).await.expect_err("refused");
    assert_eq!(err.code, ErrorCode::Network);
}

#[test]
fn launch_statuses() {
    let status = |apps: Value| Inbound {
        source: RECEIVER_ID.into(),
        namespace: NS_RECEIVER.into(),
        payload: json!({ "type": "RECEIVER_STATUS", "requestId": 0, "status": { "applications": apps } }),
    };
    assert!(launch_reply(&status(json!([])), 1).is_none(), "nothing running yet");
    assert!(launch_reply(&status(json!([{ "appId": SPOTIFY_APP_ID }])), 1).is_none(), "no transport yet");
    let running = status(json!([{ "appId": "cc32e753", "transportId": "t-1" }]));
    assert_eq!(launch_reply(&running, 1).expect("running").expect("transport"), "t-1");
    let other = Inbound { payload: json!({ "type": "LAUNCH_ERROR", "requestId": 7 }), ..status(json!([])) };
    assert!(launch_reply(&other, 1).is_none(), "another request's error");
    let heartbeat = Inbound { namespace: NS_HEARTBEAT.into(), ..running };
    assert!(launch_reply(&heartbeat, 1).is_none());
}
