//! The mod's page inside Horizon's own menu: what it says and what a press does.
//!
//! Horizon gives a native mod's page buttons and key rows (`native_mods`, `native_settings`,
//! `native_settings_click`), so the whole session has to read off one line per button and follow the
//! state since the last press. This module holds that surface: a `View` the Steam thread publishes and
//! the rows built from it, so the game side only maps a press onto the engine's own commands.
//!
//! Nothing here talks to Steam, the game or the chat box: the page is a pure function of the state,
//! which is what makes the labels testable without a second account.

use crate::runtime::Access;
use std::sync::{Arc, Mutex};

/// What a row does.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Action {
    /// Share the world open to LAN, with the access the page currently holds.
    Host,
    /// Become a guest and let Steam hand the invite over.
    WaitInvite,
    /// Ask the session what it is doing; the answer comes back as chat lines.
    Status,
    /// Show the invite code again.
    ShowInvite,
    /// Show the address to type into Direct Connect.
    ShowAddress,
    /// Leave the lobby or the connection.
    Stop,
    /// Choose the access for the next world shared.
    CycleAccess,
    /// Switch the page between English and Russian.
    CycleLang,
    /// Sentences the page says itself, because no engine command fits them.
    Say(Vec<Said>),
}

/// A sentence the page owes the player when a button has no command behind it.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Said {
    /// The world is not open to LAN, so there is nothing to share yet.
    NoLan,
    /// Access is chosen per lobby: changing it means stopping this one.
    AccessLocked,
    /// Nothing arrived from Steam: how an invite actually reaches the game.
    StillWaiting,
    /// The code is the door into the world, so it is not for a screenshot.
    InviteSecret,
    /// This access mode has no code: friends come from the lobby or the list instead.
    NoInvite,
    /// The four sentences that replace reading the manual.
    Help,
}

/// A row: the label the player reads and the action behind it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Row {
    pub label: String,
    pub action: Action,
}

/// Which language the page speaks. Chat keeps the engine's English — it is the same text the Java mod
/// says, and a host and a guest read it together — while the page is the player's own choice.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum Lang {
    #[default]
    En,
    Ru,
}

impl Lang {
    pub fn parse(value: &str) -> Self {
        match value.trim().to_ascii_lowercase().as_str() {
            "ru" | "rus" | "ru-ru" => Self::Ru,
            _ => Self::En,
        }
    }

    pub fn key(self) -> &'static str {
        match self {
            Self::En => "en",
            Self::Ru => "ru",
        }
    }

    pub fn next(self) -> Self {
        match self {
            Self::En => Self::Ru,
            Self::Ru => Self::En,
        }
    }
}

/// Whether the session shares a world, waits for a friend or does nothing yet.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum State {
    #[default]
    Idle,
    /// The local player hosts a world open to LAN.
    Hosting,
    /// The local player plays someone else's world through Steam.
    Guest,
    /// A guest with no code yet, waiting for Steam to hand one over.
    AwaitingInvite,
}

/// What the Steam thread is doing, copied out for the game thread. Read only on the game side.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct View {
    pub state: State,
    /// The access in force: what the host chose, not what the page is about to choose.
    pub access: Access,
    pub bridges: usize,
    pub lobby: u64,
    /// The port the world answers on: the LAN port shared for a host, the listener for a guest.
    pub port: u16,
    /// The invite code a host can hand out, when Steam gave one.
    pub invite: Option<String>,
    /// The last reason sharing ended, in the thread's own words.
    pub note: Option<String>,
}

impl Default for View {
    fn default() -> Self {
        Self {
            state: State::Idle,
            access: Access::FriendsOnly,
            bridges: 0,
            lobby: 0,
            port: 0,
            invite: None,
            note: None,
        }
    }
}

impl View {
    /// Whether anything of the session is still alive.
    pub fn running(&self) -> bool {
        self.state != State::Idle
    }
}

/// The choices the page owns between presses.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct Prefs {
    pub lang: Lang,
    /// The access the next shared world will use.
    pub access: Access,
}

/// Where the Steam thread leaves the state for the game thread to read.
pub type Shared = Arc<Mutex<View>>;

pub fn shared() -> Shared {
    Arc::new(Mutex::new(View::default()))
}

/// The latest state, without waiting for the Steam thread. A lock left held by a thread that died
/// keeps the page on the state it had, which is the honest answer rather than a blank one.
pub fn peek(view: &Shared) -> View {
    view.lock()
        .map(|state| state.clone())
        .unwrap_or_else(|held| held.into_inner().clone())
}

