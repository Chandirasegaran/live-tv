//! M3U parsing (only tvg-ids are needed, to know which channels want a guide).

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct Channel {
    pub tvg_id: String,
    pub name: String,
    #[serde(default)]
    pub logo: String,
    #[serde(default)]
    pub group: String,
    #[serde(default)]
    pub referrer: String,
    #[serde(default)]
    pub user_agent: String,
    pub url: String,
}

impl Channel {
    /// Guide key: tvg-id without the `@feed` suffix.
    pub fn key(&self) -> &str {
        channel_key(&self.tvg_id)
    }

    /// Feed part of the tvg-id (`SunTV.in@HD` -> `HD`), if any.
    pub fn feed(&self) -> Option<&str> {
        self.tvg_id.split_once('@').map(|(_, f)| f)
    }
}

pub fn channel_key(tvg_id: &str) -> &str {
    tvg_id.split_once('@').map_or(tvg_id, |(k, _)| k)
}

/// iptv-org occasionally doubles the option prefix inside the value
/// (`http-user-agent=#EXTVLCOPT:http-user-agent=Mozilla/...`). Strip that.
fn clean_opt(v: &str, opt: &str) -> String {
    let prefix = format!("#EXTVLCOPT:{opt}=");
    let mut v = v.trim();
    while let Some(rest) = v.strip_prefix(&prefix) {
        v = rest.trim();
    }
    v.to_string()
}

/// Splits `#EXTINF:-1 a="b" c="d",Display Name` into attributes and name.
fn parse_extinf(line: &str) -> (Vec<(String, String)>, String) {
    let body = line.trim_start_matches("#EXTINF:");
    let mut attrs = Vec::new();
    let bytes = body.as_bytes();
    let mut i = 0;
    // skip duration
    while i < bytes.len() && !bytes[i].is_ascii_whitespace() && bytes[i] != b',' {
        i += 1;
    }
    loop {
        while i < bytes.len() && bytes[i].is_ascii_whitespace() {
            i += 1;
        }
        if i >= bytes.len() || bytes[i] == b',' {
            break;
        }
        let ks = i;
        while i < bytes.len()
            && bytes[i] != b'='
            && bytes[i] != b','
            && !bytes[i].is_ascii_whitespace()
        {
            i += 1;
        }
        let key = body[ks..i].to_string();
        if i < bytes.len() && bytes[i] == b'=' {
            i += 1;
            let val = if i < bytes.len() && bytes[i] == b'"' {
                i += 1;
                let vs = i;
                while i < bytes.len() && bytes[i] != b'"' {
                    i += 1;
                }
                let v = body[vs..i].to_string();
                i = (i + 1).min(bytes.len());
                v
            } else {
                let vs = i;
                while i < bytes.len() && !bytes[i].is_ascii_whitespace() && bytes[i] != b',' {
                    i += 1;
                }
                body[vs..i].to_string()
            };
            attrs.push((key, val));
        }
    }
    let name = if i < bytes.len() {
        body[i + 1..].trim().to_string()
    } else {
        String::new()
    };
    (attrs, name)
}

pub fn parse(text: &str) -> Vec<Channel> {
    let mut out = Vec::new();
    let mut cur: Option<Channel> = None;
    for raw in text.lines() {
        let line = raw.trim();
        if line.is_empty() || line.starts_with("#EXTM3U") {
            continue;
        }
        if line.starts_with("#EXTINF:") {
            let (attrs, name) = parse_extinf(line);
            let mut ch = Channel {
                name,
                ..Default::default()
            };
            for (k, v) in attrs {
                match k.as_str() {
                    "tvg-id" => ch.tvg_id = v,
                    "tvg-logo" => ch.logo = v,
                    "group-title" => ch.group = v,
                    "http-referrer" => ch.referrer = clean_opt(&v, "http-referrer"),
                    "http-user-agent" => ch.user_agent = clean_opt(&v, "http-user-agent"),
                    _ => {}
                }
            }
            cur = Some(ch);
        } else if let Some(opt) = line.strip_prefix("#EXTVLCOPT:") {
            if let (Some(ch), Some((k, v))) = (cur.as_mut(), opt.split_once('=')) {
                match k {
                    "http-referrer" => ch.referrer = clean_opt(v, "http-referrer"),
                    "http-user-agent" => ch.user_agent = clean_opt(v, "http-user-agent"),
                    _ => {}
                }
            }
        } else if line.starts_with('#') {
            continue;
        } else if let Some(mut ch) = cur.take() {
            ch.url = line.to_string();
            out.push(ch);
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    const IN: &str = r##"#EXTM3U
#EXTINF:-1 tvg-id="Aastha.in@SD" tvg-logo="https://i.imgur.com/IqgrV92.png" http-user-agent="#EXTVLCOPT:http-user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64)" group-title="Religious",Aastha (576p)
#EXTVLCOPT:http-user-agent=#EXTVLCOPT:http-user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64)
http://103.175.73.12:8080/live/338/master.m3u8
#EXTINF:-1 tvg-id="ETNow.in@SD" tvg-logo="https://x/et.png" http-referrer="https://www.timesnownews.com/" group-title="Business",ET Now (720p) [Geo-blocked]
#EXTVLCOPT:http-referrer=https://www.timesnownews.com/
https://d.cloudfront.net/etnow_master.m3u8
#EXTINF:-1 tvg-id="SunTV.in@SD" tvg-logo="https://x/sun2.png" group-title="General",Sun TV dup
https://b.example/sun/index.m3u8
#EXTINF:-1 tvg-id="" tvg-logo="" group-title="Undefined",No Id, With Comma
https://c.example/noid.m3u8
#EXTINF:-1 tvg-id="" tvg-logo="" group-title="Undefined",No Id Again
https://c.example/noid.m3u8
"##;

    #[test]
    fn parses_attributes_and_options() {
        let l = parse(IN);
        assert_eq!(l.len(), 5);
        assert_eq!(l[0].tvg_id, "Aastha.in@SD");
        assert_eq!(l[0].user_agent, "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
        assert_eq!(l[0].name, "Aastha (576p)");
        assert_eq!(l[0].group, "Religious");
        assert_eq!(l[1].referrer, "https://www.timesnownews.com/");
        assert_eq!(l[1].url, "https://d.cloudfront.net/etnow_master.m3u8");
        assert_eq!(l[3].name, "No Id, With Comma");
        assert_eq!(l[1].key(), "ETNow.in");
        assert_eq!(l[1].feed(), Some("SD"));
    }
}
