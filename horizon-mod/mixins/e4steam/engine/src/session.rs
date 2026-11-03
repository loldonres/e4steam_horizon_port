//! One thread drives Steam, so a console or the game itself only sends commands and reads lines.
//!
//! The Flat API is not thread-safe and `Runtime::tick` owns the whole Steam context, so the same
//! thread must keep running it between the calls. This is that thread: commands arrive over a
//! channel, everything a player should read leaves as a line, and neither side ever blocks.

use crate::address::SteamAddress;
use crate::chan::{self, Receiver, Sender, TryRecvError};
use crate::runtime::{self, Access, Runtime, Status, TICK};
use crate::steam::Steam;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;
use std::time::Duration;

/// Lines wait here while the game is busy; a stalled reader drops the oldest instead of growing.
pub const LINE_QUEUE: usize = 64;
/// How long `/steam host` waits for Horizon's LAN announce when the port is not handed to it.
pub const DEFAULT_DISCOVER: Duration = Duration::from_millis(4000);
/// The version a host publishes and a guest insists on; a Java friend must match it exactly.
pub const DEFAULT_MINECRAFT: &str = "26.3";
/// `/steam help` and a bad argument both end with this, so one line is the whole surface.
pub const USAGE: &str = "usage: /steam host [--port N] [--access friends|invite|custom|local] \
                         | /steam join [<invite code>|lobby <id>] [--port N] [--minecraft V] \
                         | /steam status | /steam stop";

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Command {
    Host {
        lan_port: Option<u16>,
        access: Access,
        discover: Duration,
        minecraft: String,
    },
    Join {
        invite: Option<String>,
        lobby: Option<u64>,
        local_port: u16,
        minecraft: String,
    },
    Stop,
    /// Answer with what the session is doing right now.
    AskStatus,
    /// Leave the lobby, end the thread.
    Quit,
}

/// What a player typed after `/steam`, read the way the Java mod's lobby reads it: one word per
/// value, order-free flags. A chat line is the only place this is used, so a wrong one says the
/// whole usage rather than a partial hint.
pub fn parse(text: &str) -> Result<Command, String> {
    let mut words = text.split_whitespace();
    let Some(name) = words.next() else {
        return Err(USAGE.to_string());
    };
    let mut port = None;
    let mut access = None;
    let mut lobby = None;
    let mut minecraft = None;
    let mut discover = DEFAULT_DISCOVER;
    let mut words_after_name: Vec<&str> = Vec::new();
    while let Some(word) = words.next() {
        let (flag, inline) = match word.split_once('=') {
            Some((flag, value)) => (flag, Some(value)),
            None => (word, None),
        };
        if !flag.starts_with('-') {
            words_after_name.push(word);
            continue;
        }
        let value = match inline {
            Some(value) => value,
            None => match words.next() {
                Some(value) => value,
                None => return Err(wrong(format!("{flag} wants a value"))),
            },
        };
        // `--port=25565` and `--port 25565` reach the same arm.
        match flag {
            "--port" | "-p" => port = Some(number(value, "port")?),
            "--access" | "-a" => access = Some(value),
            "--lobby" | "-l" => {
                lobby = Some(
                    value
                        .parse::<u64>()
                        .map_err(|_| wrong(format!("'{value}' is not a lobby id")))?,
                )
            }
            "--minecraft" | "-m" => minecraft = Some(value),
            "--discover" => {
                let millis = number(value, "milliseconds")?;
                if millis > 60_000 {
                    return Err(wrong("/steam host --discover waits at most 60000 milliseconds"));
                }
                discover = Duration::from_millis(u64::from(millis));
            }
            "--help" | "-h" => return Err(USAGE.to_string()),
            other => return Err(wrong(format!("{other} is not an option"))),
        }
    }
    let minecraft = minecraft.unwrap_or(DEFAULT_MINECRAFT).to_string();
    match name.to_ascii_lowercase().as_str() {
        "host" => {
            only("host", &words_after_name)?;
            Ok(Command::Host {
                lan_port: port,
                access: match access {
                    Some(access) => Access::parse(access).ok_or_else(|| {
                        wrong(format!(
                            "'{access}' is not an access mode; use friends, invite, custom or local"
                        ))
                    })?,
                    None => Access::FriendsOnly,
                },
                discover,
                minecraft,
            })
        }
        "join" => {
            // `join lobby 1234` names the lobby without a flag; anything else is taken as an invite.
            let mut positional = words_after_name.into_iter();
            let (invite, lobby_id) = match positional.next() {
                Some(word) if word.eq_ignore_ascii_case("lobby") => match positional.next() {
                    Some(id) => (
                        None,
                        Some(
                            id.parse::<u64>()
                                .map_err(|_| wrong(format!("'{id}' is not a lobby id")))?,
                        ),
                    ),
                    None => return Err(wrong("/steam join lobby <id> wants the lobby id")),
                },
                Some(code) => (Some(code.to_string()), None),
                None => (None, None),
            };
            // A word left over is a second code or an id the player meant to use; guessing which
            // would be worse than saying the line was not understood.
            if let Some(extra) = positional.next() {
                return Err(wrong(format!("'{extra}' is not something /steam join takes")));
            }
            Ok(Command::Join {
                invite,
                lobby: lobby.or(lobby_id),
                local_port: port.unwrap_or(0),
                minecraft,
            })
        }
        "stop" => {
            only("stop", &words_after_name)?;
            Ok(Command::Stop)
        }
        "status" => {
            only("status", &words_after_name)?;
            Ok(Command::AskStatus)
        }
        other => Err(wrong(format!("'{other}' is not a command"))),
    }
}

