# e4steam — the native Horizon mod

[English](README.md) · [Русский](README.ru.md)

One `.hnmod` file: a Rust mod that Horizon compiles into the game. After you install and build it,
**Esc → Options → Mods → e4steam** opens a page of buttons, and `/steam` appears in the chat box for
anyone who prefers commands — the same surface as the Java mod
[e4steam](https://github.com/Kamilhik/e4steam) and the same `E4ST` v4 wire protocol, so a Horizon
host and a NeoForge/Fabric guest share one Steam lobby.

Player-level setup (requirements, quick start, troubleshooting) lives in the
[top-level README](../README.md). This file is about how the bundle is built and what it changes.

> Native mods are Rust inside your game binary: unsandboxed, full access to your computer. The
> Horizon launcher asks you to confirm that with `--trust-native-code`, and that confirmation is
> yours to give — scripts and assistants in this repository never pass it.

## The built file

`target/e4steam-0.1.0.hnmod` — 24 files, 70 033 bytes, SHA-256
`524739bf75bb131bdf142ddf713d74ad206b1d836f3b3cb2bc6a57059497a497`.

Reproducible: the same sources give the same bytes. The packager fixes the zip timestamps to
2026-01-01, sorts entries, and pins the file modes.

| Pinned | Value |
|---|---|
| Horizon commit | `aa27eca326db26e999d4e59d7899576016764b1e` (Nightly 2026-10-31, `NATIVE_HOOKS_API` 40) |
| horizon-mixins commit | `d9c83d7096b7ea5b83678da2efdfd6161f74997e` |
| Minecraft | `26.3` (protocol 777) |
| `crates/horizon-client/src/game.rs` | sha256 `b99ccd23…2c43`, in `compatibility.json` |

Pinning a commit instead of declaring `"hooks": N` is deliberate: the payload reads Horizon's
private `Game.lan` field to learn the LAN port, which is not part of the stable hook-point API. On
a different commit the build fails loudly rather than quietly producing something half-broken.

## Install

Dropping the file into a folder is not enough: the launcher keeps the enabled native mods in the
instance's `native-mods.json` and builds them itself, on the commit the mod pins.

1. Horizon → **Mods** → pick the instance.
2. Install it — from the registry or from GitHub once published, or point `native-build` at the
   bundle folder (`mixins/e4steam/`) or an unpacked `.hnmod`:

   ```sh
   horizon-launcher native-build mixins/e4steam --instance NAME --trust-native-code
   ```

   With no `FOLDER` it builds the native mods enabled in the instance. In the window this is the
   **Build** button and «I trust these mods: build».
3. Decide for yourself whether you trust the code before you press it.

Installed mods end up in `<instance>/horizon-native-mods/` (**Open folder** in the window). The
first build downloads the launcher's own Rust and MinGW and takes 20–30 minutes; afterwards the
cache does the work. The result is a version named `native-<commit>-<key>`, which is not mixed with
mods built on other commits. While iterating on the mod, `horizon-launcher settings native-profile
dev` rebuilds about 6× faster.

## Steam inside the game process

Horizon's own «Play with friends» is TCP/STUN, not Steam, so the game process carries no Steamworks
context and this mod can own one. Three things still have to be true:

- **`steam_api64.dll`** must be reachable by the game process. Search order: the
  `E4STEAM_STEAM_API` environment variable (full path), beside the game executable, in a `steam/`
  folder beside it, copies next to installed games in your Steam libraries, then the ordinary
  Windows search. Valve's DLL is not redistributed here — take it from your own Steam.
- **`steam_appid.txt`** with `480` (the Steamworks test app id, the same one the Java mod uses) is
  written by the mod into the working folder or beside the executable. If that folder is
  read-only, place the file yourself.
- The **Steam client** runs and is logged in; the friends list comes from it.

`/steam status` — or the page's status row — reports which of these is missing, in words rather than
a stack trace, and the same reason stays on the page's row after the chat line has scrolled away.

## How it is put into the game

The bundle is a horizon-mixins source bundle — Rust plus a manifest, staged into Horizon's source
before the game is compiled. `mixins/e4steam/horizon-mixin.toml` declares:

| Piece | What it does |
|---|---|
| `[[crate]] engine` | `e4steam-engine` becomes a dependency of `horizon-client`, staged to `native-crates/e4steam/engine/`. Its only external dependency is `flate2`, which is already in Horizon's `Cargo.lock` |
| `[[module]] runtime` | `runtime.rs` is emitted into `crates/horizon-client/src/game.rs` as `pub(crate) mod e4steam`, so it is a child of `crate::game` and may touch private game state (`Game.lan`) and the `pub(super)` hook helpers |
| `[[injection]] command` | HEAD of the `command` hook: takes `/steam …` out of chat before the server sees an unknown command |
| `[[injection]] commands` | HEAD of the `commands` hook: adds `steam` to the chat box's completion list |
| `[[injection]] frame` | HEAD of the `frame` hook: drains the Steam thread's output into chat |
| `[[injection]] mods` | HEAD of the `mods` hook: pushes the mod's row into Options → Mods — its title and one-line state |
| `[[injection]] settings` | HEAD of the `settings` hook: the page's button labels, re-asked when the page opens and after every click |
| `[[injection]] press` | HEAD of the `settings_click` hook: runs the button the player pressed |

Each injection calls a one-line private helper (`command.rs`, `commands.rs`, `frame.rs`) — the shape
horizon-mixins requires — and the helper forwards to `crate::game::e4steam::on_*`. Those entry points
are `pub(super)`, not `pub(crate)`: `Game` is private to `crate::game`, so a crate-wide signature
would name a type less visible than the function.

All real work lives in `mixins/e4steam/engine` and on **its own thread**: the Steamworks Flat API is
not thread-safe and `Runtime::tick` must keep running on the thread that owns the context, so the
game loop only pushes commands into a channel and pulls lines back out. Nothing in the mod blocks
the client.

## The page in the pause menu

**Esc → Options → Mods → e4steam → Settings** is what an ordinary player uses; `/steam` in chat stays
for the ones who like commands. Horizon owns that screen — the mod only answers three questions: which
row to list, which buttons to show, what a press means. Nothing is drawn by the mod, so the page is
vanilla-shaped and needs no renderer hook, and it asks for no keybind rows.

[`mixins/e4steam/engine/src/menu.rs`](mixins/e4steam/engine/src/menu.rs) holds that as a pure model:
`rows(lang, prefs, view, lan_port)` turns the published state into labels and actions, and `press(…)`
maps a button index back through the same function, so a label and its action cannot drift apart
between the drawing and the click. The `View` it reads is written by the Steam thread when the state
changes and only read by the game thread, so opening the page never waits on Steam.

Two of Horizon's behaviours shape the model:

- the line above the buttons is the mod list's title, taken when that list is built (at world start),
  so it can lag behind the session — which is why the status row carries the live state in its label;
- the buttons are re-asked when the page opens and after every click, so every label is rebuilt from
  the current state — including the language the player just switched to.

Language is the only thing a press keeps: `<config>/e4steam/lang` holds `en` or `ru`, in Horizon's own
`native_config_dir()` beside its options. Together with the `steam_appid.txt` the session writes,
those are the only files the mod leaves behind.

## Building and validating

```sh
cargo test --workspace          # 104 tests; no hardware, no second Steam account
python tools/package.py         # -> target/e4steam-<ver>.hnmod and its sha256
bash tools/validate.sh          # real horizon-mixins plan + apply on the pinned source
```

`validate.sh` fetches Horizon and horizon-mixins at exactly the pinned commits into a cache folder
(`~/.cache/e4steam-hz`, override with `HZ_WORK`; the bundle path with `E4STEAM_MODS`),
runs `plan`, then `apply`, and prints the shape of the plan — it must be **6 injections**
(`frame`, `command`, `commands`, `mods`, `settings`, `press`), **1 module** (`e4steam`), **1 crate**
(`engine`) — and greps the staged source so you can see the injected module and helpers in `game.rs`
and `game/native_hooks.rs`. Those two subcommands only transform source: nothing is compiled or run,
which is why they need no trust flag. `horizon-mixins check` does compile the injected mod, and that
is the player's call.

`check/` is the reason the payload type-checks in seconds instead of an hour: a mock of `crate::game`
with the same geometry as Horizon (private `Game`, `lan: Option<LanHost>`, `pub(super)` helpers,
`include!` of the bundle's own files), so a typo in `runtime.rs` or a helper shows up in `cargo test`.

`tools/package.py` also enforces the registry's bundle rules locally, so a refusal happens on your
machine instead of in review: only `.rs`, `.toml` and the crate's text files go in; dependencies must
already be in Horizon's `Cargo.lock`; `[workspace]`, `[patch]`, `[replace]`, `[profile]`, path and git
dependencies are forbidden; ids must be lowercase `[a-z0-9_]`; the manifest pin and
`compatibility.json` must agree; 512 files and 64 MiB unpacked at most.

## Re-pinning to a newer Horizon

1. Put the new commit in `[compatibility] horizon_revision` (`mixins/e4steam/horizon-mixin.toml`) and in
   `compatibility.json`, and refresh `source_sha256` for `crates/horizon-client/src/game.rs`.
   `tools/validate.sh` re-fetches the export when its `.horizon-revision` file disagrees, so moving
   the pin moves the source with it.
2. Check that `Game.lan` and the six hook signatures still exist: `frame`, `command`, `commands`
   (API 35), `mods`, `settings`, `settings_click` — all version 1, and all of them listed in the
   pinned `native-hooks.json`, which `tools/package.py` matches the manifest against.
3. `bash tools/validate.sh`, then `cargo test --workspace`.
4. Bump `version` in `horizon-mixin.toml`, `python tools/package.py`, and publish the new `.hnmod` as a
   GitHub release asset. The registry takes a release with a `.hnmod` asset as a version.

## Cross-play

Matches e4steam 0.3.2 / 0.3.4: wire version `4`, frame lengths and flags, the zlib envelope inside
`DATA`, lobby metadata (`e4steam_minecraft`, compared character by character), channel 480, the
16-byte invite token checked in constant time, 7 guests, the 30 s handshake deadline and the retry
and backpressure timings. The byte-level contract is [`../re/PROTOCOL.md`](../re/PROTOCOL.md). A
guest whose version string differs is refused before any connection.

## Layout

```
mixins/e4steam/
  horizon-mixin.toml     [mod], [compatibility], [[crate]], [[module]], 6 [[injection]]
  runtime.rs             the payload, emitted as crate::game::e4steam
  command.rs commands.rs frame.rs         one private helper fn per chat injection
  mods.rs settings.rs press.rs           … and per page injection
  engine/                e4steam-engine: E4ST codec, Steamworks Flat API, bridge,
                         /steam grammar, and the page's rows and actions (menu.rs)
compatibility.json       pins and the sha256 of game.rs
tools/                   package.py (reproducible archive), validate.sh (plan + apply)
check/                   the crate::game geometry fixture for cargo test
```

## Security

The invite code `s-….steam` **is** access to your world: never publish it in a log or a screenshot.
The mod masks other players' SteamIDs, connect addresses and named secrets (`token`, `ticket`,
`password`, `secret`, `cookie`, `authorization`, `gslt`) in its own messages before they reach chat.

## Licence

Apache-2.0; see [`../NOTICE.md`](../NOTICE.md) and
[`../THIRD_PARTY_NOTICES-e4steam.md`](../THIRD_PARTY_NOTICES-e4steam.md). This is a port of the
Java mod [e4steam](https://github.com/Kamilhik/e4steam) by Kamilchik (Apache-2.0), which is itself
derived from e4mc (MIT).
