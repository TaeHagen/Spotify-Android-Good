use librespot_core::error::ErrorKind;
use serde::Serialize;
use std::fmt;

/// Error codes shared with Kotlin (`NativeErrorCode`).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ErrorCode {
    NotLoggedIn,
    NotConnected,
    BadCredentials,
    PremiumRequired,
    Network,
    NotFound,
    RateLimited,
    InvalidArgument,
    Unavailable,
    NotActiveDevice,
    PlaybackRefused,
    Cancelled,
    Internal,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AppError {
    pub code: ErrorCode,
    pub message: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub retry_after_ms: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub context: Option<String>,
}

pub type AppResult<T> = Result<T, AppError>;

impl AppError {
    pub fn new(code: ErrorCode, message: impl Into<String>) -> Self {
        Self { code, message: message.into(), retry_after_ms: None, context: None }
    }
    pub fn invalid(message: impl Into<String>) -> Self {
        Self::new(ErrorCode::InvalidArgument, message)
    }
    pub fn internal(message: impl Into<String>) -> Self {
        Self::new(ErrorCode::Internal, message)
    }
    pub fn not_connected() -> Self {
        Self::new(ErrorCode::NotConnected, "Not connected to Spotify")
    }
    pub fn not_found(message: impl Into<String>) -> Self {
        Self::new(ErrorCode::NotFound, message)
    }
    pub fn unavailable(message: impl Into<String>) -> Self {
        Self::new(ErrorCode::Unavailable, message)
    }
    pub fn cancelled() -> Self {
        Self::new(ErrorCode::Cancelled, "Cancelled")
    }
    pub fn with_context(mut self, context: impl Into<String>) -> Self {
        self.context = Some(context.into());
        self
    }
    pub fn to_json(&self) -> String {
        serde_json::to_string(self).unwrap_or_else(|_| r#"{"code":"INTERNAL","message":"serialization"}"#.into())
    }
}

impl fmt::Display for AppError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{:?}: {}", self.code, self.message)
    }
}

impl std::error::Error for AppError {}

impl From<librespot_core::Error> for AppError {
    fn from(e: librespot_core::Error) -> Self {
        let msg = e.to_string();
        // AP login errors live in a private module; classify by message (see research/core.md §2.4).
        // They are the only source of BAD_CREDENTIALS, which makes Kotlin delete the stored
        // credentials.
        let code = match e.kind {
            ErrorKind::PermissionDenied if is_rejected_credentials(&msg) => ErrorCode::BadCredentials,
            ErrorKind::PermissionDenied if msg.contains("Premium account required") => ErrorCode::PremiumRequired,
            ErrorKind::NotFound => ErrorCode::NotFound,
            ErrorKind::ResourceExhausted => ErrorCode::RateLimited,
            ErrorKind::InvalidArgument | ErrorKind::OutOfRange => ErrorCode::InvalidArgument,
            // `Unauthenticated` is an HTTP 401/407/511 from any request (client-token, login5,
            // apresolve, spclient): a rejected bearer token or proxy authentication, not the
            // account credentials. It is retryable.
            ErrorKind::Unavailable | ErrorKind::DeadlineExceeded | ErrorKind::Aborted | ErrorKind::Unauthenticated => {
                ErrorCode::Network
            }
            ErrorKind::Cancelled => ErrorCode::Cancelled,
            ErrorKind::PermissionDenied | ErrorKind::FailedPrecondition => ErrorCode::Unavailable,
            _ => ErrorCode::Internal,
        };
        AppError::new(code, msg)
    }
}

/// An AP login refusal of the credentials themselves (`AuthenticationError::LoginFailed`).
pub(crate) fn is_rejected_credentials(msg: &str) -> bool {
    msg.contains("Bad credentials") || msg.contains("Could not validate credentials")
}

impl From<serde_json::Error> for AppError {
    fn from(e: serde_json::Error) -> Self {
        AppError::invalid(format!("bad JSON: {e}"))
    }
}

impl From<protobuf::Error> for AppError {
    fn from(e: protobuf::Error) -> Self {
        AppError::internal(format!("protobuf: {e}"))
    }
}

impl From<std::io::Error> for AppError {
    fn from(e: std::io::Error) -> Self {
        AppError::internal(format!("io: {e}"))
    }
}

impl From<tokio::time::error::Elapsed> for AppError {
    fn from(_: tokio::time::error::Elapsed) -> Self {
        AppError::new(ErrorCode::Network, "Timed out")
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use librespot_core::Error;

    fn code(e: Error) -> ErrorCode {
        AppError::from(e).code
    }

    #[test]
    fn http_auth_failures_are_retryable() {
        // HTTP 401/407/511 (rejected token, proxy authentication) never mean bad credentials.
        assert_eq!(code(Error::unauthenticated("Upstream responded with status code 401")), ErrorCode::Network);
    }

    #[test]
    fn ap_login_refusals() {
        assert_eq!(code(Error::permission_denied("Login failed with reason: Bad credentials")), ErrorCode::BadCredentials);
        assert_eq!(
            code(Error::permission_denied("Login failed with reason: Could not validate credentials")),
            ErrorCode::BadCredentials
        );
        assert_eq!(
            code(Error::permission_denied("Login failed with reason: Premium account required")),
            ErrorCode::PremiumRequired
        );
        assert_eq!(code(Error::permission_denied("audio key error 0x0001")), ErrorCode::Unavailable);
    }

    #[test]
    fn other_kinds() {
        assert_eq!(code(Error::unavailable("x")), ErrorCode::Network);
        assert_eq!(code(Error::deadline_exceeded("x")), ErrorCode::Network);
        assert_eq!(code(Error::not_found("x")), ErrorCode::NotFound);
        assert_eq!(code(Error::resource_exhausted("x")), ErrorCode::RateLimited);
        assert_eq!(code(Error::invalid_argument("x")), ErrorCode::InvalidArgument);
        assert_eq!(code(Error::cancelled("x")), ErrorCode::Cancelled);
        assert_eq!(code(Error::internal("x")), ErrorCode::Internal);
    }
}
