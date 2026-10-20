//! DishTV (port of iptv-org/epg sites/dishtv.in).
//! 1. POST https://www.dishtv.in/services/epg/signin -> {"token": ...}
//! 2. POST https://epg.mysmartstick.com/dishtv/api/v1/epg/entities/programs
//!    with `Authorization: <token>` and {"allowPastEvents":true,"channelid":..,"date":"DD/MM/YYYY"}.
//! Times look like "2026-10-20T05:00:00Z" but are IST wall-clock (the "Z" is wrong),
//! exactly as the upstream config treats them (dayjs.tz(start, 'Asia/Kolkata')).

use super::{body, Prog, Scraper};
use crate::timeutil::{parse_iso, IstDate, IST_OFFSET};
use async_trait::async_trait;
use reqwest::Client;
use serde_json::Value;
use std::sync::Mutex;

#[derive(Default)]
pub struct DishTv {
    token: Mutex<Option<String>>,
}

const SIGNIN: &str = "https://www.dishtv.in/services/epg/signin";
const PROGRAMS: &str = "https://epg.mysmartstick.com/dishtv/api/v1/epg/entities/programs";
const UA: &str = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36";

#[async_trait]
impl Scraper for DishTv {
    fn site(&self) -> &'static str {
        "dishtv.in"
    }

    async fn prepare(&self, client: &Client) -> Result<(), String> {
        let r = client
            .post(SIGNIN)
            .header("x-requested-with", "XMLHttpRequest")
            .header("referer", "https://www.dishtv.in/channel-guide.html")
            .header("user-agent", UA)
            .header("content-length", "0")
            .send()
            .await;
        let text = body(r).await.map_err(|e| format!("signin: {e}"))?;
        let token = parse_token(&text).ok_or("signin: no token in response")?;
        *self.token.lock().unwrap() = Some(token);
        Ok(())
    }

    async fn fetch_day(
        &self,
        client: &Client,
        site_id: &str,
        day: IstDate,
    ) -> Result<Vec<Prog>, String> {
        let token = self.token.lock().unwrap().clone().ok_or("not signed in")?;
        let r = client
            .post(PROGRAMS)
            .header("authorization", token)
            .header("content-type", "application/json")
            .header("user-agent", UA)
            .body(
                serde_json::json!({
                    "allowPastEvents": true,
                    "channelid": site_id,
                    "date": day.dmy('/'),
                })
                .to_string(),
            )
            .send()
            .await;
        parse(&body(r).await?)
    }
}

pub fn parse_token(content: &str) -> Option<String> {
    let v: Value = serde_json::from_str(content).ok()?;
    v.get("token")?
        .as_str()
        .filter(|t| !t.is_empty())
        .map(str::to_string)
}

/// English title: top-level `title`, else the English regional entry.
fn text(p: &Value, field: &str) -> String {
    if let Some(s) = p
        .get(field)
        .and_then(Value::as_str)
        .filter(|s| !s.trim().is_empty())
    {
        return s.to_string();
    }
    p.get("regional")
        .and_then(Value::as_object)
        .and_then(|r| {
            r.values()
                .find(|x| x.get("languagecode").and_then(Value::as_str) == Some("en"))
                .or_else(|| r.values().next())
        })
        .and_then(|x| x.get(field))
        .and_then(Value::as_str)
        .unwrap_or("")
        .to_string()
}

pub fn parse(content: &str) -> Result<Vec<Prog>, String> {
    let json: Value = serde_json::from_str(content).map_err(|e| format!("json: {e}"))?;
    let Some(list) = json.as_array() else {
        return Ok(Vec::new());
    };
    Ok(list
        .iter()
        .filter_map(|p| {
            Some((
                parse_iso(p.get("start")?.as_str()?, IST_OFFSET, true)?,
                parse_iso(p.get("stop")?.as_str()?, IST_OFFSET, true)?,
                text(p, "title"),
                text(p, "desc"),
            ))
        })
        .collect())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_iptv_org_fixture() {
        let v = parse(include_str!("../../tests/fixtures/dishtv_iptvorg.json")).unwrap();
        assert_eq!(v.len(), 16);
        // upstream test: start 2025-01-25T19:00:00Z, stop 2025-01-25T20:35:00Z
        assert_eq!(v[0].0, 1_737_831_600);
        assert_eq!(v[0].1, 1_737_837_300);
        assert_eq!(v[0].2, "Train to Busan 2: Peninsula");
        assert!(v[0].3.starts_with("Jung Seok, a former soldier"));
    }

    #[test]
    fn parses_live_sample() {
        // Sun TV (117852), 2026-10-20. 05:00 IST matches Tata Play's slot for the same show.
        let v = parse(include_str!("../../tests/fixtures/dishtv_live.json")).unwrap();
        assert_eq!(v.len(), 4);
        assert_eq!(
            v[0].0,
            IstDate {
                y: 2026,
                m: 10,
                d: 20
            }
            .start_utc()
                + 5 * 3600
        );
        assert_eq!(v[0].2, "Bhakthi Paadalgal");
    }

    #[test]
    fn token_and_empty() {
        assert_eq!(
            parse_token(r#"{"success":"true","token":"abc"}"#).as_deref(),
            Some("abc")
        );
        assert_eq!(parse_token(r#"{"success":"false"}"#), None);
        assert!(parse("[]").unwrap().is_empty());
    }
}
