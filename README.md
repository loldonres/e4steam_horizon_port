# e4steam for Horizon — play your LAN world with Steam friends

**English** · [Русский](README.ru.md)

e4steam shares the Minecraft world you already opened **to LAN** with your Steam friends, over
Steam's peer-to-peer network. No port forwarding, no public IP, no server to rent: your friends
join the way they join any Steam lobby, and the game traffic goes from their PC to yours.

It talks the same wire protocol as the Java mod
[e4steam](https://github.com/Kamilhik/e4steam) (`E4ST` v4), so a friend running the Java mod on
NeoForge or Fabric can join your Horizon world, and you can join theirs.

**Two ways to run it, one engine:**

| | Runs as | Commands are typed |
|---|---|---|
| **Native mod** (`horizon-mod/`) | part of the game, built by Horizon | in the chat box: `/steam …` |
| **Sidecar** (`cli/`, `e4steam-rs.exe`) | a console window next to any Horizon | in that console: `e4steam-rs.exe host` |

Run **one at a time**: a single Steam account does not hold two Steamworks contexts, and both
would try to own the same lobby channel.

> ### Read this before installing
> This is a community mod, not an official Horizon one, and a Horizon registry listing is a
> completeness and safety check — not an endorsement and not a security audit.
>
> Native mods are Rust compiled into the game. They run **with the same access to your computer
> as Minecraft itself**: no sandbox, no permission prompts. Install them only from a source you
> trust, and read the code if you can. This project comes with **no warranty**, as Apache-2.0
> says.

---

## What you need

| | |
|---|---|
| Steam | client running and logged in as you |
| Horizon | the Nightly build pinned by the mod — see [Pinned versions](#pinned-versions) |
| Minecraft | 26.3 (protocol 777) — host and guests must state the same version string |
| OS | Windows 10/11 x64 (tested here; Linux/macOS code paths exist but nobody has run them) |
| `steam_api64.dll` | **yours, from your own Steam install** — Valve's licence is not ours to redistribute |
| Friends | the host and every guest are Steam friends, or share an invite code |

## Install the mod

**Once it is listed in the Horizon registry** — the plain way: Horizon → **Mods** → pick your
instance → install **e4steam** → **Build**.

**From GitHub** (any release of this repository, no registry wait):

```sh
horizon-launcher mods add-github https://github.com/loldonres/e4steam_horizon_port \
  --mod e4steam --instance NAME --trust-native-code
```

**From this source tree**, pointing the launcher at the bundle folder:

```sh
horizon-launcher native-build horizon-mod/mixins/e4steam --instance NAME --trust-native-code
```

All three end in the same thing: the launcher downloads its own Rust toolchain, compiles the game
with the mod inside, and installs a version named `native-<commit>-<key>`. Installed native mods
live in `<instance>/horizon-native-mods/`. **The first build takes 20–30 minutes**; later ones hit
the cache. `--trust-native-code` is the launcher asking *you* whether you trust this code — it is
deliberately not a flag a script or an assistant presses on your behalf.

Uninstall: remove it on the Mods page (or `horizon-launcher mods remove e4steam`) and rebuild.

## Host a world

1. Start your world, press **Esc**, click **Open to LAN**.
2. In chat: `/steam host`
3. The mod finds the LAN port on its own and publishes a Steam lobby. Watch the chat lines.
4. `/steam status` shows the invite code — `s-xxxxxxxxx-yyyyyyyy.steam`.

Give that code to the people you want in. **Treat it like a password: whoever has it can join your
world.** Do not paste it into a public channel, a screenshot, or a bug report.

## Join a world

1. Install the mod and build, as above.
2. In chat: `/steam join s-xxxxxxxxx-yyyyyyyy.steam` — or `join lobby <id>`, or a bare `join` to
   wait for a Steam invite while you accept it in the overlay.
3. The mod prints `127.0.0.1:<port>`. Open **Multiplayer → Direct Connect** and type that address.

A friend on Java needs nothing extra from you: their e4steam mod does the same job on their side.

## Commands

```
/steam host [--port N] [--access friends|invite|custom|local] [--discover MS] [--minecraft V]
/steam join [<invite code>|lobby <id>] [--port N] [--minecraft V]
/steam status
/steam stop
```

| Command | What it does |
|---|---|
| `host` | shares the LAN world that is already open; invites come through Steam |
| `host --port 51234` | use this port instead of searching for the LAN announce |
| `host --access friends` | who gets in — see the table below (default `friends`) |
| `host --discover 15000` | wait longer for the LAN announce, up to 60000 ms |
| `host --minecraft 26.3` | the version string guests must repeat exactly |
| `join <code>` | connect to a host; prints the `127.0.0.1:<port>` to dial |
| `join --lobby <id>` | join a known lobby instead of using a code |
| `status` | what is running now, the invite code, and what is missing |
| `stop` | close the lobby, drop the bridges, clear the presence |

| `--access` | Meaning |
|---|---|
| `friends` | only Steam friends who are in the lobby — the safe default |
| `invite` | a private lobby: only someone holding the invite code gets in |
| `custom` | a public lobby with Steam's own membership check; for exotic setups |
| `local` | no lobby is published at all; connect directly with the code |

Up to **7 guests per host**, 64 bridges per process. The mod's messages appear in chat, in English.

Running the sidecar instead? Same words, different shell: `e4steam-rs.exe host`,
`e4steam-rs.exe join <code>`, `e4steam-rs.exe doctor` for a look at the DLL, the Steam login, the
LAN interface and the world's announce. Ctrl+C closes the lobby and drops the bridges.

## What the mod sees and sends

No hidden network access — but this *is* a networking mod, so here is everything it touches.

- **Connects to Steam** (your own logged-in client) to: read your friends list, create or join a
  lobby, exchange the invite token, relay game traffic peer-to-peer, and set the "playing with …"
  rich presence when `--access friends` or `custom` is used.
- **Forwards your Minecraft LAN traffic** to the connected peers, uncompressed except where the
  Java protocol asks for zlib. Nothing else from your machine is forwarded.
- **Listens on `127.0.0.1`** so the game can dial in — a loopback socket, not a public one.
- **Reads a LAN multicast announce** (`224.0.2.60:4445`) to find your world's port.
- **Sees the SteamID64 of the peers** in the lobby: that is how the host recognises friends, just
  as in the Java mod. Chat output masks SteamIDs so they do not leak into screenshots.
- **Writes one file**: `steam_appid.txt` (the single line `480`, the Steamworks test app id the
  Java mod uses too) next to the game executable, or in the working folder.
- **No telemetry, no analytics, no accounts, no passwords, no world files read or uploaded.**
  Nothing is sent anywhere except to the peers you are actually playing with.

## It does not work — what to check

| The chat says | It usually means |
|---|---|
| `Steam is not usable: …` | Steam is not running, not logged in, or `steam_api64.dll` is unreachable. Start Steam; if needed point `E4STEAM_STEAM_API` at the full path of the DLL |
| `keep steam_api64.dll where the game finds it` | search order: `E4STEAM_STEAM_API`, beside the game exe, `steam/` next to it, Steam library folders, then the normal Windows search |
| `no LAN IPv4 address; open the world to LAN in Horizon` | you have not clicked **Open to LAN** yet, or this machine has no LAN IPv4 |
| nothing about the LAN port after a few seconds | the announce did not arrive: pass `--port` from the LAN screen, or raise `--discover` |
| `could not write steam_appid.txt` | the game folder is read-only; put a `steam_appid.txt` containing `480` beside the exe yourself |
| the guest hangs, then gives up | the host is not sharing, the code is stale, or the guest's `--minecraft` differs from the host's string |
| the game never starts after a build | the mod pins a Horizon commit your instance does not play — see [Pinned versions](#pinned-versions) |
| Windows Firewall asks | allow it for the game; Steam P2P itself needs no open ports |

## Pinned versions

A native mod is compiled against one exact Horizon commit, on purpose: it reads Horizon's own
private LAN state to find your world's port, which is not a stable public API. On another commit
the build fails loudly instead of producing something half-broken.

| Pinned | Value |
|---|---|
| Horizon commit | `c9d474dd9116e84c243f8992d75c11314df56f06` |
| horizon-mixins commit | `d9c83d7096b7ea5b83678da2efdfd6161f74997e` |
| Minecraft | `26.3` — protocol 777 |
| Java mod it is compatible with | e4steam 0.3.2 / 0.3.4 |

A pinned mod still builds after its Nightly is replaced: every Nightly keeps its source under the
tag `nightly-source/<date>-<short>`. If the launcher refuses your instance, that is the pin doing
its job — open an issue and we re-pin it.

## Cross-play with the Java mod

| Must match | How |
|---|---|
| Wire version | `E4ST` frame, version byte `4`, message kinds, the 12-byte header |
| Frame limits | 32 KiB `DATA` chunks, 1188-byte datagrams, zlib only when the flag says so |
| Version string | the `e4steam_minecraft` lobby metadata, compared character by character |
| Identity | the peer's SteamID64 comes from Steam's own message, never from the Minecraft nickname |
| Invite | 16-byte token, checked in constant time, 7 guests max, 30 s handshake deadline |

A guest whose version string differs is refused before any connection, exactly like in the Java
mod. The byte-level contract, including the timings, is in [`re/PROTOCOL.md`](re/PROTOCOL.md).

## What is inside, and what is not here yet

Done: the `E4ST` v4 codec (DATA/FIN/RESET/OPEN/OPEN_ACK/BRIDGE_READY, flags and limits), the
Steamworks **Flat API** with no SDK and no C++ (`InitFlat` + manual dispatch, ISteamUser,
ISteamFriends, ISteamMatchmaking, ISteamNetworkingMessages on channel 480, lobbies, rich presence,
the invite dialog), the localhost↔Steam bridge (32 KiB chunks, backpressure with 2048 slots and 64
reserved for terminal frames, a 256-deep inbound queue, send retries with a hold packet, a separate
RESET queue with 10 tries and 25→2000 ms exponential backoff plus jitter, a 2 s graceful close),
host-side admission with constant-time token checks, guest-side address resolution through the
lobby, and Horizon's LAN port discovery.

Not done:

- **Dedicated servers** over Steam (`SteamGameServer`, `DEDICATED_OPEN`, auth tickets) — designed, not finished.
- **Voice chat** (Plasmo Voice) and the **addon protocol** — out of scope on purpose.
- **Nickname/UUID aliasing** (`e4steam:steam-identity:v1:`) — Horizon derives the offline UUID from
  the nickname, so a friend keeps their identity only while the nickname stays.
- **A live two-player Steam test** — the developer has one Steam account, so no host-and-guest
  session on real Steam has ever been run here. Everything else is covered by 85 automated tests
  over real loopback sockets. Treat a first join as an experiment, and tell us what breaks.

## Credit, licence, and the registry rules

- **Origin.** This is a port of **e4steam** — Copyright 2026 Kamilchik, Apache-2.0,
  <https://github.com/Kamilhik/e4steam>, itself derived from **e4mc** (Copyright Skye and
  contributors, MIT). Apache-2.0 permits the port; the upstream copyright and licence notices are
  reproduced in [`NOTICE.md`](NOTICE.md) and
  [`THIRD_PARTY_NOTICES-e4steam.md`](THIRD_PARTY_NOTICES-e4steam.md).
- **Our licence.** Apache-2.0, see `LICENSE` — one the registry can read.
- **No game code or assets.** Nothing from Mojang, Microsoft or Minecraft ships here, and players
  use their own game copy. Valve's `steam_api64.dll` is not distributed — you supply the one that
  came with your Steam.
- **Source matches the release.** The `.hnmod` is packed from this repository by
  `python horizon-mod/tools/package.py`, byte-for-byte reproducible; that command prints its
  SHA-256 and the release notes carry it, so a reviewer can tie the file to the source.
- **Nothing hidden.** See [What the mod sees and sends](#what-the-mod-sees-and-sends): the network
  access *is* the feature, and it is documented.
- Community mod, not official; a listing is not an endorsement, and the code comes as is.

## Building it yourself

```sh
cargo build --release                    # the sidecar: target/release/e4steam-rs.exe
cargo test --workspace                   # 85 tests, no second account needed
python horizon-mod/tools/package.py      # -> horizon-mod/target/e4steam-<ver>.hnmod + its sha256
bash horizon-mod/tools/validate.sh       # real horizon-mixins plan + apply on the pinned source
```

`validate.sh` only *stages* the injected source with `horizon-mixins plan`/`apply` — nothing is
compiled or run, so it needs no trust flag. Compiling the injected mod is `horizon-mixins check`
or the launcher's **Build** button, and that stays the player's own decision.

Layout — [`horizon-mod/README.md`](horizon-mod/README.md) goes deeper on the bundle and the three
injections:

```
horizon-mod/mixins/e4steam/   the .hnmod bundle: manifest, injected payload, helpers
                    engine/    the shared engine: E4ST codec, Steamworks, bridge, /steam
horizon-mod/check/             a mock of Horizon's module shape, so cargo test type-checks the mod
horizon-mod/tools/             package.py (reproducible archive), validate.sh (registry rules)
cli/                           the sidecar binary
re/                            protocol spec, decompiled Java reference, Horizon docs — not shipped
```

### Before submitting to the registry

The reviewer checks that the entry is complete, that the files match the pinned hashes, that the
mod builds and loads against the named Horizon version, and that nothing obviously harmful is
there. This repository still needs three things to hold up to that:

1. **Keep build output out of git.** `target/` and `horizon-mod/target/` are generated; a `.hnmod`
   belongs in a **GitHub release asset**, not in the source tree.
2. **Do not publish `re/decompiled/` or `re/ref/`.** They hold decompiled upstream classes and the
   natives bundled inside the reference JAR, Valve's `steam_api64.dll` among them — which also makes
   `NOTICE.md`'s "not shipped with this source tree" untrue while they are in the repo. Keep
   `re/PROTOCOL.md` (our own written spec) if you want the contract on the page.
3. **Say the same thing in both places.** The registry entry needs: author, `source` pointing at
   the code the released file was built from, licence Apache-2.0, the supported Horizon version
   above, and a link to <https://github.com/Kamilhik/e4steam> with credit, since this is a port.

## Support

- Issues: <https://github.com/loldonres/e4steam_horizon_port/issues> — say what you ran, on which
  Horizon version, which mod version, and paste the chat lines.
- **Redact before posting**: invite codes (`s-….steam`), other players' SteamIDs, and addresses in
  any screenshot or log. The mod masks them in its own messages; your screenshot will not.
- Protocol questions: read [`re/PROTOCOL.md`](re/PROTOCOL.md) first.
