//! context-resolve helpers (`/context-resolve/v1/<uri>`): used as a fallback source of item
//! lists (Liked Songs, shows, `spotify:search:<q>`).

use super::http::{self, JSON};
use crate::error::{AppError, AppResult, ErrorCode};
use futures_util::future::{BoxFuture, FutureExt};
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

/// Items of a resolved context.
#[derive(Debug, Default)]
pub(crate) struct Resolved {
    pub items: Vec<ContextItem>,
    /// Set when a page request failed: `items` is then only a prefix of the context. Stopping at
    /// `max_items` / `max_pages` is not a failure.
    pub incomplete: Option<AppError>,
}

/// Resolves `context_uri` and collects up to `max_items` items in document order, following
/// page URLs (at most `max_pages` extra requests). Fails when a followed page fails, so a
/// truncated list is never mistaken for the whole context (see [`resolve_prefix`]).
pub(crate) async fn resolve(
    session: &Session,
    context_uri: &str,
    max_items: usize,
    max_pages: usize,
) -> AppResult<Vec<ContextItem>> {
    let r = resolve_prefix(session, context_uri, max_items, max_pages).await?;
    match r.incomplete {
        Some(e) => Err(e),
        None => Ok(r.items),
    }
}

/// Like [`resolve`], but a failed page request ends the list early (reported in
/// [`Resolved::incomplete`]) instead of failing the call.
pub(crate) async fn resolve_prefix(
    session: &Session,
    context_uri: &str,
    max_items: usize,
    max_pages: usize,
) -> AppResult<Resolved> {
    let body = http::spc_get_plain(session, &format!("/context-resolve/v1/{context_uri}"), Some(JSON)).await?;
    let text = String::from_utf8_lossy(&body);
    if text.trim().is_empty() {
        return Err(AppError::not_found(format!("{context_uri}: empty context")));
    }
    let ctx = parse_context(&text)?;
    let fetch = |url: String| -> BoxFuture<'_, AppResult<ContextPage>> {
        async move {
            // Page URLs are relative to the spclient (`hm://…` historically).
            let body = http::spc_get_plain(session, &endpoint_for_page_url(&url), Some(JSON)).await?;
            parse_page(&String::from_utf8_lossy(&body))
        }
        .boxed()
    };
    Ok(collect(&ctx.pages, max_items, max_pages, &fetch).await)
}

/// Collects the items of `pages` in document order: a page's inline tracks (or the page behind
/// its `page_url`) and then the chain of its `next_page_url`s, before the next page.
async fn collect<'a>(
    pages: &[ContextPage],
    max_items: usize,
    max_pages: usize,
    fetch: &(dyn Fn(String) -> BoxFuture<'a, AppResult<ContextPage>> + Sync),
) -> Resolved {
    let mut out = Resolved::default();
    let mut requests = 0;
    'pages: for page in pages {
        if out.items.len() >= max_items {
            break;
        }
        let mut next = if page.tracks.is_empty() {
            non_empty(page.page_url.as_deref())
        } else {
            page_items(page, &mut out.items);
            non_empty(page.next_page_url.as_deref())
        };
        while let Some(url) = next.take() {
            if out.items.len() >= max_items || requests >= max_pages {
                break 'pages;
            }
            requests += 1;
            match fetch(url).await {
                Ok(p) => {
                    page_items(&p, &mut out.items);
                    next = non_empty(p.next_page_url.as_deref());
                }
                Err(e) => {
                    log::warn!("context page failed: {e}");
                    out.incomplete = Some(e);
                    break 'pages;
                }
            }
        }
    }
    out.items.truncate(max_items);
    out
}

fn non_empty(url: Option<&str>) -> Option<String> {
    url.filter(|u| !u.is_empty()).map(str::to_string)
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

    fn page_json(uris: &[&str], next: Option<&str>, page_url: Option<&str>) -> String {
        let tracks: Vec<String> = uris.iter().map(|u| format!(r#"{{"uri":"{u}"}}"#)).collect();
        let mut fields = vec![format!(r#""tracks":[{}]"#, tracks.join(","))];
        if let Some(n) = next {
            fields.push(format!(r#""nextPageUrl":"{n}""#));
        }
        if let Some(p) = page_url {
            fields.push(format!(r#""pageUrl":"{p}""#));
        }
        format!("{{{}}}", fields.join(","))
    }

    /// `[{tracks:[a], next:N0}, {pageUrl:P1}, {pageUrl:P2}]`; N0 → N1 and P1 → P1b are chained.
    fn fixture() -> (Context, HashMap<String, String>) {
        let ctx = parse_context(&format!(
            r#"{{"pages":[{},{},{}]}}"#,
            page_json(&["spotify:track:a"], Some("N0"), None),
            page_json(&[], None, Some("P1")),
            page_json(&[], None, Some("P2")),
        ))
        .unwrap();
        let pages = [
            ("N0", page_json(&["spotify:track:n0"], Some("N1"), None)),
            ("N1", page_json(&["spotify:track:n1"], None, None)),
            ("P1", page_json(&["spotify:track:p1"], Some("P1b"), None)),
            ("P1b", page_json(&["spotify:track:p1b"], None, None)),
            ("P2", page_json(&["spotify:track:p2"], None, None)),
        ]
        .into_iter()
        .map(|(k, v)| (k.to_string(), v))
        .collect();
        (ctx, pages)
    }

    fn uris(r: &Resolved) -> Vec<&str> {
        r.items.iter().map(|i| i.uri.as_str()).collect()
    }

    #[tokio::test]
    async fn follows_pages_in_document_order() {
        let (ctx, pages) = fixture();
        let log = parking_lot::Mutex::new(Vec::new());
        let fetch = |url: String| -> BoxFuture<'_, AppResult<ContextPage>> {
            log.lock().push(url.clone());
            let body = pages.get(&url).cloned();
            async move { parse_page(&body.ok_or_else(|| AppError::not_found(url))?) }.boxed()
        };
        let r = collect(&ctx.pages, 100, 10, &fetch).await;
        assert!(r.incomplete.is_none());
        assert_eq!(*log.lock(), ["N0", "N1", "P1", "P1b", "P2"]);
        assert_eq!(
            uris(&r),
            ["spotify:track:a", "spotify:track:n0", "spotify:track:n1", "spotify:track:p1", "spotify:track:p1b", "spotify:track:p2"]
        );

        // The item budget keeps the first items, not the last pages.
        log.lock().clear();
        let r = collect(&ctx.pages, 3, 10, &fetch).await;
        assert_eq!(uris(&r), ["spotify:track:a", "spotify:track:n0", "spotify:track:n1"]);
        assert_eq!(*log.lock(), ["N0", "N1"]);
        // So does the request budget; neither is a failure.
        let r = collect(&ctx.pages, 100, 1, &fetch).await;
        assert!(r.incomplete.is_none());
        assert_eq!(uris(&r), ["spotify:track:a", "spotify:track:n0"]);
    }

    #[tokio::test]
    async fn reports_a_failed_page() {
        let (ctx, mut pages) = fixture();
        pages.remove("P1");
        let fetch = |url: String| -> BoxFuture<'_, AppResult<ContextPage>> {
            let body = pages.get(&url).cloned();
            async move { parse_page(&body.ok_or_else(|| AppError::new(ErrorCode::Network, url))?) }.boxed()
        };
        let r = collect(&ctx.pages, 100, 10, &fetch).await;
        assert_eq!(r.incomplete.map(|e| e.code), Some(ErrorCode::Network));
        assert_eq!(r.items.len(), 3, "items before the failed page are kept");
    }
}
