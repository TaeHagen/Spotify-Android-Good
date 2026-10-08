//! "Hide explicit content" (`EngineSettings::filter_explicit`), OR-ed into the session's own
//! `filter-explicit-content` user attribute (the account's parental setting).
//!
//! librespot reads that attribute wherever explicit content matters
//! (`Session::filter_explicit_content`): the Player refuses to load explicit tracks (Spirc then
//! skips them) and skips the current one when the filter turns on, and the catalog marks them
//! unplayable. Downloads ignore it (the Player still refuses a downloaded explicit track at play
//! time). Forcing it on the session is the single choke point.
//!
//! The account's own value is kept in a private attribute of the same session, so turning the
//! setting off restores it. ProductInfo and Spirc (server attribute pushes and mutations) can
//! overwrite the attribute; [`apply`] recognises a value it didn't write as the account's new
//! value. The supervisor re-applies it when declaring the session online and on every health
//! tick, and `player_host` whenever the Player reports the filter was turned off.

use librespot_core::Session;

const ATTRIBUTE: &str = "filter-explicit-content";
/// The value [`apply`] last wrote to [`ATTRIBUTE`] (local only, never sent anywhere).
const WRITTEN: &str = "spotifygood-filter-explicit-written";
/// The account's own value, remembered while [`ATTRIBUTE`] is forced.
const ACCOUNT: &str = "spotifygood-filter-explicit-account";

fn flag(value: Option<&str>) -> bool {
    value == Some("1")
}

/// The account's own filter (what Spotify says), not the app setting.
pub(crate) fn account_filter(session: &Session) -> bool {
    let current = session.get_user_attribute(ATTRIBUTE);
    if current != session.get_user_attribute(WRITTEN) {
        return flag(current.as_deref());
    }
    flag(session.get_user_attribute(ACCOUNT).as_deref())
}

/// Makes the session's filter `app_filter || account filter`. Returns the new effective value
/// when it changed (the caller then tells the Player), `None` when nothing changed.
pub(crate) fn apply(session: &Session, app_filter: bool) -> Option<bool> {
    let current = session.get_user_attribute(ATTRIBUTE);
    if current != session.get_user_attribute(WRITTEN) {
        // Not our write: the account's own value (ProductInfo, a server push), or still absent.
        session.set_user_attribute(ACCOUNT, if flag(current.as_deref()) { "1" } else { "0" });
    }
    let account = flag(session.get_user_attribute(ACCOUNT).as_deref());
    let want = app_filter || account;
    if flag(current.as_deref()) == want {
        return None;
    }
    let value = if want { "1" } else { "0" };
    session.set_user_attribute(ATTRIBUTE, value);
    session.set_user_attribute(WRITTEN, value);
    Some(want)
}

#[cfg(test)]
mod tests {
    use super::*;
    use librespot_core::SessionConfig;

    #[tokio::test]
    async fn ors_the_setting_into_the_account_filter() {
        let session = Session::new(SessionConfig::default(), None);
        // ProductInfo: the account doesn't filter.
        session.set_user_attribute(ATTRIBUTE, "0");
        assert_eq!(apply(&session, false), None);
        assert!(!session.filter_explicit_content());

        // The app setting forces it on, and off again restores the account's value.
        assert_eq!(apply(&session, true), Some(true));
        assert!(session.filter_explicit_content());
        assert!(!account_filter(&session), "the account still doesn't filter");
        assert_eq!(apply(&session, true), None, "idempotent");
        assert_eq!(apply(&session, false), Some(false));
        assert!(!session.filter_explicit_content());

        // A server push resets it while the setting is on: re-applied on the next call.
        apply(&session, true);
        session.set_user_attribute(ATTRIBUTE, "0");
        assert_eq!(apply(&session, true), Some(true));
        assert!(session.filter_explicit_content());

        // The account filters (parental control): the setting can never turn it off.
        let session = Session::new(SessionConfig::default(), None);
        session.set_user_attribute(ATTRIBUTE, "1");
        assert_eq!(apply(&session, false), None);
        assert_eq!(apply(&session, true), None);
        assert_eq!(apply(&session, false), None);
        assert!(session.filter_explicit_content() && account_filter(&session));

        // The account turns its filter on while the setting is off.
        let session = Session::new(SessionConfig::default(), None);
        session.set_user_attribute(ATTRIBUTE, "0");
        assert_eq!(apply(&session, true), Some(true));
        assert_eq!(apply(&session, false), Some(false));
        session.set_user_attribute(ATTRIBUTE, "1");
        assert_eq!(apply(&session, false), None);
        assert!(account_filter(&session));
    }

    #[tokio::test]
    async fn applies_before_product_info() {
        let session = Session::new(SessionConfig::default(), None);
        // A never-connected (offline playback) session has no attributes at all.
        assert_eq!(apply(&session, false), None);
        assert_eq!(apply(&session, true), Some(true));
        assert!(session.filter_explicit_content());
    }
}
