//! Tiny date helpers. India has no DST, so IST is a fixed +05:30 and we
//! don't need a timezone database.

pub const IST_OFFSET: i64 = 5 * 3600 + 1800;
pub const DAY: i64 = 86_400;

pub fn now() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

/// Days since 1970-01-01 for a proleptic Gregorian date (Howard Hinnant's algorithm).
pub fn days_from_civil(y: i64, m: u32, d: u32) -> i64 {
    let y = if m <= 2 { y - 1 } else { y };
    let era = if y >= 0 { y } else { y - 399 } / 400;
    let yoe = y - era * 400;
    let m = m as i64;
    let doy = (153 * (if m > 2 { m - 3 } else { m + 9 }) + 2) / 5 + d as i64 - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146_097 + doe - 719_468
}

pub fn civil_from_days(z: i64) -> (i64, u32, u32) {
    let z = z + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let m = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    (if m <= 2 { y + 1 } else { y }, m, d)
}

/// A calendar day in India.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct IstDate {
    pub y: i64,
    pub m: u32,
    pub d: u32,
}

impl IstDate {
    pub fn of(unix: i64) -> Self {
        let (y, m, d) = civil_from_days((unix + IST_OFFSET).div_euclid(DAY));
        IstDate { y, m, d }
    }
    pub fn today() -> Self {
        Self::of(now())
    }
    /// Unix seconds of 00:00 IST on this day.
    pub fn start_utc(self) -> i64 {
        days_from_civil(self.y, self.m, self.d) * DAY - IST_OFFSET
    }
    pub fn next(self) -> Self {
        Self::of(self.start_utc() + DAY)
    }
    pub fn dmy(self, sep: char) -> String {
        format!("{:02}{sep}{:02}{sep}{:04}", self.d, self.m, self.y)
    }
}

/// Parses `YYYY-MM-DDTHH:MM[:SS[.fff]][Z|±HH:MM]` into Unix seconds.
/// Without an explicit zone the value is read as `default_offset` seconds east of UTC.
/// When `force_offset` is set any zone in the text is ignored (DishTV labels IST times with "Z").
pub fn parse_iso(s: &str, default_offset: i64, force_offset: bool) -> Option<i64> {
    let s = s.trim();
    let b = s.as_bytes();
    if b.len() < 16
        || b[4] != b'-'
        || b[7] != b'-'
        || !(b[10] == b'T' || b[10] == b' ')
        || b[13] != b':'
    {
        return None;
    }
    let num = |r: std::ops::Range<usize>| -> Option<i64> { s.get(r)?.parse().ok() };
    let (y, mo, d, h, mi) = (
        num(0..4)?,
        num(5..7)?,
        num(8..10)?,
        num(11..13)?,
        num(14..16)?,
    );
    let mut i = 16;
    let mut sec = 0;
    if b.get(i) == Some(&b':') {
        sec = num(17..19)?;
        i = 19;
        if b.get(i) == Some(&b'.') {
            i += 1;
            while i < b.len() && b[i].is_ascii_digit() {
                i += 1;
            }
        }
    }
    let rest = &s[i..];
    let offset = if force_offset || rest.is_empty() {
        default_offset
    } else if rest == "Z" || rest == "z" {
        0
    } else {
        let sign = match rest.as_bytes()[0] {
            b'+' => 1,
            b'-' => -1,
            _ => return None,
        };
        let r = rest[1..].replace(':', "");
        let hh: i64 = r.get(0..2)?.parse().ok()?;
        let mm: i64 = r.get(2..4).unwrap_or("00").parse().ok()?;
        sign * (hh * 3600 + mm * 60)
    };
    if !(1..=12).contains(&mo) || !(1..=31).contains(&d) {
        return None;
    }
    Some(days_from_civil(y, mo as u32, d as u32) * DAY + h * 3600 + mi * 60 + sec - offset)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ist_day_bounds() {
        // 2026-10-20 00:00 IST == 2026-10-19 18:30 UTC
        let d = IstDate {
            y: 2026,
            m: 10,
            d: 20,
        };
        assert_eq!(d.start_utc(), 1_792_434_600);
        assert_eq!(IstDate::of(1_792_434_600), d);
        assert_eq!(
            IstDate::of(1_792_434_599),
            IstDate {
                y: 2026,
                m: 10,
                d: 19
            }
        );
        assert_eq!(
            d.next(),
            IstDate {
                y: 2026,
                m: 10,
                d: 21
            }
        );
        assert_eq!(
            IstDate {
                y: 2026,
                m: 12,
                d: 31
            }
            .next(),
            IstDate {
                y: 2027,
                m: 1,
                d: 1
            }
        );
        assert_eq!(d.dmy('-'), "20-10-2026");
        assert_eq!(d.dmy('/'), "20/10/2026");
    }

    #[test]
    fn iso_parsing() {
        assert_eq!(
            parse_iso("2025-06-09T18:00:00.000Z", 0, false),
            Some(1_749_492_000)
        );
        assert_eq!(
            parse_iso("2025-06-09T23:30:00+05:30", 0, false),
            Some(1_749_492_000)
        );
        assert_eq!(
            parse_iso("2025-06-09T23:30:00Z", IST_OFFSET, true),
            Some(1_749_492_000)
        );
        assert_eq!(
            parse_iso("2025-06-09T23:30", IST_OFFSET, false),
            Some(1_749_492_000)
        );
        assert_eq!(parse_iso("nonsense", 0, false), None);
    }
}
