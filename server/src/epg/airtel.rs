//! Airtel Xstream (port of iptv-org/epg sites/airtelxstream.in).
//! GET https://epg.airtel.tv/app/v2/content/channel/epg?channelId=..&startTime=<ms>&endTime=<ms>
//! Response: {"programGuide": {"<channelId>": [{title, desc, startTime, endTime}, ...]}}, times in ms.

use super::{body, Prog, Scraper};
use crate::timeutil::{IstDate, DAY};
use async_trait::async_trait;
use reqwest::Client;
use serde_json::Value;

pub struct Airtel;

const URL: &str = "https://epg.airtel.tv/app/v2/content/channel/epg";
const UA: &str = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36";

#[async_trait]
impl Scraper for Airtel {
    fn site(&self) -> &'static str {
        "airtelxstream.in"
    }

    async fn fetch_day(
        &self,
        client: &Client,
        site_id: &str,
        day: IstDate,
    ) -> Result<Vec<Prog>, String> {
        let start = day.start_utc() * 1000;
        let r = client
            .get(URL)
            .query(&[
                ("channelId", site_id.to_string()),
                ("startTime", start.to_string()),
                ("endTime", (start + DAY * 1000).to_string()),
            ])
            .header("user-agent", UA)
            .header("referer", "https://www.airtelxstream.in/")
            .send()
            .await;
        parse(&body(r).await?)
    }
}

pub fn parse(content: &str) -> Result<Vec<Prog>, String> {
    let json: Value = serde_json::from_str(content).map_err(|e| format!("json: {e}"))?;
    let Some(list) = json
        .get("programGuide")
        .and_then(Value::as_object)
        .and_then(|g| g.values().next())
        .and_then(Value::as_array)
    else {
        return Ok(Vec::new());
    };
    Ok(list
        .iter()
        .filter_map(|p| {
            Some((
                p.get("startTime")?.as_i64()? / 1000,
                p.get("endTime")?.as_i64()? / 1000,
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
        let v = parse(include_str!("../../tests/fixtures/airtel_iptvorg.json")).unwrap();
        assert!(!v.is_empty());
        assert_eq!(v[0].0, 1_766_868_120);
        assert_eq!(v[0].1, 1_766_875_380);
        assert_eq!(v[0].2, "Bengal Tiger");
        assert!(v[0].3.starts_with("Starring: Ravi Teja"));
    }

    #[test]
    fn parses_live_sample() {
        let v = parse(include_str!("../../tests/fixtures/airtel_live.json")).unwrap();
        assert_eq!(v.len(), 3);
        assert_eq!(
            v[1].0,
            IstDate {
                y: 2026,
                m: 10,
                d: 20
            }
            .start_utc()
        );
        assert_eq!(v[1].2, "Nagaichuvai Galatta");
    }

    #[test]
    fn empty_guide() {
        assert!(parse(r#"{"programGuide": {}}"#).unwrap().is_empty());
    }
}
