// e4steam: share the world Horizon already opens with Steam friends.
//
// horizon-mixins injects this file as `crate::game::e4steam`, so it stays glue: the wire protocol,
// the Steam runtime and the `/steam` grammar live in the mod's own `e4steam_engine` crate, and the
// network work runs on that crate's thread rather than on the game's.

/// One session per game process, made the first time a player types `/steam`.
static SESSION: ::std::sync::OnceLock<::e4steam_engine::session::Session> =
    ::std::sync::OnceLock::new();

fn session() -> &'static ::e4steam_engine::session::Session {
    SESSION.get_or_init(::e4steam_engine::session::Session::spawn)
}

/// A command typed in chat, without its slash: only `steam …` is ours, so anything else is the
/// game's and runs untouched.
fn steam_args(command: &str) -> Option<&str> {
    let command = command.trim();
    let (word, rest) = match command.split_once(char::is_whitespace) {
        Some((word, rest)) => (word, rest),
        None => (command, ""),
    };
    if word.eq_ignore_ascii_case("steam") {
        Some(rest)
    } else {
        None
    }
}

/// The port of the world open to LAN, so `/steam host` needs no numbers typed. Nothing is shared
/// until Horizon has a LAN world to share.
fn lan_port(game: &crate::game::Game<'_>) -> Option<u16> {
    game.lan.as_ref().map(|host| host.settings.port)
}

/// `pub(super)`: `Game` is private to `crate::game`, so a crate-wide entry point would name a type
/// less visible than itself. The helpers reach these through their parent.
pub(super) fn on_command(game: &mut crate::game::Game<'_>, command: &str, ran: &mut bool) {
    let Some(args) = steam_args(command) else {
        return;
    };
    // Taking the word means `/steam …` never reaches the server as an unknown vanilla command.
    *ran = true;
    let parsed = match ::e4steam_engine::session::parse(args) {
        Ok(command) => command,
        Err(usage) => {
            for line in usage.split('\n') {
                game.native_chat(line);
            }
            return;
        }
    };
    use ::e4steam_engine::session::Command;
    let sent = match parsed {
        Command::Host {
            lan_port: port,
            access,
            discover,
            minecraft,
        } => session().host(
            port.or_else(|| lan_port(game)),
            access,
            discover,
            minecraft,
        ),
        Command::Join {
            invite,
            lobby,
            local_port,
            minecraft,
        } => session().join(invite, lobby, local_port, minecraft),
        Command::Stop => session().stop(),
        Command::AskStatus => session().ask_status(),
        // `/steam` never asks for this: the thread lives as long as the window does.
        Command::Quit => false,
    };
    if !sent {
        game.native_chat("e4steam is no longer running; restart Horizon to share again.");
    }
}

/// What the Steam thread has to say, one chat line at a time.
pub(super) fn on_frame(game: &mut crate::game::Game<'_>) {
    // Only the window's game has a chat box; the server thread's copy would read the queue dry and
    // show nothing.
    if !game.native_window() {
        return;
    }
    let Some(session) = SESSION.get() else {
        return;
    };
    for line in session.take_lines() {
        game.native_chat(&line);
    }
}

/// The chat box colours `/steam` and completes it like the game's own commands (API 35).
pub(super) fn on_commands(commands: &mut Vec<&'static str>) {
    commands.push("steam");
}
