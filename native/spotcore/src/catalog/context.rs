//! context-resolve helpers (`/context-resolve/v1/<uri>`): used as a fallback source of item
//! lists (Liked Songs, shows, `spotify:search:<q>`).

use super::http::{self, JSON};
use crate::error::{AppError, AppResult, ErrorCode};
use librespot_core::Session;
use librespot_protocol::context::Context;
use librespot_protocol::context_page::ContextPage;
use protobuf_json_mapping::ParseOptions;
use std::collections::HashMap;

/// One item of a resolved context.
#[derive(Debug, Clone, PartialEq)]
pub(crate) struct ContextItem {
    pub uri: String,
    pub uid: Option<String>,
    pub metadata: HashMap<String, String>,
}

fn options() -> ParseOptions {
    ParseOptions { ignore_unknown_fields: true, ..Default::default() }
}

pub(crate) fn parse_context(json: &str) -> AppResult<Context> {
    protobuf_json_mapping::parse_from_str_with_options::<Context>(json, &options())
        .map_err(|e| AppError::new(ErrorCode::Unavailable, format!("context: {e}")))
}

pub(crate) fn parse_page(json: &str) -> AppResult<ContextPage> {
    protobuf_json_mapping::parse_from_str_with_options::<ContextPage>(json, &options())
        .map_err(|e| AppError::new(ErrorCode::Unavailable, format!("context page: {e}")))
}

fn page_items(page: &ContextPage, out: &mut Vec<ContextItem>) {
    for t in &page.tracks {
        let uri = t.uri();
        if uri.is_empty() || uri.contains("delimiter") {
            continue;
        }
        out.push(ContextItem {
            uri: uri.to_string(),
            uid: t.uid.clone().filter(|u| !u.is_empty()),
            metadata: t.metadata.clone(),
        });
    }
}

fn endpoint_for_page_url(url: &str) -> String {
    let path = url.trim_start_matches("hm://");
    format!("/{}", path.trim_start_matches('/'))
}

/// Resolves `context_uri` and collects up to `max_items` items, following page URLs (at most
/// `max_pages` extra requests).
pub(crate) async fn resolve(
    session: &Session,
    context_uri: &str,
    max_items: usize,
    max_pages: usize,
) -> AppResult<Vec<ContextItem>> {
    let body = http::spc_get_plain(session, &format!("/context-resolve/v1/{context_uri}"), Some(JSON)).await?;
    let text = String::from_utf8_lossy(&body);
    if text.trim().is_empty() {
        return Err(AppError::not_found(format!("{context_uri}: empty context")));
    }
    let ctx = parse_context(&text)?;
    let mut items = Vec::new();
    let mut follow: Vec<String> = Vec::new();
    for page in &ctx.pages {
        if page.tracks.is_empty() {
            if let Some(url) = page.page_url.as_deref().filter(|u| !u.is_empty()) {
                follow.push(url.to_string());
            }
        } else {
            page_items(page, &mut items);
            if let Some(url) = page.next_page_url.as_deref().filter(|u| !u.is_empty()) {
                follow.push(url.to_string());
            }
        }
    }
    let mut requests = 0;
    while let Some(url) = (items.len() < max_items && requests < max_pages).then(|| follow.pop()).flatten() {
        requests += 1;
        // Page URLs are relative to the spclient (`hm://…` historically).
        let page = match http::spc_get_plain(session, &endpoint_for_page_url(&url), Some(JSON)).await {
            Ok(b) => parse_page(&String::from_utf8_lossy(&b)),
            Err(e) => Err(e.into()),
        };
        match page {
            Ok(p) => {
                page_items(&p, &mut items);
                if let Some(next) = p.next_page_url.as_deref().filter(|u| !u.is_empty()) {
                    follow.push(next.to_string());
                }
            }
            Err(e) => {
                log::warn!("context page failed: {e}");
                break;
            }
        }
    }
    items.truncate(max_items);
    Ok(items)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_context_json_leniently() {
        let json = r#"{
            "uri": "spotify:user:alice:collection",
            "metadata": {"context_description": "Liked Songs"},
            "pages": [
                {"tracks": [
                    {"uri": "spotify:track:4uLU6hMCjMI75M1A2tKUQC", "uid": "u1", "metadata": {"added_at": "1700000000"}},
                    {"uri": "spotify:delimiter"},
                    {"uri": "spotify:track:7GhIk7Il098yCjg4BQjzvb", "unknownField": 1}
                 ],
                 "nextPageUrl": "hm://context-resolve/v1/page/2"},
                {"pageUrl": "hm://context-resolve/v1/page/3"}
            ],
            "someNewField": {"x": 1}
        }"#;
        let ctx = parse_context(json).unwrap();
        let mut items = Vec::new();
        for p in &ctx.pages {
            page_items(p, &mut items);
        }
        assert_eq!(items.len(), 2);
        assert_eq!(items[0].metadata.get("added_at").map(String::as_str), Some("1700000000"));
        assert_eq!(items[0].uid.as_deref(), Some("u1"));
        assert_eq!(ctx.pages[0].next_page_url(), "hm://context-resolve/v1/page/2");
        assert_eq!(endpoint_for_page_url("hm://context-resolve/v1/page/3"), "/context-resolve/v1/page/3");
    }
}