/// `stop` and `status` take no words, so an extra one is a typo worth naming rather than ignoring.
fn only(word: &str, words: &[&str]) -> Result<(), String> {
    match words.first() {
        Some(extra) => Err(wrong(format!(
            "'{extra}' is not something /steam {word} takes"
        ))),
        None => Ok(()),
    }
}

/// Ports and waits are small; anything the game cannot use is refused with the word the player typed.
fn number(value: &str, what: &str) -> Result<u16, String> {
    value
        .parse::<u16>()
        .map_err(|_| wrong(format!("'{value}' is not a {what} number")))
}

/// Every refusal carries the whole usage: a chat line is gone in seconds, so a player should not
/// have to ask twice for the options they got wrong.
fn wrong(reason: impl std::fmt::Display) -> String {
    format!("{USAGE}\n{reason}")
}

/// The Steam half of one game process. Dropping it stops the transport and joins its thread.
pub struct Session {
    commands: Sender<Command>,
    lines: Receiver<String>,
    worker: Mutex<Option<JoinHandle<()>>>,
    done: Arc<AtomicBool>,
}

impl Session {
    pub fn spawn() -> Self {
        let (command_tx, command_rx) = chan::unbounded();
        let (line_tx, line_rx) = chan::bounded(LINE_QUEUE);
        let done = Arc::new(AtomicBool::new(false));
        let finished = Arc::clone(&done);
        let worker = std::thread::Builder::new()
            .name("e4steam".to_string())
            .spawn(move || {
                run(command_rx, line_tx);
                finished.store(true, Ordering::Release);
            })
            .expect("the e4steam thread always starts");
        Self {
            commands: command_tx,
            lines: line_rx,
            worker: Mutex::new(Some(worker)),
            done,
        }
    }

    fn give(&self, command: Command) -> bool {
        self.commands.try_send(command).is_ok()
    }

    pub fn host(
        &self,
        lan_port: Option<u16>,
        access: Access,
        discover: Duration,
        minecraft: String,
    ) -> bool {
        self.give(Command::Host {
            lan_port,
            access,
            discover,
            minecraft,
        })
    }

    pub fn join(
        &self,
        invite: Option<String>,
        lobby: Option<u64>,
        local_port: u16,
        minecraft: String,
    ) -> bool {
        self.give(Command::Join {
            invite,
            lobby,
            local_port,
            minecraft,
        })
    }

    pub fn stop(&self) -> bool {
        self.give(Command::Stop)
    }

    pub fn ask_status(&self) -> bool {
        self.give(Command::AskStatus)
    }

