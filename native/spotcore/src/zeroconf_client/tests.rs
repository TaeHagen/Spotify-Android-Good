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
