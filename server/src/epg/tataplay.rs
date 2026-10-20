//! Tata Play (port of iptv-org/epg sites/tataplay.com).
//! POST https://tm.tapi.videoready.tv/content-detail/pub/api/v2/channels/schedule?date=DD-MM-YYYY
//! body {"id": site_id}. The API sits behind Akamai, which rejects requests that
//! lack a browser's `sec-*` headers, so we send a full browser header set.

use super::{body, Prog, Scraper};
use crate::timeutil::{parse_iso, IstDate};
use async_trait::async_trait;
use reqwest::Client;
use serde_json::Value;
use std::time::Duration;

pub struct TataPlay;

const URL: &str = "https://tm.tapi.videoready.tv/content-detail/pub/api/v2/channels/schedule";
const UA: &str = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36";

#[async_trait]
impl Scraper for TataPlay {
    fn site(&self) -> &'static str {
        "tataplay.com"
    }

    // The API gateway allows 30 requests per minute per IP (RateLimit-* headers).
    fn concurrency(&self) -> usize {
        1
    }

    fn pause(&self) -> Duration {
        Duration::from_millis(2200)
    }

    fn gap_filler(&self) -> bool {
        true
    }

    async fn fetch_day(
        &self,
        client: &Client,
        site_id: &str,
        day: IstDate,
    ) -> Result<Vec<Prog>, String> {
        for attempt in 0..2 {
            let r = self.request(client, site_id, day).send().await;
            if let Ok(resp) = &r {
                if resp.status().as_u16() == 429 && attempt == 0 {
                    let wait = resp
                        .headers()
                        .get("ratelimit-reset")
                        .and_then(|v| v.to_str().ok()?.parse::<u64>().ok())
                        .unwrap_or(60)
                        .min(120);
                    tracing::debug!(wait, "tataplay rate limited; waiting");
                    tokio::time::sleep(Duration::from_secs(wait + 1)).await;
                    continue;
                }
            }
            return parse(&body(r).await?);
        }
        Err("HTTP 429".into())
    }
}

impl TataPlay {
    fn request(&self, client: &Client, site_id: &str, day: IstDate) -> reqwest::RequestBuilder {
        client
            .post(URL)
            .query(&[("date", day.dmy('-'))])
            .header("accept", "*/*")
            .header("accept-language", "en-IN,en;q=0.9")
            .header("content-type", "application/json")
            .header("locale", "ENG")
            .header("origin", "https://watch.tataplay.com")
            .header("referer", "https://watch.tataplay.com/")
            .header(
                "sec-ch-ua",
                "\"Chromium\";v=\"146\", \"Not-A.Brand\";v=\"24\"",
            )
            .header("sec-ch-ua-mobile", "?0")
            .header("sec-ch-ua-platform", "\"Windows\"")
            .header("sec-fetch-dest", "empty")
            .header("sec-fetch-mode", "cors")
            .header("sec-fetch-site", "cross-site")
            .header("user-agent", UA)
            .body(serde_json::json!({ "id": site_id }).to_string())
    }
}

/// Times come as epoch milliseconds (live API) or ISO strings (older responses).
fn time(v: &Value) -> Option<i64> {
    match v {
        Value::Number(n) => n
            .as_i64()
            .map(|ms| if ms > 100_000_000_000 { ms / 1000 } else { ms }),
        Value::String(s) => s
            .parse::<i64>()
            .ok()
            .map(|ms| ms / 1000)
            .or_else(|| parse_iso(s, 0, false)),
        _ => None,
    }
}

pub fn parse(content: &str) -> Result<Vec<Prog>, String> {
    let json: Value = serde_json::from_str(content).map_err(|e| format!("json: {e}"))?;
    let Some(list) = json.pointer("/data/epg").and_then(Value::as_array) else {
        return Ok(Vec::new());
    };
    Ok(list
        .iter()
        .filter_map(|p| {
            Some((
                time(p.get("startTime")?)?,
                time(p.get("endTime")?)?,
                p.get("title")?.as_str()?.to_string(),
                p.get("desc")
                    .and_then(Value::as_str)
                    .unwrap_or("")
                    .to_string(),
            ))
        })
        .collect())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_iptv_org_fixture() {
        let v = parse(include_str!("../../tests/fixtures/tataplay_iptvorg.json")).unwrap();
        assert_eq!(v.len(), 2);
        assert_eq!(v[0].0, 1_749_492_000); // 2025-06-09T18:00:00Z
        assert_eq!(v[0].1, 1_749_493_800);
        assert_eq!(v[0].2, "Yeh Rishta Kya Kehlata Hai");
        assert_eq!(
            v[0].3,
            "The story of the Rajshri family and their journey through life."
        );
    }

    #[test]
    fn parses_live_sample() {
        // Sun TV (165), 2026-10-20; starts at 00:00 IST.
        let v = parse(include_str!("../../tests/fixtures/tataplay_live.json")).unwrap();
        assert_eq!(v.len(), 4);
        assert_eq!(
            v[0].0,
            IstDate {
                y: 2026,
                m: 10,
                d: 20
            }
            .start_utc()
        );
        assert_eq!(v[0].1, v[1].0);
        assert_eq!(v[0].2, "Nagaichuvai Gallatta");
    }

    #[test]
    fn empty_guide() {
        assert!(parse(r#"{"data":{"epg":[]}}"#).unwrap().is_empty());
        assert!(parse("<html>Access Denied</html>").is_err());
    }
}