    /// Everything said since the last call, oldest first. Never blocks.
    pub fn take_lines(&self) -> Vec<String> {
        let mut spoken = Vec::new();
        while let Ok(line) = self.lines.try_recv() {
            spoken.push(line);
            if spoken.len() >= LINE_QUEUE {
                break;
            }
        }
        spoken
    }

    pub fn is_alive(&self) -> bool {
        !self.done.load(Ordering::Acquire)
    }

    /// Stops the transport and waits for its thread, so Steam is shut down before the caller moves on.
    pub fn close(&self) {
        self.give(Command::Quit);
        // The worker's own `Drop` of the runtime can park briefly; the queue is already closed, so
        // the thread only finishes its current tick.
        if let Ok(mut worker) = self.worker.lock() {
            if let Some(thread) = worker.take() {
                let _ = thread.join();
            }
        }
    }
}

impl Drop for Session {
    fn drop(&mut self) {
        // Releasing the sender is enough for the loop to end; joining is the caller's choice.
        let _ = self.commands.try_send(Command::Quit);
    }
}

fn run(commands: Receiver<Command>, lines: Sender<String>) {
    let mut world = World::default();
    loop {
        let mut quit = false;
        loop {
            match commands.try_recv() {
                Ok(Command::Host { lan_port, access, discover, minecraft }) => {
                    world.stop(&lines);
                    world.start_host(&lines, lan_port, access, discover, minecraft);
                }
                Ok(Command::Join { invite, lobby, local_port, minecraft }) => {
                    world.stop(&lines);
                    world.start_guest(invite, lobby, local_port, minecraft, &lines);
                }
                Ok(Command::Stop) => world.stop(&lines),
                Ok(Command::AskStatus) => world.status(&lines),
                Ok(Command::Quit) => {
                    quit = true;
                    break;
                }
                Err(TryRecvError::Empty) => break,
                // The session is gone, so nobody could start or stop anything again.
                Err(TryRecvError::Disconnected) => {
                    quit = true;
                    break;
                }
            }
        }
        if quit {
            world.stop(&lines);
            return;
        }
        world.tick(&lines);
        std::thread::sleep(TICK);
    }
}

#[derive(Default)]
struct World {
    runtime: Option<Runtime>,
    bridges: usize,
    lobby: u64,
}

impl World {
    fn start_host(
        &mut self,
        lines: &Sender<String>,
        lan_port: Option<u16>,
        access: Access,
        discover: Duration,
        minecraft: String,
    ) {
        let port = match lan_port {
            Some(port) => port,
            None => match discover_lan_port(discover) {
                Ok(port) => {
                    say(lines, format!("Horizon's LAN world is on 127.0.0.1:{port}"));
                    port
                }
                Err(error) => {
                    say(lines, error);
                    return;
                }
            },
        };
        let steam = match open_steam(lines) {
            Some(steam) => steam,
            None => return,
        };
        let steam_id = steam.my_steam_id();
        let runtime = Runtime::host(steam, access, port, minecraft);
        let hosted = runtime.lan_port();
        say(
            lines,
            format!(
                "Steam id {} hosts 127.0.0.1:{hosted}; access is '{}'",
                steam_id_tail(steam_id),
                access_label(access)
            ),
        );
        match runtime.invite_endpoint() {
            Some(endpoint) => {
                say(lines, format!("invite code: {endpoint}"));
                say(lines, "keep it private: the code is the ticket into your world.");
            }
            None => say(lines, "no invite code is available for this access mode."),
        }
        self.bridges = 0;
        self.lobby = 0;
        self.runtime = Some(runtime);
    }

