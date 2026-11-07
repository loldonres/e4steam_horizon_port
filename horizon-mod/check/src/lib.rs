// A stand-in for the slice of Horizon that `mixins/e4steam` is injected into, so `cargo test`
// type-checks the injected files instead of a full game build. Shapes and visibilities follow the
// pinned source: `Game` is private to `crate::game`, `lan` is a private field of it, and the
// `native_*` helpers are `pub(super)` in `crate::game::native_hooks`.
//
// Nothing here is called except by the tests and by the included bundle files, so the dead-code
// lint belongs to Horizon, not to this fixture.
#![allow(dead_code)]
//
// This crate is not part of the `.hnmod`: the bundle ships `mixins/e4steam/` alone.

pub mod game {
    use std::cell::RefCell;

    thread_local! {
        static CHAT: RefCell<Vec<String>> = const { RefCell::new(Vec::new()) };
    }

    /// What `native_chat` printed on this thread, oldest first; reading it empties the box.
    pub fn chat_lines() -> Vec<String> {
        CHAT.with(|chat| std::mem::take(&mut *chat.borrow_mut()))
    }

    mod lan {
        pub(super) struct LanSettings {
            pub port: u16,
        }
        pub(super) struct LanHost {
            pub settings: LanSettings,
        }
    }

    // Private to `crate::game` in the real source; `pub(crate)` here so the tests below can name it.
    pub(crate) struct Game<'a> {
        lan: Option<lan::LanHost>,
        _world: std::marker::PhantomData<&'a ()>,
    }

    mod native_hooks {
        use super::{Game, CHAT};
        use std::time::Duration;

        /// Options > Mods' row for a native mod, with the shape `crate::game` gives it.
        pub(crate) struct NativeMod {
            pub(crate) id: String,
            pub(crate) title: String,
            pub(crate) description: String,
            pub(crate) keys: Vec<[String; 3]>,
        }

        // The bundle's helper files, each one private function, included in this module: horizon-mixins
        // drops them into the target as a local block, where the same absolute paths are all a helper
        // uses. (`include!` inside a method body would be a statement macro and cannot bring an `fn` in.)
        include!("../../mixins/e4steam/command.rs");
        include!("../../mixins/e4steam/commands.rs");
        include!("../../mixins/e4steam/frame.rs");
        include!("../../mixins/e4steam/mods.rs");
        include!("../../mixins/e4steam/settings.rs");
        include!("../../mixins/e4steam/press.rs");

        impl Game<'_> {
            /// A line in chat from the client.
            pub(super) fn native_chat(&mut self, text: &str) {
                CHAT.with(|chat| chat.borrow_mut().push(text.to_string()));
            }
            pub(super) fn native_window(&self) -> bool {
                true
            }
            /// The folder a native mod keeps its settings in; here, one per test process, so a page
            /// switched to Russian in one run cannot start the next run in Russian.
            pub(super) fn native_config_dir() -> Result<std::path::PathBuf, String> {
                Ok(std::env::temp_dir().join(format!("e4steam-check-{}", std::process::id())))
            }
            /// Once a frame, in a world or not. HEAD inserts the call before these statements.
            pub(super) fn native_frame(&mut self, elapsed: Duration) {
                steam_frame(&mut *self);
                let _ = elapsed;
            }
            /// A command typed in chat, without its slash, before the server sees it.
            pub(super) fn native_command(&mut self, command: &str, ran: &mut bool) {
                steam_command(&mut *self, command, ran);
            }
            pub(super) fn native_commands(&mut self, commands: &mut Vec<&'static str>) {
                steam_commands(commands);
                let _ = commands;
            }
            /// Options > Mods asks the native mods for their rows.
            pub(super) fn native_mods(&mut self, mods: &mut Vec<NativeMod>) {
                steam_mods(mods);
            }
            /// The buttons of mod `mod_id`'s page, asked again when it opens and after each press.
            pub(super) fn native_settings(&mut self, mod_id: &str, buttons: &mut Vec<String>) {
                steam_settings(&mut *self, mod_id, buttons);
            }
            /// Button `button` of that page was pressed.
            pub(super) fn native_settings_click(&mut self, mod_id: &str, button: usize) {
                steam_settings_click(&mut *self, mod_id, button);
            }
        }
    }

    impl Game<'_> {
        pub fn alone() -> Self {
            Self {
                lan: None,
                _world: std::marker::PhantomData,
            }
        }
        /// A world open to LAN, which is what `/steam host` reads its port from.
        pub fn open_to_lan(port: u16) -> Self {
            Self {
                lan: Some(lan::LanHost {
                    settings: lan::LanSettings { port },
                }),
                _world: std::marker::PhantomData,
            }
        }
        pub fn frame(&mut self, elapsed: std::time::Duration) {
            self.native_frame(elapsed);
        }
        pub fn type_command(&mut self, command: &str) -> bool {
            let mut ran = false;
            self.native_command(command, &mut ran);
            ran
        }
        pub fn command_names(&mut self) -> Vec<&'static str> {
            let mut names = vec!["gamemode", "tp"];
            self.native_commands(&mut names);
            names
        }
        /// Options > Mods opened: every native mod's row, as (id, title, description).
        pub fn mod_rows(&mut self) -> Vec<(String, String, String)> {
            let mut mods = Vec::new();
            self.native_mods(&mut mods);
            mods.into_iter()
                .map(|row| (row.id, row.title, row.description))
                .collect()
        }
        /// The buttons of mod `mod_id`'s page, in the order Horizon draws them.
        pub fn page_buttons(&mut self, mod_id: &str) -> Vec<String> {
            let mut buttons = Vec::new();
            self.native_settings(mod_id, &mut buttons);
            buttons
        }
        /// The player pressed button `button` of that page.
        pub fn press_button(&mut self, mod_id: &str, button: usize) {
            self.native_settings_click(mod_id, button);
        }
        /// The chosen page language is the one thing a press leaves behind between tests, which share
        /// a process and so a config folder; deleting it puts the page back where Horizon starts.
        pub fn forget_the_chosen_language(&self) {
            if let Ok(dir) = Self::native_config_dir() {
                let _ = std::fs::remove_file(dir.join("e4steam").join("lang"));
            }
        }
    }

    pub(crate) mod e4steam {
        include!("../../mixins/e4steam/runtime.rs");
    }
}

