//! Invite addresses, byte-compatible with `SteamAddress` / `SteamDedicatedAddress`.

use crate::frame::TOKEN_LENGTH;

const DIGITS: &[u8; 36] = b"0123456789abcdefghijklmnopqrstuvwxyz";

pub const TOKEN_HEX_LEN: usize = TOKEN_LENGTH * 2;

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct SteamAddress {
    pub steam_id: u64,
    pub token: [u8; TOKEN_LENGTH],
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct DedicatedAddress {
    pub steam_id: u64,
    pub generation: u64,
}

fn to_base36(value: u128) -> String {
    if value == 0 {
        return "0".to_string();
    }
    let mut out = Vec::new();
    let mut rest = value;
    while rest > 0 {
        out.push(DIGITS[(rest % 36) as usize]);
        rest /= 36;
    }
    out.reverse();
    String::from_utf8(out).expect("base36 digits are ascii")
}

/// Java's `new BigInteger(encoded, 36)` fails on overflow, so anything beyond u128 is rejected.
fn from_base36(encoded: &str) -> Option<u128> {
    if encoded.is_empty() {
        return None;
    }
    let mut acc: u128 = 0;
    for ch in encoded.bytes() {
        let digit = match ch {
            b'0'..=b'9' => ch - b'0',
            b'a'..=b'z' => ch - b'a' + 10,
            _ => return None,
        };
        acc = acc.checked_mul(36)?;
        acc = acc.checked_add(digit as u128)?;
    }
    Some(acc)
}

fn from_base36_u64(encoded: &str) -> Option<u64> {
    from_base36(encoded).and_then(|v| u64::try_from(v).ok())
}

fn from_dec_u64(encoded: &str) -> Option<u64> {
    if encoded.is_empty() || !encoded.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    encoded.parse::<u64>().ok()
}

fn token_from_u128(value: u128) -> [u8; TOKEN_LENGTH] {
    value.to_be_bytes()
}

fn hex32_to_token(hex: &str) -> Option<[u8; TOKEN_LENGTH]> {
    if hex.len() != TOKEN_HEX_LEN || !hex.bytes().all(|b| b.is_ascii_hexdigit()) {
        return None;
    }
    let mut out = [0u8; TOKEN_LENGTH];
    for (i, byte) in out.iter_mut().enumerate() {
        let hi = hex.as_bytes()[i * 2];
        let lo = hex.as_bytes()[i * 2 + 1];
        *byte = (hex_val(hi)? << 4) | hex_val(lo)?;
    }
    Some(out)
}

fn token_to_hex(token: &[u8; TOKEN_LENGTH]) -> String {
    let mut s = String::with_capacity(TOKEN_HEX_LEN);
    for b in token {
        s.push_str(&format!("{b:02x}"));
    }
    s
}

fn hex_val(b: u8) -> Option<u8> {
    match b {
        b'0'..=b'9' => Some(b - b'0'),
        b'a'..=b'f' => Some(b - b'a' + 10),
        b'A'..=b'F' => Some(b - b'A' + 10),
        _ => None,
    }
}

fn steam_suffix(rest: &str) -> Option<&str> {
    rest.strip_suffix(".steam")
        .or_else(|| rest.strip_suffix(".steam."))
}

impl SteamAddress {
    pub fn new(steam_id: u64, token: [u8; TOKEN_LENGTH]) -> Option<Self> {
        if steam_id == 0 {
            return None;
        }
        Some(Self { steam_id, token })
    }

    pub fn invite_string(&self) -> String {
        format!(
            "s-{}-{}.steam",
            to_base36(self.steam_id as u128),
            to_base36(u128::from_be_bytes(self.token))
        )
    }

    pub fn legacy_string(&self) -> String {
        format!("e4steam-{}-{}.steam", self.steam_id, token_to_hex(&self.token))
    }

    /// Mirrors `SteamAddress.tryParse`: short form first, then legacy hex form.
    pub fn try_parse(value: &str) -> Option<Self> {
        let normalized = value.trim().to_ascii_lowercase();
        if let Some(rest) = normalized.strip_prefix("s-").and_then(steam_suffix) {
            let (id_part, token_part) = rest.split_once('-')?;
            if !is_b36(id_part, 13) || !is_b36(token_part, 25) {
                return None;
            }
            let steam_id = from_base36_u64(id_part)?;
            if steam_id == 0 {
                return None;
            }
            let token = token_from_u128(from_base36(token_part)?);
            return Some(Self { steam_id, token });
        }
        let legacy = normalized.strip_prefix("e4steam-").and_then(steam_suffix)?;
        let (id_part, token_part) = legacy.split_once('-')?;
        if !is_dec(id_part, 20) || token_part.len() != TOKEN_HEX_LEN || !is_hex_lower(token_part) {
            return None;
        }
        let steam_id = from_dec_u64(id_part)?;
        if steam_id == 0 {
            return None;
        }
        Some(Self {
            steam_id,
            token: hex32_to_token(token_part)?,
        })
    }
}

impl DedicatedAddress {
    pub fn new(steam_id: u64, generation: u64) -> Option<Self> {
        if steam_id == 0 || generation == 0 {
            return None;
        }
        Some(Self { steam_id, generation })
    }

    pub fn descriptor(&self) -> String {
        format!(
            "d-{}-{}.steam",
            to_base36(self.steam_id as u128),
            to_base36(self.generation as u128)
        )
    }

    pub fn try_parse(value: &str) -> Option<Self> {
        let normalized = value.trim().to_ascii_lowercase();
        let body = steam_suffix(normalized.strip_prefix("d-")?)?;
        let (id_part, gen_part) = body.split_once('-')?;
        if !is_b36(id_part, 13) || !is_b36(gen_part, 25) {
            return None;
        }
        let steam_id = from_base36_u64(id_part)?;
        let generation = from_base36_u64(gen_part)?;
        Self::new(steam_id, generation)
    }
}

fn is_b36(part: &str, max_len: usize) -> bool {
    !part.is_empty()
        && part.len() <= max_len
        && part.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'z').contains(&b))
}

