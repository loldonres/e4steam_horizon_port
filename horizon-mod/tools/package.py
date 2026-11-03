#!/usr/bin/env python3
"""Package `mixins/<id>/` as the single `.hnmod` file Horizon's launcher takes.

The archive is `horizon-native.json` plus every `.rs` and `.toml` under the bundle, crates included,
with fixed dates and modes, so the same sources always give the same bytes and the same SHA-256.
Nothing here runs the mod: these checks are the rules horizon-mixins and the launcher apply, read
from the pinned Horizon export, so a bundle that fails here fails in the same way later — in
seconds, on this machine, instead of in the middle of a game build.

    python tools/package.py [--out DIR] [--horizon PATH]

`--horizon` is a Horizon source export or clone at the pinned commit; the pinned export under `re/`
is used when nothing is given, for its `Cargo.lock` (the allow list a bundle crate's dependencies
must be inside) and its `native-hooks.json` (the hook catalog).
"""
import argparse
import hashlib
import json
import re
import tomllib
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GAME = "26.3"
# What `horizon_mods::index::native::Bundle::read` takes out of a `.hnmod`.
BUNDLE_SUFFIXES = {".rs", ".toml"}
CRATE_TEXT = re.compile(r"^(README|LICENSE|COPYING|NOTICE|\.gitignore)", re.IGNORECASE)
MAX_FILES = 512
MAX_UNPACKED = 64 * 1024 * 1024
# A module payload becomes `pub(crate) mod <name> { … }`: no outer wrapper, no extra files, no
# attributes on the file itself.
FORBIDDEN_PAYLOAD = [
    (re.compile(r"^\s*(?:pub(?:\([^)]*\))?\s+)?mod\s+\w+\s*;", re.M), "an external `mod` file"),
    (re.compile(r"#\[path\b"), "`#[path]`"),
    (re.compile(r"\binclude(?:_bytes|_str)?!\s*\("), "an include macro"),
    (re.compile(r"^\s*#!\[", re.M), "a file attribute"),
    (re.compile(r"^\s*//!", re.M), "an inner file doc comment"),
]


def fail(message):
    raise SystemExit(f"e4steam: {message}")


def read_hook_catalog(horizon):
    """`native-hooks.json` from the pinned source: the names a bundle may hook without a raw target."""
    path = Path(horizon) / "native-hooks.json"
    if not path.is_file():
        return None
    catalog = {}
    for entry in json.loads(path.read_text(encoding="utf-8"))["hooks"]:
        catalog.setdefault((entry["name"], entry["version"]), entry["target"])
    return catalog


def read_locked_packages(horizon):
    """Package names in the pinned Horizon `Cargo.lock`: the only crates a bundle crate may need."""
    path = Path(horizon) / "Cargo.lock"
    if not path.is_file():
        return None
    text = path.read_text(encoding="utf-8")
    return {
        found.group(1)
        for block in text.split("[[package]]")
        for found in [re.search(r'^name = "([^"]+)"', block, re.M)]
        if found
    }


def check_crate(crate_dir, locked):
    """Rules of `[[crate]]`: a plain registry package, no lock of its own, nothing outside the folder."""
    manifest_path = crate_dir / "Cargo.toml"
    if not manifest_path.is_file():
        fail(f"{crate_dir}: a [[crate]] path needs a Cargo.toml")
    if (crate_dir / "Cargo.lock").exists():
        fail(f"{crate_dir}: a bundle crate must not carry a Cargo.lock (E_CRATE)")
    manifest = tomllib.loads(manifest_path.read_text(encoding="utf-8"))
    allowed_tables = {
        "package", "lib", "bin", "test", "example", "bench",
        "features", "lints", "badges", "dependencies", "dev-dependencies", "build-dependencies",
    }
    extra = sorted(set(manifest) - allowed_tables)
    if extra:
        fail(f"{crate_dir}: tables a bundle crate cannot use: {', '.join(extra)} (E_CRATE)")
    package = manifest.get("package", {})
    if "workspace" in package:
        fail(f"{crate_dir}: package.workspace is refused (E_CRATE)")
    name = package.get("name")
    if not isinstance(name, str):
        fail(f"{crate_dir}: a bundle crate needs a literal package name")
    if locked is not None and name.replace("-", "_") in {n.replace("-", "_") for n in locked}:
        fail(f"{crate_dir}: {name} is already a package in Horizon's lock (E_CRATE)")
    if not (crate_dir / "src").is_dir():
        fail(f"{crate_dir}: crate {name} has no src/")

    for table in ("dependencies", "dev-dependencies", "build-dependencies"):
        for dep, spec in manifest.get(table, {}).items():
            requirement = spec if isinstance(spec, str) else spec.get("version")
            if isinstance(spec, dict) and (set(spec) - {"version", "features", "default-features", "optional"}):
                fail(f"{crate_dir}: {table} {dep} uses fields a bundle crate cannot (E_CRATE_DEPENDENCY)")
            if not isinstance(requirement, str):
                fail(f"{crate_dir}: {table} {dep} must be a plain registry requirement (E_CRATE_DEPENDENCY)")
            if locked is not None and dep.replace("-", "_") not in {n.replace("-", "_") for n in locked}:
                fail(f"{crate_dir}: {table} {dep} {requirement} is not in Horizon's Cargo.lock (E_CRATE_DEPENDENCY)")
    return name


def check_payload(text, name):
    for pattern, description in FORBIDDEN_PAYLOAD:
        if pattern.search(text):
            fail(f"{name}: a module payload cannot use {description}")