#[cfg(test)]
mod tests {
    use super::game::{chat_lines, Game};
    use std::sync::{Mutex, MutexGuard};
    use std::time::Duration;

    // One Steam session serves the whole process, so its answers must not be drained by whichever
    // test thread happens to run a frame first.
    static SERIAL: Mutex<()> = Mutex::new(());

    // The bundle's `[mod] id`, which is what Horizon names the page with.
    const MOD: &str = "e4steam";

    fn one_at_a_time() -> MutexGuard<'static, ()> {
        SERIAL.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    /// The Steam thread answers when it is ready, so a test waits for the line instead of sleeping once.
    fn until_answer(game: &mut Game<'_>) -> Vec<String> {
        let mut spoken = Vec::new();
        for _ in 0..400 {
            game.frame(Duration::from_millis(50));
            spoken.extend(chat_lines());
            if !spoken.is_empty() {
                return spoken;
            }
            std::thread::sleep(Duration::from_millis(10));
        }
        spoken
    }

    /// Drops what an earlier test set running: a command sent before the lock changed hands can be
    /// answered after it, and that answer belongs to the other test's assertion, not this one's.
    /// Call it before asking, never after — the answer being waited for is in the same queue.
    fn quiet(game: &mut Game<'_>) {
        let mut idle = 0;
        for _ in 0..400 {
            game.frame(Duration::from_millis(50));
            idle = if chat_lines().is_empty() { idle + 1 } else { 0 };
            if idle == 3 {
                return;
            }
            std::thread::sleep(Duration::from_millis(5));
        }
    }

    #[test]
    fn steam_is_the_only_word_this_mod_takes() {
        let _serial = one_at_a_time();
        let mut game = Game::alone();
        assert!(!game.type_command("gamemode creative"));
        assert!(!game.type_command("status"));
        assert!(game.type_command("steam status"));
        assert!(game.type_command("STEAM status"));
    }

    #[test]
    fn the_chat_box_is_told_about_steam() {
        let _serial = one_at_a_time();
        let mut game = Game::alone();
        assert_eq!(game.command_names(), vec!["gamemode", "tp", "steam"]);
    }

    #[test]
    fn a_command_is_answered_in_chat() {
        let _serial = one_at_a_time();
        let mut game = Game::alone();
        quiet(&mut game);
        assert!(game.type_command("steam status"));
        assert_eq!(
            until_answer(&mut game),
            vec!["nothing is shared right now.".to_string()]
        );
    }

    #[test]
    fn a_wrong_command_answers_with_the_usage() {
        let _serial = one_at_a_time();
        let mut game = Game::alone();
        quiet(&mut game);
        assert!(game.type_command("steam hop"));
        let spoken = until_answer(&mut game);
        assert!(spoken[0].starts_with("usage:"), "{spoken:?}");
        assert!(
            spoken.iter().any(|line| line.contains("'hop' is not a command")),
            "{spoken:?}"
        );
    }

    #[test]
    fn an_empty_steam_command_shows_the_usage() {
        let _serial = one_at_a_time();
        let mut game = Game::alone();
        quiet(&mut game);
        assert!(game.type_command("steam"));
        assert!(until_answer(&mut game)[0].starts_with("usage:"));
    }

    #[test]
    fn a_world_open_to_lan_is_still_not_shared() {
        // The open world's port is read only when `/steam host` runs; nothing has left the computer
        // yet, so a LAN world alone is not a Steam lobby.
        let _serial = one_at_a_time();
        let mut game = Game::open_to_lan(25565);
        quiet(&mut game);
        assert!(game.type_command("steam status"));
        assert_eq!(
            until_answer(&mut game),
            vec!["nothing is shared right now.".to_string()]
        );
    }