fn is_dec(part: &str, max_len: usize) -> bool {
    !part.is_empty() && part.len() <= max_len && part.bytes().all(|b| b.is_ascii_digit())
}

fn is_hex_lower(part: &str) -> bool {
    part.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn matches_java_examples() {
        // Java: Long.toUnsignedString(76561198000000000, 36)
        assert_eq!(to_base36(76561198000000000u128), "kxuogt5na4g");
        let token = [0u8; 16];
        let addr = SteamAddress::new(76561198000000000, token).unwrap();
        assert_eq!(addr.invite_string(), "s-kxuogt5na4g-0.steam");
        assert_eq!(SteamAddress::try_parse(&addr.invite_string()), Some(addr));
    }

    #[test]
    fn full_token_roundtrip() {
        let mut token = [0u8; 16];
        for (i, b) in token.iter_mut().enumerate() {
            *b = (i * 17 + 3) as u8;
        }
        let addr = SteamAddress::new(u64::MAX, token).unwrap();
        let s = addr.invite_string();
        assert_eq!(SteamAddress::try_parse(&s), Some(addr));
        assert_eq!(SteamAddress::try_parse(&format!("{s}.")), Some(addr));
        assert_eq!(SteamAddress::try_parse(&s.to_uppercase()), Some(addr));
        assert_eq!(SteamAddress::try_parse(&addr.legacy_string()), Some(addr));
    }

    #[test]
    fn rejects_invalid() {
        assert_eq!(SteamAddress::new(0, [1u8; 16]), None);
        assert_eq!(SteamAddress::try_parse("example.com"), None);
        assert_eq!(SteamAddress::try_parse("s-0-1.steam"), None);
        assert_eq!(SteamAddress::try_parse("s-1-!bad.steam"), None);
        assert_eq!(SteamAddress::try_parse("e4steam-1-deadbeef.steam"), None);
        assert_eq!(DedicatedAddress::try_parse("d-1-0.steam"), None);
        assert_eq!(DedicatedAddress::try_parse("s-1-1.steam"), None);
    }

    #[test]
    fn dedicated_descriptor() {
        let d = DedicatedAddress::new(76561198000000000, 42).unwrap();
        let s = d.descriptor();
        assert_eq!(s, "d-kxuogt5na4g-16.steam");
        assert_eq!(DedicatedAddress::try_parse(&s), Some(d));
    }

    #[test]
    fn base36_overflow_is_rejected() {
        assert_eq!(to_base36(u128::MAX), "f5lxx1zz5pnorynqglhzmsp33");
        assert_eq!(from_base36("f5lxx1zz5pnorynqglhzmsp33"), Some(u128::MAX));
        assert!(from_base36("f5lxx1zz5pnorynqglhzmsp34").is_none());
        assert!(from_base36(&"z".repeat(26)).is_none());
    }
}
