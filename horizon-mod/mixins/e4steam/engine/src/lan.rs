//! Horizon announces its LAN worlds on multicast 224.0.2.60:4445 every 1500 ms with
//! `[MOTD]…[/MOTD][AD]<host:port>[/AD]`, and its LAN port is OS-assigned, so the sidecar
//! learns it here instead of asking the player to read logs.

use std::io;
use std::net::{IpAddr, Ipv4Addr, SocketAddr, ToSocketAddrs, UdpSocket};
use std::time::Duration;

pub const GROUP: Ipv4Addr = Ipv4Addr::new(224, 0, 2, 60);
pub const PORT: u16 = 4445;
pub const INTERVAL: Duration = Duration::from_millis(1500);
const MAX_PACKET: usize = 2048;

/// std cannot enumerate interfaces, so ask the OS which source address it would use for an
/// off-link peer; an unconnected UDP socket never sends a packet.
pub fn local_ipv4() -> Option<Ipv4Addr> {
    let socket = UdpSocket::bind((Ipv4Addr::UNSPECIFIED, 0)).ok()?;
    socket.connect(("8.8.8.8", 53)).ok()?;
    match socket.local_addr().ok()?.ip() {
        IpAddr::V4(address) if !address.is_loopback() => Some(address),
        _ => None,
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct LanAnnounce {
    pub motd: String,
    pub address: String,
}

impl LanAnnounce {
    pub fn target(&self) -> Option<SocketAddr> {
        split_host_port(&self.address, 25565)
    }
}

/// Mirrors `horizon-client/src/lan/discovery.rs` payload format.
pub fn parse_announce(raw: &[u8]) -> Option<LanAnnounce> {
    let text = std::str::from_utf8(raw).ok()?.trim();
    let motd = between(text, "[MOTD]", "[/MOTD]")?;
    let address = between(text, "[AD]", "[/AD]")?;
    if address.is_empty() {
        return None;
    }
    Some(LanAnnounce {
        motd: motd.to_string(),
        address: address.to_string(),
    })
}

fn between<'a>(text: &'a str, open: &str, close: &str) -> Option<&'a str> {
    let start = text.find(open)? + open.len();
    let end = text[start..].find(close)?;
    Some(&text[start..start + end])
}

pub fn split_host_port(address: &str, default_port: u16) -> Option<SocketAddr> {
    let address = address.trim();
    if address.is_empty() {
        return None;
    }
    if let Ok(parsed) = address.parse::<SocketAddr>() {
        return prefer_ipv4(vec![parsed]);
    }
    let with_port = if address.contains(':') && !address.starts_with('[') {
        address.to_string()
    } else {
        format!("{address}:{default_port}")
    };
    prefer_ipv4(with_port.to_socket_addrs().ok()?.collect())
}

/// Horizon's LAN listener binds IPv4, so a `localhost` that resolves to `::1` first must not
/// win the lookup.
fn prefer_ipv4(mut addrs: Vec<SocketAddr>) -> Option<SocketAddr> {
    let ipv4 = addrs.iter().position(|a| a.is_ipv4());
    match ipv4 {
        Some(index) => Some(addrs.remove(index)),
        None => addrs.pop(),
    }
}

pub struct Listener {
    socket: UdpSocket,
}

impl Listener {
    /// Binds the multicast port so the sidecar sees its own game's announcements too.
    pub fn bind() -> io::Result<Self> {
        let socket = UdpSocket::bind((Ipv4Addr::UNSPECIFIED, PORT))?;
        socket.join_multicast_v4(&GROUP, &Ipv4Addr::UNSPECIFIED)?;
        socket.set_read_timeout(Some(INTERVAL))?;
        Ok(Self { socket })
    }

    /// Waits for a LAN announce addressed at `host`, up to `timeout`.
    pub fn wait_for(&self, host: Ipv4Addr, timeout: Duration) -> io::Result<Option<LanAnnounce>> {
        let deadline = std::time::Instant::now() + timeout;
        let mut buf = [0u8; MAX_PACKET];
        loop {
            match self.socket.recv(&mut buf) {
                Ok(read) => {
                    if let Some(announce) = parse_announce(&buf[..read]) {
                        if announce
                            .target()
                            .is_some_and(|target| target.ip() == IpAddr::V4(host))
                        {
                            return Ok(Some(announce));
                        }
                    }
                }
                // A receive timeout is how Windows reports "nothing announced yet" (WSAETIMEDOUT).
                Err(err)
                    if err.kind() == io::ErrorKind::WouldBlock
                        || err.kind() == io::ErrorKind::TimedOut => {}
                Err(err) => return Err(err),
            }
            if std::time::Instant::now() >= deadline {
                return Ok(None);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_horizon_payload() {
        let raw = b"[MOTD]@Ignis[/MOTD][AD]192.168.1.20:51234[/AD]";
        let announce = parse_announce(raw).unwrap();
        assert_eq!(announce.motd, "@Ignis");
        assert_eq!(
            announce.target(),
            Some("192.168.1.20:51234".parse().unwrap())
        );
    }

    #[test]
    fn defaults_missing_port_and_rejects_junk() {
        assert_eq!(
            split_host_port("127.0.0.1", 25565),
            Some("127.0.0.1:25565".parse().unwrap())
        );
        assert_eq!(
            split_host_port("localhost:25580", 25565),
            Some("127.0.0.1:25580".parse().unwrap())
        );
        assert_eq!(
            split_host_port("[::1]:25568", 25565),
            Some("[::1]:25568".parse().unwrap())
        );
        assert_eq!(split_host_port("1.2.3.4:99999", 25565), None);
        assert_eq!(parse_announce(b"no tags here"), None);
        assert_eq!(parse_announce(b"[MOTD]x[/MOTD][AD][/AD]"), None);
    }
}
