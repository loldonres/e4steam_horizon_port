#!/usr/bin/env bash
# Validate the bundle with the real horizon-mixins tool: export the pinned Horizon source, stage the
# mod with `apply`, and keep the plan. Nothing here compiles the game and no native code runs, so no
# trust flag is passed — that stays the player's call in the launcher.
set -euo pipefail

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
# The pinned source and the built CLI live here; the repo moves, this cache should not.
WORK=${HZ_WORK:-$HOME/.cache/e4steam-hz}
MODS=${E4STEAM_MODS:-$HERE/mixins}
HORIZON_REV=c9d474dd9116e84c243f8992d75c11314df56f06
MIXINS_REV=d9c83d7096b7ea5b83678da2efdfd6161f74997e
[ -d "$HOME/.cargo/bin" ] && export PATH="$HOME/.cargo/bin:$PATH"

mkdir -p "$WORK"
cd "$WORK"

if [ ! -f src/.horizon-revision ]; then
  echo "==> fetching the Horizon source at $HORIZON_REV"
  rm -rf src && mkdir src
  curl -sSL "https://codeload.github.com/horizon-rust/horizon/tar.gz/$HORIZON_REV" -o src.tar.gz
  tar --strip-components=1 -xzf src.tar.gz -C src
  rm src.tar.gz
  printf '%s\n' "$HORIZON_REV" > src/.horizon-revision
fi

if [ ! -f hm/Cargo.toml ]; then
  echo "==> fetching horizon-mixins at $MIXINS_REV"
  rm -rf hm && mkdir hm
  curl -sSL "https://codeload.github.com/horizon-rust/horizon-mixins/tar.gz/$MIXINS_REV" -o hm.tar.gz
  tar --strip-components=1 -xzf hm.tar.gz -C hm
  rm hm.tar.gz
fi

echo "==> building the mixins CLI"
cargo build --quiet --locked --manifest-path hm/Cargo.toml -p horizon-mixins-cli
# The package is horizon-mixins-cli; the binary inside it is just `horizon-mixins`.
MIXINS=./hm/target/debug/horizon-mixins
[ -x "$MIXINS.exe" ] && MIXINS="$MIXINS.exe"

echo "==> plan"
"$MIXINS" plan --workspace src --mods "$MODS" --out plan.json

echo "==> apply"
rm -rf stage
"$MIXINS" apply --workspace src --mods "$MODS" --out stage | tee apply.json
python - <<'PY'
import json, pathlib
plan = json.loads(pathlib.Path("plan.json").read_text())
print(json.dumps({
    "schema": plan.get("schema"),
    "injections": [i.get("injection_id") for i in plan.get("injections", [])],
    "modules": [m.get("name") for m in plan.get("modules", [])],
    "crates": [c.get("crate_id") for c in plan.get("crates", [])],
}, indent=2))
PY
echo "==> staged code"
grep -n "pub(crate) mod e4steam" stage/crates/horizon-client/src/game.rs
grep -n "steam_frame\|steam_command\|steam_commands" stage/crates/horizon-client/src/game/native_hooks.rs
grep -n "e4steam-engine" stage/crates/horizon-client/Cargo.toml
