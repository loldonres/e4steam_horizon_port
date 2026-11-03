//! Horizon sidecar: shares the local Minecraft LAN world over Steam peer-to-peer, speaking the same
//! E4ST v4 wire protocol as the Java `e4steam` mod so both games can play together.

use e4steam_engine::address::SteamAddress;
use e4steam_engine::runtime::{Access, Runtime, Status};
use e4steam_engine::steam::Steam;
use e4steam_engine::{frame, lan, runtime, steam};
use clap::{Parser, Subcommand};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Duration;

#[derive(Parser)]
#[command(
    name = "e4steam-rs",
    version,
    about = "Play a Horizon LAN world with Steam friends",
    long_about = "Bridges 127.0.0.1 Minecraft traffic onto Steam P2P using the e4steam wire protocol, \
                  so Java players running the e4steam mod and Horizon players meet in one world."
)]
struct Cli {
    #[command(subcommand)]
    command: Command,
}

#[derive(Subcommand)]
enum Command {
    /// Share the LAN world Horizon is already hosting with Steam friends.
    Host {
        /// Who gets in: friends, invite, custom or local.
        #[arg(long, default_value = "friends")]
        access: String,
        /// Horizon's LAN port; read from the LAN multicast announce when omitted.
        #[arg(long)]
        port: Option<u16>,
        /// How long to wait for Horizon's LAN announce, in milliseconds.
        #[arg(long, default_value = "4000")]
        discover_ms: u64,
        /// Published as the lobby's e4steam_minecraft value; Java friends must match it exactly.
        #[arg(long, default_value = "26.3")]
        minecraft: String,
    },
    /// Join a hosted world, then point Minecraft at the printed 127.0.0.1 port.
    Join {
        /// Invite code such as s-<steamid>-<token>.steam. Omit to wait for a Steam invite.
        address: Option<String>,
        /// Join through a Steam lobby id instead of a code.
        #[arg(long)]
        lobby: Option<u64>,
        /// Local port Minecraft connects to; a free one is chosen when omitted.
        #[arg(long, default_value = "0")]
        local_port: u16,
        /// Only join a host publishing this Minecraft version.
        #[arg(long, default_value = "26.3")]
        minecraft: String,
    },
    /// Report what the sidecar can see: Steam, the app id and Horizon's LAN announce.
    Doctor,
}

fn main() -> std::process::ExitCode {
    match Cli::parse().command {
        Command::Host { access, port, discover_ms, minecraft } => {
            host(access, port, discover_ms, minecraft)
        }
        Command::Join { address, lobby, local_port, minecraft } => {
            join(address, lobby, local_port, minecraft)
        }
        Command::Doctor => doctor(),
    }
}

fn stop_flag() -> Arc<AtomicBool> {
    let stop = Arc::new(AtomicBool::new(false));
    let shared = Arc::clone(&stop);
    if ctrlc::set_handler(move || shared.store(true, Ordering::SeqCst)).is_err() {
        eprintln!("note: Ctrl-C cannot be trapped here, so the lobby is left to expire on its own");
    }
    stop
}

fn run(runtime: &mut Runtime) -> std::process::ExitCode {
    let stop = stop_flag();
    let mut bridges = 0usize;
    let mut lobby = 0u64;
    while runtime.is_running() && !stop.load(Ordering::SeqCst) {
        runtime.tick();
        let live = runtime.active_bridges();
        if live != bridges {
            bridges = live;
            println!("steam bridges open: {live}");
        }
        let current = runtime.lobby();
        if current != 0 && current != lobby {
            lobby = current;
            println!("lobby id {current} — a friend can also join with --lobby {current}");
        }
        std::thread::sleep(runtime::TICK);
    }
    runtime.shutdown();
    match runtime.status() {
        Status::Running => {
            println!("stopped.");
            std::process::ExitCode::SUCCESS
        }
        Status::Stopped(reason) => {
            eprintln!("stopped: {}", redact(reason));
            std::process::ExitCode::FAILURE
        }
    }
}