    fn start_guest(
        &mut self,
        invite: Option<String>,
        lobby: Option<u64>,
        local_port: u16,
        minecraft: String,
        lines: &Sender<String>,
    ) {
        let steam = match open_steam(lines) {
            Some(steam) => steam,
            None => return,
        };
        let mut runtime = match (invite.as_deref(), lobby) {
            (Some(text), _) => match SteamAddress::try_parse(text.trim()) {
                Some(target) => Runtime::guest(steam, target, lobby.unwrap_or(0), minecraft),
                None => {
                    say(
                        lines,
                        "that is not an e4steam invite; expected s-<steamid>-<token>.steam".to_string(),
                    );
                    return;
                }
            },
            (None, Some(lobby)) => Runtime::guest_via_lobby(steam, lobby, minecraft),
            (None, None) => Runtime::await_invite(steam, minecraft),
        };
        if let Err(error) = runtime.bind_local(local_port) {
            say(lines, redact(&error));
            return;
        }
        say(
            lines,
            format!(
                "Minecraft → Multiplayer → Direct Connect → 127.0.0.1:{}",
                runtime.local_port()
            ),
        );
        if invite.is_none() && lobby.is_none() {
            say(lines, "waiting for a Steam invite from a friend…");
        }
        self.bridges = 0;
        self.lobby = 0;
        self.runtime = Some(runtime);
    }

    fn stop(&mut self, lines: &Sender<String>) {
        if let Some(mut runtime) = self.runtime.take() {
            runtime.shutdown();
            say(lines, "stopped.");
        }
        self.bridges = 0;
        self.lobby = 0;
    }

    fn status(&self, lines: &Sender<String>) {
        match &self.runtime {
            None => say(lines, "nothing is shared right now."),
            Some(runtime) => say(
                lines,
                format!(
                    "{} of {} bridges up, lobby {}",
                    runtime.active_bridges(),
                    runtime::MAX_HOST_CONNECTIONS,
                    if runtime.lobby() == 0 {
                        "none".to_string()
                    } else {
                        runtime.lobby().to_string()
                    }
                ),
            ),
        }
    }

    fn tick(&mut self, lines: &Sender<String>) {
        let mut ended: Option<String> = None;
        if let Some(runtime) = self.runtime.as_mut() {
            runtime.tick();
            let live = runtime.active_bridges();
            if live != self.bridges {
                self.bridges = live;
                say(lines, format!("steam bridges open: {live}"));
            }
            let lobby = runtime.lobby();
            if lobby != 0 && lobby != self.lobby {
                self.lobby = lobby;
                say(
                    lines,
                    format!("lobby id {lobby} — friends can join with /steam join lobby {lobby}"),
                );
            }
            if !runtime.is_running() {
                ended = Some(match runtime.status() {
                    Status::Running => String::new(),
                    Status::Stopped(reason) => reason.clone(),
                });
            }
        }
        // The borrow above has to end before the shutdown can take the runtime out of `self`.
        if let Some(reason) = ended {
            self.stop(lines);
            if !reason.is_empty() {
                say(lines, format!("reason: {}", redact(&reason)));
            }
        }
    }
}

fn open_steam(lines: &Sender<String>) -> Option<Steam> {
    match Steam::init() {
        Ok(steam) => Some(steam),
        Err(error) => {
            say(lines, format!("Steam is not usable: {}", redact(&error)));
            say(lines, "hint: start Steam, log in, and keep steam_api64.dll where the game finds it.");
            None
        }
    }
}

fn discover_lan_port(timeout: Duration) -> Result<u16, String> {
    let address = crate::lan::local_ipv4()
        .ok_or_else(|| "no LAN IPv4 address; open the world to LAN in Horizon".to_string())?;
    runtime::detect_lan_port(address, timeout)
        .map_err(|error| format!("{error}; open the world to LAN in Horizon first"))
}

fn say(lines: &Sender<String>, text: impl Into<String>) {
    // A reader that stopped draining must not stall the Steam thread.
    let _ = lines.try_send(text.into());
}

/// Steam ids are long; keep the tail so a player can recognise their own account.
pub fn steam_id_tail(steam_id: u64) -> String {
    let text = steam_id.to_string();
    if text.len() <= 4 {
        return text;
    }
    format!("…{}", &text[text.len() - 4..])
}

/// The name a player typed for this mode, so chat reads back what `/steam host --access` said.
fn access_label(access: Access) -> &'static str {
    match access {
        Access::LocalOnly => "local",
        Access::FriendsOnly => "friends",
        Access::InviteOnly => "invite",
        Access::Custom => "custom",
    }
}