/// Store `view` unless it already says this, so a 10 ms loop does not allocate for nothing.
pub fn publish(view: &Shared, next: View) {
    if let Ok(mut guard) = view.lock() {
        if *guard != next {
            *guard = next;
        }
    }
}

/// One line above the buttons: what the session is, in numbers a player can check.
pub fn title(lang: Lang, view: &View) -> String {
    let state = match view.state {
        State::Idle => line(lang, "not running", "не запущено"),
        State::Hosting => line(
            lang,
            &format!(
                "hosting, {} of {} guests",
                view.bridges,
                crate::runtime::MAX_HOST_CONNECTIONS
            ),
            &format!(
                "раздаю, гостей {} из {}",
                view.bridges,
                crate::runtime::MAX_HOST_CONNECTIONS
            ),
        ),
        State::Guest => line(
            lang,
            &format!("playing through 127.0.0.1:{}", view.port),
            &format!("играю через 127.0.0.1:{}", view.port),
        ),
        State::AwaitingInvite => line(lang, "waiting for an invite", "жду инвайт"),
    };
    format!("e4steam · {state}")
}

/// The row in Options > Mods, where the player decides whether to open the page.
pub fn description(lang: Lang, view: &View) -> String {
    match view.state {
        State::Idle => match &view.note {
            Some(note) => note.clone(),
            None => line(
                lang,
                "shares a LAN world with Steam friends",
                "раздаёт LAN-мир друзьям Steam",
            ),
        },
        State::Hosting => {
            let access = access_label(lang, view.access);
            if view.lobby == 0 {
                line(lang, "hosting, no lobby yet", "раздаю, лобби ещё нет")
            } else {
                let mut text = line(
                    lang,
                    &format!("hosting lobby {}", view.lobby),
                    &format!("раздаю, лобби {}", view.lobby),
                );
                text.push_str(&format!(" · {access}"));
                text
            }
        }
        State::Guest | State::AwaitingInvite => {
            line(lang, "playing a friend's world", "играю у друга")
        }
    }
}

/// The page: one row per button, in the order Horizon draws them.
pub fn rows(lang: Lang, prefs: &Prefs, view: &View, lan_port: Option<u16>) -> Vec<Row> {
    let mut rows = Vec::new();
    match view.state {
        State::Idle => {
            rows.push(if lan_port.is_some() {
                row(lang, "share this LAN world", "раздать этот LAN-мир", Action::Host)
            } else {
                row(
                    lang,
                    "open the world to LAN, then press here",
                    "сначала открой мир в LAN, потом нажми",
                    say(Said::NoLan),
                )
            });
            rows.push(row(
                lang,
                "join a friend's world",
                "подключиться к другу",
                Action::WaitInvite,
            ));
            rows.push(row(
                lang,
                &format!(
                    "access: {} — press to change",
                    access_label(lang, prefs.access)
                ),
                &format!(
                    "доступ: {} — нажми, чтобы изменить",
                    access_label(lang, prefs.access)
                ),
                Action::CycleAccess,
            ));
        }
        State::Hosting => {
            rows.push(row(lang, "stop hosting", "остановить раздачу", Action::Stop));
            if view.invite.is_some() {
                rows.push(row(
                    lang,
                    "show the invite code",
                    "показать код инвайта",
                    Action::ShowInvite,
                ));
            }
            rows.push(row(
                lang,
                &format!(
                    "access: {} — stop first to change",
                    access_label(lang, view.access)
                ),
                &format!("доступ: {} — сначала останови", access_label(lang, view.access)),
                say(Said::AccessLocked),
            ));
        }
        State::Guest | State::AwaitingInvite => {
            rows.push(row(lang, "leave the world", "выйти из мира", Action::Stop));
            if view.state == State::Guest {
                rows.push(row(
                    lang,
                    &format!("direct connect 127.0.0.1:{}", view.port),
                    &format!("прямое подключение 127.0.0.1:{}", view.port),
                    Action::ShowAddress,
                ));
            } else {
                rows.push(row(
                    lang,
                    "still waiting for a Steam invite",
                    "всё ещё жду инвайта из Steam",
                    say(Said::StillWaiting),
                ));
            }
        }
    }
    // The line above these buttons is Horizon's own, taken when it built the mod list — at world
    // start — so it can still read "not running" a lobby later. This row is asked for on every open
    // and every press, so it is where the live state belongs.
    let mut status = title(lang, view);
    status.push_str(&line(
        lang,
        " — press to hear the details",
        " — нажми, чтобы услышать подробности",
    ));
    rows.push(Row {
        label: status,
        action: Action::Status,
    });
    rows.push(row(
        lang,
        "how to use this",
        "как этим пользоваться",
        say(Said::Help),
    ));
    rows.push(row(
        lang,
        "открыть это меню по-русски",
        "make this menu English",
        Action::CycleLang,
    ));
    rows
}