fn init_steam() -> Result<Steam, std::process::ExitCode> {
    Steam::init().map_err(|error| {
        eprintln!("Steam is not usable: {error}");
        eprintln!("hint: start Steam, log in, and keep steam_api64.dll next to this program.");
        std::process::ExitCode::FAILURE
    })
}

fn host(
    access: String,
    port: Option<u16>,
    discover_ms: u64,
    minecraft: String,
) -> std::process::ExitCode {
    let Some(mode) = Access::parse(&access) else {
        eprintln!("unknown access mode '{access}'; use friends, invite, custom or local");
        return std::process::ExitCode::FAILURE;
    };
    let lan_port = match port {
        Some(port) => port,
        None => match find_lan_port(Duration::from_millis(discover_ms)) {
            Ok(port) => {
                println!("Horizon's LAN world is on 127.0.0.1:{port}");
                port
            }
            Err(error) => {
                eprintln!("{error}");
                return std::process::ExitCode::FAILURE;
            }
        },
    };
    let steam = match init_steam() {
        Ok(steam) => steam,
        Err(code) => return code,
    };
    let mut runtime = Runtime::host(steam, mode, lan_port, minecraft);
    let hosted = runtime.lan_port();
    println!("Steam id {} hosts 127.0.0.1:{hosted}; access is '{access}'.", steam_id_text(runtime.my_steam_id()));
    match runtime.invite_endpoint() {
        Some(endpoint) => {
            println!("invite code: {endpoint}");
            println!("keep it private: the code is the ticket into your world.");
        }
        None => println!("no invite code is available for this access mode."),
    }
    run(&mut runtime)
}

fn join(
    address: Option<String>,
    lobby: Option<u64>,
    local_port: u16,
    minecraft: String,
) -> std::process::ExitCode {
    let steam = match init_steam() {
        Ok(steam) => steam,
        Err(code) => return code,
    };
    let mut runtime = match (address.as_deref(), lobby) {
        (Some(text), _) => match SteamAddress::try_parse(text.trim()) {
            Some(target) => Runtime::guest(steam, target, lobby.unwrap_or(0), minecraft),
            None => {
                eprintln!("'{text}' is not an e4steam invite; expected s-<steamid>-<token>.steam");
                return std::process::ExitCode::FAILURE;
            }
        },
        (None, Some(lobby)) => Runtime::guest_via_lobby(steam, lobby, minecraft),
        (None, None) => Runtime::await_invite(steam, minecraft),
    };
    if let Err(error) = runtime.bind_local(local_port) {
        eprintln!("{error}");
        return std::process::ExitCode::FAILURE;
    }
    println!("Minecraft → Multiplayer → Direct Connect → 127.0.0.1:{}", runtime.local_port());
    if lobby.is_none() && address.is_none() {
        println!("waiting for a Steam invite from a friend…");
    }
    run(&mut runtime)
}

fn doctor() -> std::process::ExitCode {
    println!("steam dll: {}", match steam::locate_steam_dll() {
        Ok(path) => path.display().to_string(),
        Err(error) => format!("not found ({error})"),
    });
    println!("app id file: {}", match steam::ensure_app_id_file() {
        Ok(()) => format!("written for app id {}", steam::APP_ID),
        Err(error) => format!("failed ({error})"),
    });
    let address = lan::local_ipv4();
    println!("lan interface: {}", match address {
        Some(address) => address.to_string(),
        None => "not found".to_string(),
    });
    let timeout = Duration::from_millis(4000);
    println!("lan world announce: {}", match address {
        Some(address) => match runtime::detect_lan_port(address, timeout) {
            Ok(port) => format!("found on port {port}"),
            Err(error) => error,
        },
        None => "skipped, no LAN address".to_string(),
    });
    let steam = match init_steam() {
        Ok(steam) => steam,
        Err(code) => return code,
    };
    println!("steam id: {}", steam_id_text(steam.my_steam_id()));
    println!("persona: {}", steam.persona_name(steam.my_steam_id()));
    println!("lobby capacity: {}, wire protocol v{}", steam::LOBBY_CAPACITY, frame::VERSION);
    std::process::ExitCode::SUCCESS
}

