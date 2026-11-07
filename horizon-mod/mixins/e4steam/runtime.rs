// e4steam: share the world Horizon already opens with Steam friends.
//
// horizon-mixins injects this file as `crate::game::e4steam`, so it stays glue: the wire protocol,
// the Steam runtime and the `/steam` grammar live in the mod's own `e4steam_engine` crate, and the
// network work runs on that crate's thread rather than on the game's.
//
// A player reaches the same session two ways: the `/steam` word in chat, and the mod's own page in
// Options > Mods, which Horizon opens from the pause menu. The page is what an ordinary player uses.

/// One session per game process, made the first time a player types `/steam` or opens the page.
static SESSION: ::std::sync::OnceLock<::e4steam_engine::session::Session> =
    ::std::sync::OnceLock::new();

/// The access the next shared world allows, chosen on the page between presses.
static ACCESS: ::std::sync::Mutex<::e4steam_engine::runtime::Access> =
    ::std::sync::Mutex::new(::e4steam_engine::runtime::Access::FriendsOnly);

/// `[mod] id` in the bundle: Horizon names the page with it.
const MOD_ID: &str = "e4steam";

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

/// The port of the world open to LAN, so neither `/steam host` nor the page needs numbers typed.
/// Nothing is shared until Horizon has a LAN world to share.
fn lan_port(game: &crate::game::Game<'_>) -> Option<u16> {
    game.lan.as_ref().map(|host| host.settings.port)
}

/// The version the session publishes and insists on, the same one the chat commands use.
fn minecraft() -> String {
    ::e4steam_engine::session::DEFAULT_MINECRAFT.to_string()
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
    tell(game, sent);
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

/// The row Options > Mods shows, and the line above the page's buttons: both read the state the
/// Steam thread publishes, so nothing is asked of it and the screen never waits.
pub(super) fn on_mods(mods: &mut Vec<crate::game::native_hooks::NativeMod>) {
    use ::e4steam_engine::menu;
    let view = session().view();
    let lang = language();
    mods.push(crate::game::native_hooks::NativeMod {
        id: MOD_ID.to_string(),
        title: menu::title(lang, &view),
        description: menu::description(lang, &view),
        // Key rows are Horizon's own to bind; this mod answers to presses and to `/steam`.
        keys: Vec::new(),
    });
}

/// The page's buttons, rebuilt every time Horizon asks: opening it and every press both re-read it,
/// so a label cannot stay behind on the state it was drawn for.
pub(super) fn on_settings(game: &mut crate::game::Game<'_>, mod_id: &str, buttons: &mut Vec<String>) {
    if mod_id != MOD_ID {
        return;
    }
    let view = session().view();
    for row in ::e4steam_engine::menu::rows(language(), &prefs(), &view, lan_port(game)) {
        buttons.push(row.label);
    }
}

/// Button `button` of the page was pressed. `press` rebuilds the rows, which is what ties the index
/// to the label the player actually saw.
pub(super) fn on_press(game: &mut crate::game::Game<'_>, mod_id: &str, button: usize) {
    if mod_id != MOD_ID {
        return;
    }
    let choice = prefs();
    let view = session().view();
    let Some(action) = ::e4steam_engine::menu::press(choice.lang, &choice, &view, lan_port(game), button)
    else {
        // The state moved between the drawing and the click; the row is no longer ours to run.
        return;
    };
    run(game, choice, action, &view);
}

/// One press, put onto the session the same way a chat command is.
fn run(
    game: &mut crate::game::Game<'_>,
    choice: ::e4steam_engine::menu::Prefs,
    action: ::e4steam_engine::menu::Action,
    view: &::e4steam_engine::menu::View,
) {
    use ::e4steam_engine::menu::{Action, Said};
    match action {
        Action::Host => tell(
            game,
            session().host(lan_port(game), choice.access, ::e4steam_engine::session::DEFAULT_DISCOVER, minecraft()),
        ),
        // No code: Steam hands the lobby over when the friend invites us, as the Java mod does.
        Action::WaitInvite => tell(game, session().join(None, None, 0, minecraft())),
        Action::Status => tell(game, session().ask_status()),
        Action::Stop => tell(game, session().stop()),
        Action::CycleAccess => choose_access(choice.access),
        Action::CycleLang => save_language(choice.lang.next()),
        Action::ShowInvite => match &view.invite {
            Some(code) => {
                game.native_chat(&format!("invite code: {code}"));
                say(game, choice.lang, Said::InviteSecret);
            }
            None => say(game, choice.lang, Said::NoInvite),
        },
        Action::ShowAddress => {
            game.native_chat(&format!(
                "Minecraft → Multiplayer → Direct Connect → 127.0.0.1:{}",
                view.port
            ));
        }
        Action::Say(sentences) => {
            for sentence in sentences {
                say(game, choice.lang, sentence);
            }
        }
    }
}

/// The words the page owes a player when a button has no command behind it.
fn say(game: &mut crate::game::Game<'_>, lang: ::e4steam_engine::menu::Lang, said: ::e4steam_engine::menu::Said) {
    for line in ::e4steam_engine::menu::spoken(lang, said) {
        game.native_chat(&line);
    }
}

/// A command that did not reach the thread means the thread ended; saying so beats waiting for an
/// answer that cannot come.
fn tell(game: &mut crate::game::Game<'_>, sent: bool) {
    if !sent {
        game.native_chat("e4steam is no longer running; restart Horizon to share again.");
    }
}

fn prefs() -> ::e4steam_engine::menu::Prefs {
    ::e4steam_engine::menu::Prefs {
        lang: language(),
        access: chosen_access(),
    }
}

fn chosen_access() -> ::e4steam_engine::runtime::Access {
    *ACCESS
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

fn choose_access(from: ::e4steam_engine::runtime::Access) {
    let next = ::e4steam_engine::menu::next_access(from);
    *ACCESS.lock().unwrap_or_else(|poisoned| poisoned.into_inner()) = next;
}

/// The page's language lives in the game's own config folder, so it survives a restart and a player
/// who switches back to Horizon's English gets the English buttons.
fn language() -> ::e4steam_engine::menu::Lang {
    let Some(path) = lang_file() else {
        return ::e4steam_engine::menu::Lang::default();
    };
    match ::std::fs::read_to_string(path) {
        Ok(text) => ::e4steam_engine::menu::Lang::parse(&text),
        // No file, no folder, no read: the page starts in English, which is what the buttons say.
        Err(_) => ::e4steam_engine::menu::Lang::default(),
    }
}

fn save_language(lang: ::e4steam_engine::menu::Lang) {
    let Some(path) = lang_file() else {
        return;
    };
    if let Some(folder) = path.parent() {
        let _ = ::std::fs::create_dir_all(folder);
    }
    let _ = ::std::fs::write(&path, lang.key());
}

fn lang_file() -> Option<::std::path::PathBuf> {
    Some(crate::game::Game::native_config_dir().ok()?.join("e4steam").join("lang"))
}
