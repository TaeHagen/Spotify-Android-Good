//! TLS to a Cast device (port 8009), with rustls on the ring provider librespot already uses.
//!
//! Cast devices present a self-signed certificate that no CA vouches for (Google's own senders
//! authenticate the device separately, through the device-auth namespace, which we don't use).
//! So this connector accepts any server certificate. That policy lives in this module's private
//! [`client_config`] and is used for nothing but the socket opened by [`connect`]; every other
//! TLS connection in the app keeps librespot's normal verification.
//!
//! What TLS still gives here: encryption against anyone passively on the LAN. The handshake
//! signature is checked against the presented certificate's key when webpki can parse that
//! certificate. Since no identity is authenticated, a certificate it can't parse (an unusual
//! self-signed one) is accepted like any other rather than failing the device.

use super::channel::network;
use crate::error::{AppError, AppResult, ErrorCode};
use std::net::SocketAddr;
use std::sync::{Arc, OnceLock};
use std::time::Duration;
use tokio::net::TcpStream;
use tokio_rustls::client::TlsStream;
use tokio_rustls::rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use tokio_rustls::rustls::crypto::{self, CryptoProvider, WebPkiSupportedAlgorithms};
use tokio_rustls::rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use tokio_rustls::rustls::server::ParsedCertificate;
use tokio_rustls::rustls::{ClientConfig, DigitallySignedStruct, Error as TlsError, SignatureScheme};
use tokio_rustls::TlsConnector;

const CONNECT_TIMEOUT: Duration = Duration::from_secs(5);
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(5);

/// Accepts the device's self-signed certificate (see the module docs).
#[derive(Debug)]
struct CastDeviceCertificate {
    algorithms: WebPkiSupportedAlgorithms,
}

impl CastDeviceCertificate {
    fn parseable(cert: &CertificateDer<'_>) -> bool {
        ParsedCertificate::try_from(cert).is_ok()
    }
}

impl ServerCertVerifier for CastDeviceCertificate {
    fn verify_server_cert(
        &self,
        _end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp_response: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, TlsError> {
        Ok(ServerCertVerified::assertion())
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, TlsError> {
        if !Self::parseable(cert) {
            return Ok(HandshakeSignatureValid::assertion());
        }
        crypto::verify_tls12_signature(message, cert, dss, &self.algorithms)
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, TlsError> {
        if !Self::parseable(cert) {
            return Ok(HandshakeSignatureValid::assertion());
        }
        crypto::verify_tls13_signature(message, cert, dss, &self.algorithms)
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.algorithms.supported_schemes()
    }
}

/// The client configuration for Cast sockets only (built once). TLS 1.2 stays enabled: many
/// Cast devices don't speak 1.3.
fn client_config() -> AppResult<Arc<ClientConfig>> {
    static CONFIG: OnceLock<Result<Arc<ClientConfig>, String>> = OnceLock::new();
    CONFIG
        .get_or_init(|| {
            let provider: Arc<CryptoProvider> = Arc::new(crypto::ring::default_provider());
            let verifier = Arc::new(CastDeviceCertificate { algorithms: provider.signature_verification_algorithms });
            ClientConfig::builder_with_provider(provider)
                .with_safe_default_protocol_versions()
                .map(|builder| {
                    Arc::new(builder.dangerous().with_custom_certificate_verifier(verifier).with_no_client_auth())
                })
                .map_err(|e| e.to_string())
        })
        .clone()
        .map_err(|e| AppError::internal(format!("cast tls: {e}")))
}

/// Connects to the Cast device at `addr` and completes the TLS handshake, each step bounded.
pub(crate) async fn connect(addr: SocketAddr) -> AppResult<TlsStream<TcpStream>> {
    let tcp = tokio::time::timeout(CONNECT_TIMEOUT, TcpStream::connect(addr))
        .await
        .map_err(|_| AppError::new(ErrorCode::Network, "Timed out connecting to the device"))?
        .map_err(network)?;
    let _ = tcp.set_nodelay(true);
    let connector = TlsConnector::from(client_config()?);
    // An IP address: rustls sends no SNI for it.
    let name = ServerName::IpAddress(addr.ip().into());
    tokio::time::timeout(HANDSHAKE_TIMEOUT, connector.connect(name, tcp))
        .await
        .map_err(|_| AppError::new(ErrorCode::Network, "Timed out connecting to the device"))?
        .map_err(network)
}