/// The action behind row `index`, taken from the same rows, so a label and its press cannot drift
/// apart between the page opening and the click.
pub fn press(
    lang: Lang,
    prefs: &Prefs,
    view: &View,
    lan_port: Option<u16>,
    index: usize,
) -> Option<Action> {
    rows(lang, prefs, view, lan_port)
        .get(index)
        .map(|row| row.action.clone())
}

/// The access the page moves to after a `CycleAccess` press: the four modes the command takes, in
/// the order the Java mod documents them.
pub fn next_access(access: Access) -> Access {
    match access {
        Access::FriendsOnly => Access::InviteOnly,
        Access::InviteOnly => Access::Custom,
        Access::Custom => Access::LocalOnly,
        Access::LocalOnly => Access::FriendsOnly,
    }
}

/// The lines the page says itself, in the language the player chose.
pub fn spoken(lang: Lang, said: Said) -> Vec<String> {
    let lines: &[&str] = match said {
        Said::NoLan => line_slice(
            lang,
            &[
                "e4steam shares a world Horizon already opened to LAN.",
                "Esc → Open to LAN, then press the first button here again.",
            ],
            &[
                "e4steam раздаёт мир, который Horizon уже открыл в LAN.",
                "Esc → «Открыть в LAN», потом нажми первую кнопку здесь.",
            ],
        ),
        Said::AccessLocked => line_slice(
            lang,
            &[
                "the access of a live lobby cannot change; press \"stop hosting\" first.",
                "guests already in stay until the world closes.",
            ],
            &[
                "доступ живого лобби не переключить: сначала «остановить раздачу».",
                "кто уже в мире, тот останется.",
            ],
        ),
        Said::StillWaiting => line_slice(
            lang,
            &[
                "e4steam waits for a Steam invite: accept it in the Steam overlay or chat.",
                "a code from a friend is typed in chat instead: /steam join <code>",
            ],
            &[
                "e4steam ждёт инвайта Steam: прими его в оверлее или в чате Steam.",
                "код от друга набирают в чате игры: /steam join <код>",
            ],
        ),
        Said::InviteSecret => line_slice(
            lang,
            &["keep it private: the code is the ticket into your world."],
            &["не светите код: это билет в ваш мир."],
        ),
        Said::NoInvite => line_slice(
            lang,
            &[
                "this access mode publishes no invite code.",
                "friends reach the world from the Steam friends list, or press to change the access.",
            ],
            &[
                "этот доступ не выдаёт кода инвайта.",
                "друзья заходят из списка друзей Steam, либо смени доступ здесь.",
            ],
        ),
        Said::Help => return help(lang),
    };
    lines.iter().map(|line| line.to_string()).collect()
}

/// The four sentences a player who never read a manual needs.
pub fn help(lang: Lang) -> Vec<String> {
    let lines: &[&str] = line_slice(
        lang,
        &[
            "1. Esc → Open to LAN, then \"share this LAN world\" here.",
            "2. Friends come in from the Steam overlay, or with the invite code this page shows.",
            "3. To play at a friend: \"join a friend's world\" here, then accept their Steam invite.",
            "4. Then Multiplayer → Direct Connect → the 127.0.0.1 address the mod said in chat.",
        ],
        &[
            "1. Esc → «Открыть в LAN», затем здесь «раздать этот LAN-мир».",
            "2. Друг заходит из оверлея Steam или по коду инвайта, который показывает эта страница.",
            "3. Играть у друга: здесь «подключиться к другу», потом принять его инвайт в Steam.",
            "4. Дальше Сетевая игра → Прямое подключение → адрес 127.0.0.1 из чата игры.",
        ],
    );
    lines.iter().map(|line| line.to_string()).collect()
}

fn say(said: Said) -> Action {
    Action::Say(vec![said])
}

fn row(lang: Lang, english: &str, russian: &str, action: Action) -> Row {
    Row {
        label: line(lang, english, russian),
        action,
    }
}

fn access_label(lang: Lang, access: Access) -> String {
    match (lang, access) {
        (Lang::En, Access::FriendsOnly) => "friends".to_string(),
        (Lang::En, Access::InviteOnly) => "invite only".to_string(),
        (Lang::En, Access::Custom) => "public".to_string(),
        (Lang::En, Access::LocalOnly) => "localhost only".to_string(),
        (Lang::Ru, Access::FriendsOnly) => "друзья".to_string(),
        (Lang::Ru, Access::InviteOnly) => "по инвайту".to_string(),
        (Lang::Ru, Access::Custom) => "для всех".to_string(),
        (Lang::Ru, Access::LocalOnly) => "только локально".to_string(),
    }
}

