//! "Hide explicit content" (`EngineSettings::filter_explicit`), OR-ed into the session's own
//! `filter-explicit-content` user attribute (the account's parental setting).
//!
//! librespot reads the effective value wherever explicit content matters
//! (`Session::filter_explicit_content`): the Player refuses to load explicit tracks (Spirc then
//! skips them) and skips the current one when the filter turns on, and the catalog marks them
//! unplayable. Downloads ignore the app setting (the Player still refuses a downloaded explicit
//! track at play time) but not the account's own filter ([`account_filter`]): explicit items are
//! not downloaded for such an account. The session is the single choke point.
//!
//! The setting is the session's forced flag (the vendored `Session::set_filter_explicit_forced`),
//! never written into the attribute: the attribute stays the account's own value, whatever
//! ProductInfo (which replaces all attributes), Spirc's attribute updates and its mutations
//! (which flip the local value) do, and none of them can lift the setting.
//!
//! A session no server talks to (the offline one, and one whose connect attempt hasn't reached
//! ProductInfo yet) gets the account's value as last reported online ([`seed`],
//! `EngineSettings::account_filter_explicit`, persisted by Kotlin), so a filtered account's
//! downloads don't play offline or during a reconnect either.

use librespot_core::Session;

const ATTRIBUTE: &str = "filter-explicit-content";

/// The account's own filter (what Spotify says), not the app setting.
pub(crate) fn account_filter(session: &Session) -> bool {
    session.get_user_attribute(ATTRIBUTE).as_deref() == Some("1")
}

/// Makes the session's filter `app_filter || account filter`. Returns the new effective value
/// when it changed (the caller then tells the Player), `None` when nothing changed.
pub(crate) fn apply(session: &Session, app_filter: bool) -> Option<bool> {
    let before = session.filter_explicit_content();
    session.set_filter_explicit_forced(app_filter);
    changed(session, before)
}

/// For a session no server talks to yet: takes [`account`] as the account's own value, then
/// applies [`app_filter`]. ProductInfo replaces the account's value once the session connects.
/// Returns the new effective value when it changed.
pub(crate) fn seed(session: &Session, account: bool, app_filter: bool) -> Option<bool> {
    let before = session.filter_explicit_content();
    session.set_user_attribute(ATTRIBUTE, if account { "1" } else { "0" });
    session.set_filter_explicit_forced(app_filter);
    changed(session, before)
}

fn changed(session: &Session, before: bool) -> Option<bool> {
    let now = session.filter_explicit_content();
    (now != before).then_some(now)
}

#[cfg(test)]
mod tests {
    use super::*;
    use librespot_core::SessionConfig;

    /// What Spirc does with an attribute mutation: flips the local value.
    fn mutate(session: &Session) {
        let flipped = if account_filter(session) { "0" } else { "1" };
        session.set_user_attribute(ATTRIBUTE, flipped);
    }

    /// The account's attributes from the server (ProductInfo, which replaces them all, or an
    /// attributes update).
    fn product_info(session: &Session, filter: &str) {
        let mut attributes = std::collections::HashMap::new();
        attributes.insert(ATTRIBUTE.to_owned(), filter.to_owned());
        attributes.insert("type".to_owned(), "premium".to_owned());
        session.set_user_attributes(attributes);
    }

    #[tokio::test]
    async fn ors_the_setting_into_the_account_filter() {
        let session = Session::new(SessionConfig::default(), None);
        // ProductInfo: the account doesn't filter.
        product_info(&session, "0");
        assert_eq!(apply(&session, false), None);
        assert!(!session.filter_explicit_content());

        // The app setting forces it on, and off again restores the account's value.
        assert_eq!(apply(&session, true), Some(true));
        assert!(session.filter_explicit_content());
        assert!(!account_filter(&session), "the account still doesn't filter");
        assert_eq!(apply(&session, true), None, "idempotent");
        assert_eq!(apply(&session, false), Some(false));
        assert!(!session.filter_explicit_content());

        // A server push of the attributes can't lift the setting.
        apply(&session, true);
        product_info(&session, "0");
        assert!(session.filter_explicit_content());
        assert_eq!(apply(&session, true), None);

        // The account filters (parental control): the setting can never turn it off.
        let session = Session::new(SessionConfig::default(), None);
        product_info(&session, "1");
        assert_eq!(apply(&session, false), None);
        assert_eq!(apply(&session, true), None);
        assert_eq!(apply(&session, false), None);
        assert!(session.filter_explicit_content() && account_filter(&session));
    }

    #[tokio::test]
    async fn a_mutation_while_the_setting_is_on_changes_the_account_value() {
        // "Hide explicit content" on, the account allows explicit content.
        let session = Session::new(SessionConfig::default(), None);
        product_info(&session, "0");
        apply(&session, true);
        // A Family manager turns "Allow explicit content" off: Spirc flips the local value.
        mutate(&session);
        assert!(account_filter(&session), "the account filters now");
        // Turning the setting off doesn't lift the account's filter.
        assert_eq!(apply(&session, false), None);
        assert!(session.filter_explicit_content());
        // Allowed again (another mutation): the setting off now lets explicit content through.
        mutate(&session);
        assert!(!account_filter(&session));
        assert!(!session.filter_explicit_content());
        // Also when the mutation meets the setting on: still filtered, account value right.
        apply(&session, true);
        mutate(&session);
        mutate(&session);
        assert!(!account_filter(&session) && session.filter_explicit_content());
    }

    #[tokio::test]
    async fn the_offline_session_takes_the_last_known_account_filter() {
        let session = Session::new(SessionConfig::default(), None);
        // A filtered account (last reported online), "Hide explicit content" off.
        assert_eq!(seed(&session, true, false), Some(true));
        assert!(session.filter_explicit_content() && account_filter(&session));
        // The setting can't turn it off.
        assert_eq!(apply(&session, false), None);
        // The account turned its filter off (reported online, persisted, seeded again).
        assert_eq!(seed(&session, false, false), Some(false));
        assert!(!session.filter_explicit_content());
        assert_eq!(apply(&session, true), Some(true), "the setting alone still filters");
        assert_eq!(seed(&session, true, true), None, "still on: now also the account's");
        assert!(account_filter(&session));
    }

    #[tokio::test]
    async fn a_connect_attempt_filters_before_product_info() {
        // A fresh attempt session, seeded like the offline one: filtered from the start, with
        // either the setting or the account's last known filter.
        let session = Session::new(SessionConfig::default(), None);
        seed(&session, false, true);
        assert!(session.filter_explicit_content());
        let session = Session::new(SessionConfig::default(), None);
        seed(&session, true, false);
        assert!(session.filter_explicit_content());
        // ProductInfo brings the account's current value; the setting stays.
        product_info(&session, "0");
        assert!(!session.filter_explicit_content(), "the account allows it now");
        let session = Session::new(SessionConfig::default(), None);
        seed(&session, false, true);
        product_info(&session, "0");
        assert!(session.filter_explicit_content(), "ProductInfo can't lift the setting");
        // Nothing known, setting off: not filtered.
        let session = Session::new(SessionConfig::default(), None);
        assert_eq!(seed(&session, false, false), None);
        assert!(!session.filter_explicit_content());
    }
}