    #[test]
    fn the_mods_page_lists_e4steam_with_what_it_is_doing() {
        let _serial = one_at_a_time();
        let mut game = Game::alone();
        game.forget_the_chosen_language();
        quiet(&mut game);
        let (_, title, description) = row_for(&mut game, MOD);
        assert_eq!(title, "e4steam · not running");
        assert_eq!(description, "shares a LAN world with Steam friends");
    }

    #[test]
    fn a_page_button_answers_without_a_word_in_chat() {
        let _serial = one_at_a_time();
        let mut game = Game::alone();
        game.forget_the_chosen_language();
        quiet(&mut game);
        let status = button_named(&mut game, "press to hear the details");
        game.press_button(MOD, status);
        assert_eq!(
            until_answer(&mut game),
            vec!["nothing is shared right now.".to_string()]
        );
    }

    #[test]
    fn a_world_not_open_to_lan_is_explained_not_guessed() {
        let _serial = one_at_a_time();
        let mut game = Game::alone();
        game.forget_the_chosen_language();
        quiet(&mut game);
        // Without an open world the first button cannot host, so it says what to do instead.
        assert_eq!(
            game.page_buttons(MOD)[0],
            "open the world to LAN, then press here"
        );
        game.press_button(MOD, 0);
        assert!(until_answer(&mut game)
            .iter()
            .any(|line| line.contains("Open to LAN")));
    }

    #[test]
    fn an_open_world_gives_the_page_a_button_that_hosts() {
        let _serial = one_at_a_time();
        let mut game = Game::open_to_lan(25565);
        game.forget_the_chosen_language();
        quiet(&mut game);
        assert_eq!(game.page_buttons(MOD)[0], "share this LAN world");
    }

    #[test]
    fn the_access_button_moves_through_the_modes_and_back() {
        let _serial = one_at_a_time();
        let mut game = Game::alone();
        game.forget_the_chosen_language();
        quiet(&mut game);
        let seen: Vec<String> = (0..4)
            .map(|_| {
                let labels = game.page_buttons(MOD);
                let index = labels
                    .iter()
                    .position(|label| label.starts_with("access:"))
                    .unwrap_or_else(|| panic!("no access row in {labels:?}"));
                let label = labels[index].clone();
                game.press_button(MOD, index);
                label
            })
            .collect();
        assert_eq!(
            seen,
            vec![
                "access: friends — press to change",
                "access: invite only — press to change",
                "access: public — press to change",
                "access: localhost only — press to change",
            ]
        );
        // Four presses is the whole cycle, so the page is back where a player left it and the next
        // test does not inherit a chosen mode.
        let labels = game.page_buttons(MOD);
        assert!(
            labels
                .iter()
                .any(|label| label == "access: friends — press to change"),
            "{labels:?}"
        );
    }

    #[test]
    fn the_language_button_moves_the_page_to_russian_and_back() {
        let _serial = one_at_a_time();
        let mut game = Game::alone();
        game.forget_the_chosen_language();
        quiet(&mut game);
        let switch = button_named(&mut game, "по-русски");
        game.press_button(MOD, switch);
        let russian = game.page_buttons(MOD);
        assert!(
            russian.iter().any(|label| label == "подключиться к другу"),
            "{russian:?}"
        );
        let back = russian
            .iter()
            .position(|label| label == "make this menu English")
            .unwrap_or_else(|| panic!("the page never offered to go back: {russian:?}"));
        game.press_button(MOD, back);
        assert!(
            game.page_buttons(MOD)
                .iter()
                .any(|label| label == "join a friend's world"),
            "the page stayed out of English"
        );
    }

    #[test]
    fn another_mods_page_is_not_ours_to_fill_or_press() {
        let _serial = one_at_a_time();
        let mut game = Game::alone();
        game.forget_the_chosen_language();
        quiet(&mut game);
        assert!(game.page_buttons("sodium").is_empty());
        let _ = chat_lines();
        game.press_button("sodium", 0);
        // Nothing was asked of the Steam thread, so nothing has arrived to be said.
        assert!(chat_lines().is_empty());
    }

    /// The page as Horizon holds it: the row of `mod_id` from Options > Mods.
    fn row_for(game: &mut Game<'_>, mod_id: &str) -> (String, String, String) {
        game.mod_rows()
            .into_iter()
            .find(|(id, _, _)| id == mod_id)
            .unwrap_or_else(|| panic!("{mod_id} is not listed among the native mods"))
    }

    /// The button a player reads rather than a number that moves with the state.
    fn button_named(game: &mut Game<'_>, name: &str) -> usize {
        let labels = game.page_buttons(MOD);
        labels
            .iter()
            .position(|label| label.contains(name))
            .unwrap_or_else(|| panic!("{name} is not a button of {labels:?}"))
    }
}
