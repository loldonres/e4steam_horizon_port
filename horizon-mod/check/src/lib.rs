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

        // The bundle's helper files, each one private function, included in this module: horizon-mixins
        // drops them into the target as a local block, where the same absolute paths are all a helper
        // uses. (`include!` inside a method body would be a statement macro and cannot bring an `fn` in.)
        include!("../../mixins/e4steam/command.rs");
        include!("../../mixins/e4steam/commands.rs");
        include!("../../mixins/e4steam/frame.rs");

        impl Game<'_> {
            /// A line in chat from the client.
            pub(super) fn native_chat(&mut self, text: &str) {
                CHAT.with(|chat| chat.borrow_mut().push(text.to_string()));
            }
            pub(super) fn native_window(&self) -> bool {
                true
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
        assert!(game.type_command("steam"));
        assert!(until_answer(&mut game)[0].starts_with("usage:"));
    }

    #[test]
    fn a_world_open_to_lan_is_still_not_shared() {
        // The open world's port is read only when `/steam host` runs; nothing has left the computer
        // yet, so a LAN world alone is not a Steam lobby.
        let _serial = one_at_a_time();
        let mut game = Game::open_to_lan(25565);
        assert!(game.type_command("steam status"));
        assert_eq!(
            until_answer(&mut game),
            vec!["nothing is shared right now.".to_string()]
        );
    }
}