def check_helper(text, path):
    """One private, synchronous, safe function, no attributes, no generics: what a helper file is."""
    if re.search(r"^\s*pub\s+fn\b", text, re.M):
        fail(f"{path}: a helper function must be private")
    if re.search(r"^\s*#\[[^\]]*\]\s*$", text, re.M) or re.search(r"^\s*#!\[", text, re.M):
        fail(f"{path}: a helper function cannot carry attributes")
    if len(re.findall(r"^\s*fn\b", text, re.M)) != 1:
        fail(f"{path}: a helper file contains exactly one function")
    if re.search(r"\b(unsafe|async|extern)\b", text):
        fail(f"{path}: a helper must be a plain synchronous safe function")


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out", default=str(ROOT / "target"), help="where to write the .hnmod")
    parser.add_argument("--horizon", help="Horizon source at the pinned commit (for Cargo.lock and native-hooks.json)")
    args = parser.parse_args()

    horizon = Path(args.horizon) if args.horizon else ROOT.parent / "re" / "horizon-docs"
    locked = read_locked_packages(horizon)
    catalog = read_hook_catalog(horizon)
    if locked is None or catalog is None:
        print(f"note: {horizon} has no Cargo.lock or native-hooks.json, so crate and hook names are not checked")

    compat = json.loads((ROOT / "compatibility.json").read_text(encoding="utf-8"))
    manifests = sorted((ROOT / "mixins").glob("*/horizon-mixin.toml"))
    if len(manifests) != 1:
        fail("a .hnmod holds one bundle: keep one mixins/<id>/horizon-mixin.toml")
    bundle = manifests[0].parent
    mixin = tomllib.loads(manifests[0].read_text(encoding="utf-8"))
    mod = mixin["mod"]
    if bundle.name != mod["id"]:
        fail(f"rename {bundle}/ to mixins/{mod['id']}/ (the registry finds a bundle by id)")
    if not mod["id"].replace("_", "").isalnum() or not mod["id"].islower():
        fail("the registry takes ids of lowercase letters, digits and _ only")
    if mixin.get("compatibility", {}).get("horizon_revision") != compat["horizon_revision"]:
        fail("horizon-mixin.toml and compatibility.json pin different Horizon revisions")

    ids = set()
    for crate in mixin.get("crate", []):
        if crate["id"] in ids:
            fail(f"id {crate['id']} is used twice (ids are unique within a bundle)")
        ids.add(crate["id"])
        if not Path(crate["path"]).is_absolute() and ".." in Path(crate["path"]).parts:
            fail(f"crate path {crate['path']} leaves the bundle")
        check_crate(bundle / crate["path"], locked)

    for module in mixin.get("module", []):
        if module["id"] in ids:
            fail(f"id {module['id']} is used twice (ids are unique within a bundle)")
        ids.add(module["id"])
        source = bundle / module["source"]
        if not source.is_file():
            fail(f"[[module]] {module['id']} names no source: {source}")
        check_payload(source.read_text(encoding="utf-8"), module["source"])
        # The destination's fingerprint is what the plan records; a flat export keeps only the name.
        wanted = compat.get("source_sha256", {}).get(module["file"])
        for candidate in (horizon / module["file"], horizon / Path(module["file"]).name):
            if wanted and candidate.is_file():
                local = hashlib.sha256(candidate.read_bytes()).hexdigest()
                if local != wanted:
                    print(f"note: {candidate} is not the {module['file']} the bundle was checked against")
                break

    for injection in mixin.get("injection", []):
        if injection["id"] in ids:
            fail(f"id {injection['id']} is used twice (ids are unique within a bundle)")
        ids.add(injection["id"])
        helper = bundle / injection["helper"]
        if not helper.is_file():
            fail(f"[[injection]] {injection['id']} names no helper: {helper}")
        check_helper(helper.read_text(encoding="utf-8"), injection["helper"])
        named = "hook" in injection
        if named == ("target" in injection):
            fail(f"[[injection]] {injection['id']} needs exactly one of `hook` and `target` (E_MANIFEST)")
        if named:
            if "hook_version" not in injection:
                fail(f"[[injection]] {injection['id']} names a hook without a version (E_MANIFEST)")
            if catalog is not None and (injection["hook"], injection["hook_version"]) not in catalog:
                fail(f"[[injection]] {injection['id']} names hook {injection['hook']} "
                     f"version {injection['hook_version']}, which the catalog does not list")
        for binding in injection.get("bindings", []):
            for key in ("name", "ty", "value"):
                if key not in binding:
                    fail(f"[[injection]] {injection['id']}: a binding needs {key}")

    manifest = dict(schema=1, id=mod["id"], version=mod["version"], game=GAME,
                    horizon=compat["horizon_revision"], mixins=compat["horizon_mixins_revision"])
    files = {"horizon-native.json": (json.dumps(manifest, indent=2, sort_keys=True) + "\n").encode()}
    for path in sorted(bundle.rglob("*")):
        if not path.is_file():
            continue
        if path.suffix not in BUNDLE_SUFFIXES and not CRATE_TEXT.match(path.name):
            fail(f"{path.relative_to(bundle)}: a .hnmod carries only Rust, TOML and data files")
        files[path.relative_to(ROOT).as_posix()] = path.read_bytes()
    unpacked = sum(len(content) for content in files.values())
    if len(files) > MAX_FILES or unpacked > MAX_UNPACKED:
        fail(f"{len(files)} files, {unpacked} bytes: over what a .hnmod may hold")

    out = Path(args.out) / f"{mod['id']}-{mod['version']}.hnmod"
    out.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(out, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, content in sorted(files.items()):
            info = zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, content)
    print(json.dumps(dict(
        file=str(out),
        files=len(files),
        size=out.stat().st_size,
        unpacked=unpacked,
        sha256=hashlib.sha256(out.read_bytes()).hexdigest(),
    ), indent=2))


if __name__ == "__main__":
    main()