fn find_lan_port(timeout: Duration) -> Result<u16, String> {
    let address =
        lan::local_ipv4().ok_or_else(|| "no LAN IPv4 address; pass --port".to_string())?;
    runtime::detect_lan_port(address, timeout)
        .map_err(|error| format!("{error}; open the world to LAN in Horizon or pass --port"))
}

/// Steam ids are long; keep the tail so a player can recognise their own account.
fn steam_id_text(steam_id: u64) -> String {
    let text = steam_id.to_string();
    if text.len() <= 4 {
        return text;
    }
    format!("…{}", &text[text.len() - 4..])
}

/// `Doctor.redactDiagnostic`: join addresses, foreign steam ids and named secrets never reach a log.
fn redact(value: &str) -> String {
    const KEYS: [&str; 7] = ["token", "ticket", "password", "secret", "cookie", "authorization", "gslt"];
    let mut hidden = String::with_capacity(value.len());
    let mut hide_next = false;
    for word in value.split_inclusive(char::is_whitespace) {
        let without_trailing = word.trim_end();
        let spacing = &word[without_trailing.len()..];
        let token = without_trailing.trim_start();
        if token.is_empty() {
            hidden.push_str(word);
            continue;
        }
        let lowered = token.to_ascii_lowercase();
        let mut swallow_spacing = false;
        if hide_next {
            hidden.push_str("<redacted>");
            hide_next = false;
        } else if lowered.contains(".steam") {
            hidden.push_str("<redacted-join-address>");
        } else if let Some(key) = KEYS.iter().find(|key| {
            lowered.starts_with(**key)
                && match lowered.as_bytes().get(key.len()) {
                    Some(b':') | Some(b'=') | None => true,
                    Some(_) => false,
                }
        }) {
            // "token: abc123" and "token abc123" hide the value in the next word; "token=abc" hides it here.
            hidden.push_str(*key);
            hidden.push('=');
            let tail = &lowered[key.len()..];
            let attached_value = match tail.strip_prefix(|c| c == ':' || c == '=') {
                Some(rest) => !rest.is_empty(),
                None => false,
            };
            if attached_value {
                hidden.push_str("<redacted>");
            } else {
                hide_next = true;
                swallow_spacing = true;
            }
        } else {
            hidden.push_str(&mask_steam_ids(token));
        }
        if !swallow_spacing {
            hidden.push_str(spacing);
        }
    }
    hidden
}

fn mask_steam_ids(token: &str) -> String {
    let mut masked = String::with_capacity(token.len());
    let mut digits = token.chars().peekable();
    while let Some(character) = digits.next() {
        if character.is_ascii_digit() {
            let mut run = String::from(character);
            while let Some(next) = digits.peek() {
                if next.is_ascii_digit() {
                    run.push(*next);
                    digits.next();
                } else {
                    break;
                }
            }
            if run.len() == 17 && run.starts_with("7656") {
                masked.push_str("<redacted-steam-id>");
            } else {
                masked.push_str(&run);
            }
        } else {
            masked.push(character);
        }
    }
    masked
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn steam_ids_are_shortened_but_still_readable() {
        assert_eq!(steam_id_text(76561198000000000), "…0000");
        assert_eq!(steam_id_text(12), "12");
    }

    #[test]
    fn plain_messages_survive() {
        assert_eq!(redact("host is on 26.3"), "host is on 26.3");
        assert_eq!(redact("  spaced   out  "), "  spaced   out  ");
    }

    #[test]
    fn named_secrets_lose_their_value() {
        assert_eq!(redact("bad token: abc123"), "bad token=<redacted>");
        assert_eq!(redact("ticket=xyz"), "ticket=<redacted>");
    }

    #[test]
    fn join_addresses_and_peer_ids_are_dropped() {
        assert_eq!(
            redact("join s-abcd12-eeff00.steam now"),
            "join <redacted-join-address> now"
        );
        assert_eq!(redact("peer 76561198000000000 left"), "peer <redacted-steam-id> left");
        assert_eq!(redact("port 25565 stayed"), "port 25565 stayed");
    }
}