/// `Doctor.redactDiagnostic`: join addresses, foreign steam ids and named secrets never reach a log.
pub fn redact(value: &str) -> String {
    const KEYS: [&str; 7] =
        ["token", "ticket", "password", "secret", "cookie", "authorization", "gslt"];
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
        assert_eq!(steam_id_tail(76561198000000000), "…0000");
        assert_eq!(steam_id_tail(12), "12");
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
        assert_eq!(redact("join s-abcd12-eeff00.steam now"), "join <redacted-join-address> now");
        assert_eq!(redact("peer 76561198000000000 left"), "peer <redacted-steam-id> left");
        assert_eq!(redact("port 25565 stayed"), "port 25565 stayed");
    }

    #[test]
    fn a_host_command_reads_its_flags_in_any_order() {
        assert_eq!(
            parse("host").unwrap(),
            Command::Host {
                lan_port: None,
                access: Access::FriendsOnly,
                discover: DEFAULT_DISCOVER,
                minecraft: DEFAULT_MINECRAFT.to_string(),
            }
        );
        assert_eq!(
            parse("host --access=invite --port 25565").unwrap(),
            Command::Host {
                lan_port: Some(25565),
                access: Access::InviteOnly,
                discover: DEFAULT_DISCOVER,
                minecraft: DEFAULT_MINECRAFT.to_string(),
            }
        );
        assert_eq!(
            parse("HOST -m 26.3 --discover 250").unwrap(),
            Command::Host {
                lan_port: None,
                access: Access::FriendsOnly,
                discover: Duration::from_millis(250),
                minecraft: "26.3".to_string(),
            }
        );
    }

    #[test]
    fn a_join_takes_a_code_a_lobby_or_nothing_at_all() {
        assert_eq!(
            parse("join s-abcd12-eeff00.steam").unwrap(),
            Command::Join {
                invite: Some("s-abcd12-eeff00.steam".to_string()),
                lobby: None,
                local_port: 0,
                minecraft: DEFAULT_MINECRAFT.to_string(),
            }
        );
        assert_eq!(
            parse("join lobby 4389261425 --port 25566").unwrap(),
            Command::Join {
                invite: None,
                lobby: Some(4389261425),
                local_port: 25566,
                minecraft: DEFAULT_MINECRAFT.to_string(),
            }
        );
        // No code and no lobby: wait for a Steam invite, the way the Java mod's button does.
        assert_eq!(
            parse("join").unwrap(),
            Command::Join {
                invite: None,
                lobby: None,
                local_port: 0,
                minecraft: DEFAULT_MINECRAFT.to_string(),
            }
        );
    }

    #[test]
    fn a_wrong_word_repeats_the_usage() {
        for typed in [
            "",
            "hop",
            "host --access nobody",
            "host --port abc",
            "host --port",
            "host --discover 90000000",
            "host 25565",
            "join lobby",
            "join s-abcd.steam lobby 12",
            "stop now",
        ] {
            let error = parse(typed).unwrap_err();
            assert!(error.starts_with("usage:"), "{typed} → {error}");
        }
        assert!(parse("hop").unwrap_err().contains("'hop' is not a command"));
        assert_eq!(parse("stop").unwrap(), Command::Stop);
        assert_eq!(parse("status").unwrap(), Command::AskStatus);
    }

    #[test]
    fn a_session_with_nothing_running_reports_itself() {
        // Neither Steam nor a LAN world is touched here: the worker answers `status` from its own
        // thread, so a command never has to wait for the game to do anything.
        let session = Session::spawn();
        assert!(session.ask_status());
        let mut spoken = Vec::new();
        for _ in 0..400 {
            spoken.extend(session.take_lines());
            if !spoken.is_empty() {
                break;
            }
            std::thread::sleep(Duration::from_millis(10));
        }
        assert_eq!(spoken, vec!["nothing is shared right now.".to_string()]);
        session.close();
        assert!(!session.is_alive());
    }

    #[test]
    fn dropping_the_session_ends_the_thread() {
        let session = Session::spawn();
        assert!(session.is_alive());
        let done = Arc::clone(&session.done);
        drop(session);
        for _ in 0..400 {
            if done.load(Ordering::Acquire) {
                return;
            }
            std::thread::sleep(Duration::from_millis(10));
        }
        panic!("the e4steam thread outlived its session");
    }
}
