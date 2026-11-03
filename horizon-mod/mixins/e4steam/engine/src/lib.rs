//! Steam P2P transport for Minecraft LAN worlds, wire-compatible with the e4steam Java mod.
//!
//! Both frontends share this crate: the Horizon native mod injects it into the game process, and
//! `e4steam-cli` runs it as a sidecar next to any launcher. It owns the E4ST v4 frame codec, the
//! Steamworks Flat API binding, the bridge runtime and the `/steam` command session; `session` is
//! the one the mod drives, so the two forms behave the same.

pub mod address;
pub mod bridge;
pub mod chan;
pub mod dll;
pub mod frame;
pub mod lan;
pub mod queue;
pub mod reset;
pub mod rng;
pub mod runtime;
pub mod session;
pub mod steam;