fn line(lang: Lang, english: &str, russian: &str) -> String {
    match lang {
        Lang::En => english.to_string(),
        Lang::Ru => russian.to_string(),
    }
}

fn line_slice<'a>(lang: Lang, english: &'a [&'static str], russian: &'a [&'static str]) -> &'a [&'static str] {
    match lang {
        Lang::En => english,
        Lang::Ru => russian,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::runtime::Access;

    fn view(state: State) -> View {
        View {
            state,
            ..View::default()
        }
    }

    fn labels(lang: Lang, prefs: &Prefs, view: &View, lan: Option<u16>) -> Vec<String> {
        rows(lang, prefs, view, lan).into_iter().map(|row| row.label).collect()
    }

    #[test]
    fn an_open_lan_world_is_what_the_first_button_offers() {
        let prefs = Prefs::default();
        let idle = View::default();
        let with_lan = labels(Lang::En, &prefs, &idle, Some(25565));
        assert_eq!(with_lan[0], "share this LAN world");
        assert_eq!(
            press(Lang::En, &prefs, &idle, Some(25565), 0),
            Some(Action::Host)
        );

        let no_lan = labels(Lang::En, &prefs, &idle, None);
        assert!(no_lan[0].contains("open the world to LAN"), "{no_lan:?}");
        assert_eq!(
            press(Lang::En, &prefs, &idle, None, 0),
            Some(Action::Say(vec![Said::NoLan]))
        );
    }

    #[test]
    fn a_page_always_ends_with_status_help_and_the_language() {
        let prefs = Prefs::default();
        for (state, lan) in [
            (State::Idle, Some(25565)),
            (State::Idle, None),
            (State::Hosting, Some(25565)),
            (State::Guest, None),
            (State::AwaitingInvite, None),
        ] {
            let rows = rows(Lang::En, &prefs, &view(state), lan);
            let last = rows.last().expect("a page with rows");
            assert_eq!(last.action, Action::CycleLang, "{state:?}");
            assert_eq!(rows[rows.len() - 2].action, Action::Say(vec![Said::Help]));
            assert!(rows.iter().any(|row| row.action == Action::Status), "{state:?}");
        }
    }

    #[test]
    fn the_status_row_says_the_state_the_header_cannot_be_updated_to() {
        // Horizon draws the line above the buttons from the mod list it built at world start, so it
        // can still read "not running" while a lobby lives. These rows are re-asked on every open
        // and every press, so the state has to ride on one of them.
        let prefs = Prefs::default();
        let idle = labels(Lang::En, &prefs, &View::default(), Some(25565));
        assert_eq!(
            idle[3],
            "e4steam · not running — press to hear the details"
        );
        let mut hosting = view(State::Hosting);
        hosting.bridges = 2;
        let page = labels(Lang::En, &prefs, &hosting, Some(25565));
        assert_eq!(
            page[page.len() - 3],
            "e4steam · hosting, 2 of 7 guests — press to hear the details"
        );
    }

    #[test]
    fn hosting_offers_to_stop_and_only_shows_a_code_when_steam_gave_one() {
        let prefs = Prefs::default();
        let mut hosting = view(State::Hosting);
        hosting.bridges = 2;
        hosting.lobby = 4_300_000;
        let without = labels(Lang::En, &prefs, &hosting, Some(25565));
        assert_eq!(without[0], "stop hosting");
        assert!(!without.iter().any(|line| line.contains("invite code")), "{without:?}");

        hosting.invite = Some("s-1234567890-abcdefgh.steam".to_string());
        let with = rows(Lang::En, &prefs, &hosting, Some(25565));
        assert!(
            with.iter().any(|row| row.action == Action::ShowInvite),
            "{with:?}"
        );
        assert_eq!(
            description(Lang::En, &hosting),
            "hosting lobby 4300000 · friends".to_string()
        );
        assert_eq!(title(Lang::En, &hosting), "e4steam · hosting, 2 of 7 guests");
    }

    #[test]
    fn the_access_of_a_live_lobby_is_explained_not_switched() {
        let prefs = Prefs::default();
        let mut hosting = view(State::Hosting);
        hosting.access = Access::Custom;
        let page = rows(Lang::En, &prefs, &hosting, Some(25565));
        let access = page
            .iter()
            .find(|row| row.label.contains("access:"))
            .expect("an access row");
        assert!(access.label.contains("public"), "{access:?}");
        assert_eq!(access.action, Action::Say(vec![Said::AccessLocked]));

        // While nothing runs, the same row is the chooser.
        let chooser = rows(Lang::En, &prefs, &View::default(), Some(25565))
            .into_iter()
            .find(|row| row.action == Action::CycleAccess)
            .expect("an access button when idle");
        assert!(chooser.label.contains("press to change"), "{chooser:?}");
    }

    #[test]
    fn a_guest_sees_the_address_it_was_handed() {
        let prefs = Prefs::default();
        let mut guest = view(State::Guest);
        guest.port = 51234;
        let page = rows(Lang::En, &prefs, &guest, None);
        assert_eq!(page[0].action, Action::Stop);
        assert_eq!(page[1].label, "direct connect 127.0.0.1:51234");
        assert_eq!(page[1].action, Action::ShowAddress);

        let waiting = rows(Lang::En, &prefs, &view(State::AwaitingInvite), None);
        assert_eq!(waiting[1].action, Action::Say(vec![Said::StillWaiting]));
    }

    #[test]
    fn a_press_that_is_no_longer_there_does_nothing() {
        let prefs = Prefs::default();
        let idle = View::default();
        let count = rows(Lang::En, &prefs, &idle, None).len();
        assert_eq!(press(Lang::En, &prefs, &idle, None, count), None);
        assert_eq!(press(Lang::En, &prefs, &idle, None, 4_000), None);
    }

    #[test]
    fn the_page_reads_the_same_in_russian() {
        let prefs = Prefs {
            lang: Lang::Ru,
            access: Access::InviteOnly,
        };
        let labels = labels(Lang::Ru, &prefs, &View::default(), Some(25565));
        assert_eq!(labels[0], "раздать этот LAN-мир");
        assert!(labels[1].contains("подключиться к другу"), "{labels:?}");
        assert!(labels[2].contains("по инвайту"), "{labels:?}");
        assert_eq!(
            labels[3],
            "e4steam · не запущено — нажми, чтобы услышать подробности"
        );

        let hosting = view(State::Hosting);
        assert_eq!(title(Lang::Ru, &hosting), "e4steam · раздаю, гостей 0 из 7");
        assert_eq!(
            description(Lang::Ru, &View::default()),
            "раздаёт LAN-мир друзьям Steam"
        );
    }

    #[test]
    fn the_language_button_is_the_one_row_that_changes_the_labels() {
        let prefs = Prefs::default();
        let english = labels(Lang::En, &prefs, &View::default(), None);
        let index = english.len() - 1;
        assert_eq!(press(Lang::En, &prefs, &View::default(), None, index), Some(Action::CycleLang));
        assert_eq!(Lang::En.next(), Lang::Ru);
        assert_eq!(Lang::Ru.next(), Lang::En);
        assert_eq!(Lang::parse("ru-RU"), Lang::Ru);
        assert_eq!(Lang::parse("anything else"), Lang::En);
        assert_eq!(Lang::Ru.key(), "ru");
    }

    #[test]
    fn the_help_and_the_sentences_are_said_in_both_languages() {
        for lang in [Lang::En, Lang::Ru] {
            assert_eq!(help(lang).len(), 4, "{lang:?}");
            for said in [Said::NoLan, Said::AccessLocked, Said::StillWaiting, Said::NoInvite, Said::InviteSecret] {
                let lines = spoken(lang, said);
                assert!(!lines.is_empty(), "{lang:?} {said:?}");
                assert!(
                    lines.iter().all(|line| !line.trim().is_empty()),
                    "{lang:?} {said:?}"
                );
            }
        }
        assert!(spoken(Lang::Ru, Said::Help)[0].contains("Esc"));
    }

    #[test]
    fn access_cycles_through_the_four_modes_the_command_takes() {
        let mut access = Access::FriendsOnly;
        let seen = {
            let mut seen = Vec::new();
            for _ in 0..4 {
                seen.push(access);
                access = next_access(access);
            }
            seen
        };
        assert_eq!(
            seen,
            vec![
                Access::FriendsOnly,
                Access::InviteOnly,
                Access::Custom,
                Access::LocalOnly
            ]
        );
        assert_eq!(access, Access::FriendsOnly);
    }

    #[test]
    fn a_stopped_session_keeps_the_reason_it_said() {
        let mut stopped = View::default();
        stopped.note = Some("Steam is not usable: no steam_api64.dll".to_string());
        assert_eq!(
            description(Lang::En, &stopped),
            "Steam is not usable: no steam_api64.dll"
        );
        assert_eq!(description(Lang::Ru, &stopped), description(Lang::En, &stopped));
        assert!(title(Lang::En, &stopped).ends_with("not running"));
    }
}
