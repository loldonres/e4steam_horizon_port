//! The sidecar event loop: localhost TCP in, E4ST frames out over Steam P2P.
//!
//! One thread owns `Steam` because the Flat API is documented as not thread-safe. That thread
//! runs the mod's tick — callbacks, proactive session accepts, the outbound drain, the message
//! drain, housekeeping, 10 ms sleep — while each bridge moves bytes between its loopback socket
//! and the shared outbound queue on its own two threads.

use crate::address::SteamAddress;
use crate::bridge::{now_ms, Bridge, RemoteSink};
use crate::frame::{self, Decode};
use crate::lan;
use crate::queue::{OutboundQueue, Packet};
use crate::reset::{self, Outcome, ResetRetries, State as RetryState};
use crate::steam::{self, Event, Steam};
use crate::chan::{unbounded, Receiver, Sender};
use std::collections::{HashMap, HashSet};
use std::net::{Ipv4Addr, SocketAddr, TcpListener, TcpStream};
use std::sync::Arc;
use std::time::Duration;

/// The mod caps one host at seven guests and the registry at 64 bridges per process.
pub const MAX_HOST_CONNECTIONS: usize = 7;
pub const MAX_BRIDGES: usize = 64;
pub const LOOPBACK_CONNECT_TIMEOUT: Duration = Duration::from_millis(100);
/// Guests that arrive inside this window after a failed connect to the LAN world are refused fast.
pub const LOOPBACK_COOLDOWN_MS: i64 = 2000;
pub const HOST_PEER_READY_FALLBACK_MS: i64 = 2000;
pub const GUEST_PEER_READY_FALLBACK_MS: i64 = 10_000;
pub const SENDS_PER_TICK: usize = 32;
pub const HELD_RETRY_BACKOFF_MS: i64 = 25;
pub const HELD_NOT_READY_BACKOFF_MS: i64 = 10;
pub const SEND_RETRY_DEADLINE_MS: i64 = 30_000;
pub const KNOWN_PEER_ACCEPT_INTERVAL_MS: i64 = 100;
pub const PENDING_PEER_TIMEOUT_MS: i64 = 10_000;
pub const IDLE_SESSION_CLOSE_DELAY_MS: i64 = 250;
/// How long a guest waits for the host endpoint to resolve before giving up.
pub const GUEST_RESOLVE_DEADLINE_MS: i64 = 30_000;
pub const LOBBY_CREATE_ATTEMPTS: u32 = 6;
pub const LOBBY_RETRY_DELAY: Duration = Duration::from_millis(500);
pub const TICK: Duration = Duration::from_millis(10);

pub const LOBBY_KEY_PROTOCOL: &str = "e4steam_protocol";
pub const LOBBY_KEY_MINECRAFT: &str = "e4steam_minecraft";
pub const LOBBY_KEY_ENDPOINT: &str = "e4steam_endpoint";
pub const PROTOCOL_VALUE: &str = "4";
pub const RICH_PRESENCE_STATUS: &str = "Hosting a Minecraft LAN world";
pub const RICH_PRESENCE_CONNECT: &str = "connect";
pub const RICH_PRESENCE_CONNECT_PREFIX: &str = "e4steam-lobby:";
pub const CHAT_ENTER_SUCCESS: u32 = 1;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Access {
    LocalOnly,
    FriendsOnly,
    InviteOnly,
    Custom,
}

impl Access {
    pub fn parse(value: &str) -> Option<Self> {
        Some(match value.to_ascii_lowercase().as_str() {
            "local" | "local-only" => Self::LocalOnly,
            "friends" | "friends-only" => Self::FriendsOnly,
            "invite" | "invite-only" => Self::InviteOnly,
            "custom" | "external" => Self::Custom,
            _ => return None,
        })
    }

    /// `SteamLobbyManager.createLobbyCompat`.
    pub fn lobby_type(self) -> i32 {
        match self {
            Self::FriendsOnly => steam::LOBBY_FRIENDS_ONLY,
            Self::Custom => steam::LOBBY_PUBLIC,
            Self::InviteOnly | Self::LocalOnly => steam::LOBBY_PRIVATE,
        }
    }

    pub fn wants_lobby(self) -> bool {
        self != Self::LocalOnly
    }

    pub fn wants_rich_presence(self) -> bool {
        matches!(self, Self::FriendsOnly | Self::Custom)
    }
}

/// Who the host lets in — decided on the host only, exactly like the mod.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Admission {
    Allowed,
    NotFriend,
    NotLobbyMember,
}

/// `SteamLobbyManager.isAllowedHostPeer`: nobody is admitted unless they are a friend, except when
/// the host opened an external access gate; beyond that FRIENDS_ONLY is done, and every other mode
/// still has to prove lobby membership.
pub fn decide_admission(
    access: Access,
    is_friend: bool,
    custom_gate_open: bool,
    in_lobby: bool,
) -> Admission {
    let custom_public = access == Access::Custom && custom_gate_open;
    if !custom_public && !is_friend {
        return Admission::NotFriend;
    }
    if access == Access::FriendsOnly {
        return Admission::Allowed;
    }
    if in_lobby {
        Admission::Allowed
    } else {
        Admission::NotLobbyMember
    }
}

/// Connection identifiers are random and never zero, so a stale peer cannot hijack a bridge.
pub fn next_connection_id(taken: impl Fn(u32) -> bool) -> u32 {
    loop {
        let candidate = crate::rng::u32_value();
        if candidate != 0 && !taken(candidate) {
            return candidate;
        }
    }
}

/// Constant-time token comparison, the Rust counterpart of `MessageDigest.isEqual`.
pub fn token_matches(expected: &[u8; 16], received: &[u8]) -> bool {
    if received.len() != expected.len() {
        return false;
    }
    let mut difference = 0u8;
    for (wanted, got) in expected.iter().zip(received) {
        difference |= wanted ^ got;
    }
    difference == 0
}

enum Control {
    Forget { steam_id: u64, connection_id: u32 },
}

/// The Steam half of every bridge, reachable from the bridge's reader thread.
struct Sink {
    outbound: Arc<OutboundQueue>,
    control: Sender<Control>,
}

impl RemoteSink for Sink {
    fn send_data(&self, steam_id: u64, connection_id: u32, payload: &[u8]) -> bool {
        match frame::encode_data(connection_id, payload) {
            Ok(bytes) => self.outbound.offer(Packet::new(steam_id, connection_id, bytes)),
            Err(_) => false,
        }
    }

    fn send_fin(&self, steam_id: u64, connection_id: u32) -> bool {
        self.outbound
            .offer(Packet::new(steam_id, connection_id, frame::encode_fin(connection_id)))
    }

    fn send_reset(&self, steam_id: u64, connection_id: u32) -> bool {
        self.outbound
            .offer(Packet::new(steam_id, connection_id, frame::encode_reset(connection_id)))
    }

    fn forget_bridge(&self, steam_id: u64, connection_id: u32) {
        let _ = self.control.send(Control::Forget { steam_id, connection_id });
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Status {
    Running,
    Stopped(String),
}

enum Role {
    Host {
        access: Access,
        token: [u8; 16],
        lan_port: u16,
    },
    Guest {
        host_steam_id: u64,
        token: [u8; 16],
    },
}

/// Lobby bookkeeping, so one tick can drive creation, joining and metadata resolution.
enum LobbyPhase {
    /// Nothing to wait for: either no lobby is used, or the guest already has the endpoint.
    Idle,
    /// The guest passed no code and waits for Steam to hand it a lobby to join.
    AwaitingInvite,
    Creating { attempts: u32, retry_at_ms: i64, waiting: bool },
    Published,
    Joining { deadline_ms: i64 },
    Resolving { deadline_ms: i64 },
}

pub struct Runtime {
    steam: Steam,
    role: Role,
    minecraft: String,
    sink: Arc<Sink>,
    outbound: Arc<OutboundQueue>,
    reset_retries: ResetRetries,
    control_rx: Receiver<Control>,
    bridges: HashMap<(u64, u32), Arc<Bridge>>,
    loopback_peers: HashMap<u16, (u64, u32)>,
    listener: Option<TcpListener>,
    held: Option<Packet>,
    held_not_before_ms: i64,
    held_deadline_ms: i64,
    next_open_attempt_ms: i64,
    next_accept_attempt_ms: i64,
    pending_peers: HashMap<u64, i64>,
    idle_sessions: HashMap<u64, i64>,
    lobby: u64,
    lobby_phase: LobbyPhase,
    status: Status,
}

impl Runtime {
    fn new(steam: Steam, role: Role, minecraft: String) -> Self {
        let outbound = Arc::new(OutboundQueue::new());
        let (control_tx, control_rx) = unbounded();
        let sink = Arc::new(Sink {
            outbound: Arc::clone(&outbound),
            control: control_tx,
        });
        Self {
            steam,
            role,
            minecraft,
            sink,
            outbound,
            reset_retries: ResetRetries::new(),
            control_rx,
            bridges: HashMap::new(),
            loopback_peers: HashMap::new(),
            listener: None,
            held: None,
            held_not_before_ms: 0,
            held_deadline_ms: 0,
            next_open_attempt_ms: 0,
            next_accept_attempt_ms: 0,
            pending_peers: HashMap::new(),
            idle_sessions: HashMap::new(),
            lobby: 0,
            lobby_phase: LobbyPhase::Idle,
            status: Status::Running,
        }
    }

    /// Hosts a Horizon LAN world that already listens on `lan_port`.
    pub fn host(steam: Steam, access: Access, lan_port: u16, minecraft: String) -> Self {
        let token = crate::rng::bytes16();
        let mut runtime = Self::new(steam, Role::Host { access, token, lan_port }, minecraft);
        if access.wants_lobby() {
            runtime.lobby_phase = LobbyPhase::Creating { attempts: 0, retry_at_ms: 0, waiting: false };
        }
        runtime
    }

    /// Joins a world whose lobby id is already known, e.g. pasted from a friend's chat message.
    pub fn guest_via_lobby(steam: Steam, lobby: u64, minecraft: String) -> Self {
        let mut runtime = Self::new(
            steam,
            Role::Guest { host_steam_id: 0, token: [0; 16] },
            minecraft,
        );
        runtime.lobby = lobby;
        runtime.steam.join_lobby(lobby);
        runtime.lobby_phase =
            LobbyPhase::Joining { deadline_ms: now_ms() + GUEST_RESOLVE_DEADLINE_MS };
        runtime
    }

    /// Waits for a Steam overlay or rich-presence invite instead of a typed code.
    pub fn await_invite(steam: Steam, minecraft: String) -> Self {
        let mut runtime = Self::new(
            steam,
            Role::Guest { host_steam_id: 0, token: [0; 16] },
            minecraft,
        );
        runtime.lobby_phase = LobbyPhase::AwaitingInvite;
        runtime
    }

    /// Joins a world by invite code; `lobby` is the lobby the code arrived through, or 0.
    pub fn guest(steam: Steam, target: SteamAddress, lobby: u64, minecraft: String) -> Self {
        let mut runtime = Self::new(
            steam,
            Role::Guest { host_steam_id: target.steam_id, token: target.token },
            minecraft,
        );
        if lobby == 0 {
            runtime.steam.accept_session(target.steam_id);
        } else {
            runtime.lobby = lobby;
            runtime.steam.join_lobby(lobby);
            runtime.lobby_phase =
                LobbyPhase::Joining { deadline_ms: now_ms() + GUEST_RESOLVE_DEADLINE_MS };
        }
        runtime
    }

    pub fn status(&self) -> &Status {
        &self.status
    }

    pub fn is_running(&self) -> bool {
        matches!(self.status, Status::Running)
    }

    /// Bridges take the sink as a trait object, so the concrete `Sink` is coerced here.
    fn bridge_sink(&self) -> Arc<dyn RemoteSink> {
        let sink: Arc<Sink> = Arc::clone(&self.sink);
        sink
    }

    /// The loopback port a guest's Minecraft client should connect to.
    pub fn local_port(&self) -> u16 {
        self.listener
            .as_ref()
            .and_then(|listener| listener.local_addr().ok())
            .map(|address| address.port())
            .unwrap_or(0)
    }

    pub fn invite_endpoint(&self) -> Option<String> {
        match &self.role {
            Role::Host { token, .. } => {
                SteamAddress::new(self.steam.my_steam_id(), *token).map(|address| address.invite_string())
            }
            Role::Guest { .. } => None,
        }
    }

    /// The Horizon LAN world port a host bridges to.
    pub fn lan_port(&self) -> u16 {
        match self.role {
            Role::Host { lan_port, .. } => lan_port,
            Role::Guest { .. } => 0,
        }
    }

    pub fn lobby(&self) -> u64 {
        self.lobby
    }

    pub fn my_steam_id(&self) -> u64 {
        self.steam.my_steam_id()
    }

    /// Live bridges, so the CLI can tell a player that a friend really arrived.
    pub fn active_bridges(&self) -> usize {
        self.bridges.values().filter(|bridge| !bridge.is_closed()).count()
    }

    /// Binds the loopback listener a guest's Minecraft client connects to.
    pub fn bind_local(&mut self, port: u16) -> Result<(), String> {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, port))
            .map_err(|error| format!("could not listen on 127.0.0.1:{port}: {error}"))?;
        listener.set_nonblocking(true).map_err(|error| error.to_string())?;
        self.listener = Some(listener);
        Ok(())
    }

    /// One tick, in the mod's order: callbacks, accepts, outbound, inbound, housekeeping.
    pub fn tick(&mut self) {
        let now = now_ms();
        self.drain_control();
        if !self.is_running() {
            return;
        }
        let callbacks = self.steam.pump_callbacks();
        self.handle_events(callbacks);
        self.accept_known_peers(now);
        self.drain_outbound();
        let messages = self.steam.poll_messages();
        self.handle_events(messages);
        self.accept_local_client();
        self.drain_control();
        self.cleanup_sessions(now);
        self.cleanup_graceful_closures(now);
        self.advance_lobby(now);
    }

    pub fn shutdown(&mut self) {
        for bridge in self.bridges.values() {
            bridge.close(false);
        }
        self.bridges.clear();
        self.loopback_peers.clear();
        self.reset_retries.cancel_all();
        self.outbound.clear();
        if self.lobby != 0 {
            self.steam.set_lobby_joinable(self.lobby, false);
            self.steam.leave_lobby(self.lobby);
            self.lobby = 0;
        }
        self.steam.clear_rich_presence();
    }

    fn fail(&mut self, reason: String) {
        if self.is_running() {
            self.status = Status::Stopped(reason);
        }
    }

    fn drain_control(&mut self) {
        while let Ok(control) = self.control_rx.try_recv() {
            match control {
                Control::Forget { steam_id, connection_id } => self.unregister(steam_id, connection_id),
            }
        }
    }

    /// `SteamRuntime.unregister`: release the loopback claim, purge the queues, forget the bridge.
    fn unregister(&mut self, steam_id: u64, connection_id: u32) {
        let key = (steam_id, connection_id);
        let bridge = match self.bridges.get(&key) {
            Some(bridge) => Arc::clone(bridge),
            None => return,
        };
        if bridge.host_side {
            let port = bridge.local_port();
            if self.loopback_peers.get(&port) == Some(&key) {
                self.loopback_peers.remove(&port);
            }
        }
        self.outbound.purge(steam_id, connection_id);
        self.reset_retries.purge(&bridge);
        self.bridges.remove(&key);
        if !self.has_bridge_for(steam_id) {
            self.idle_sessions.insert(steam_id, now_ms() + IDLE_SESSION_CLOSE_DELAY_MS);
        }
    }

    fn has_bridge_for(&self, steam_id: u64) -> bool {
        self.bridges.keys().any(|(peer, _)| *peer == steam_id)
    }

    fn open_host_bridges(&self) -> usize {
        self.bridges
            .iter()
            .filter(|(_, bridge)| bridge.host_side && !bridge.is_closed())
            .count()
    }

    /// The mod proactively accepts sessions from peers it already knows; manual dispatch cannot
    /// deliver the session-request callback, so this is what lets a guest's OPEN arrive at all.
    fn accept_known_peers(&mut self, now: i64) {
        if now < self.next_accept_attempt_ms {
            return;
        }
        self.next_accept_attempt_ms = now + KNOWN_PEER_ACCEPT_INTERVAL_MS;
        let mut peers: HashSet<u64> = HashSet::new();
        if let Role::Guest { host_steam_id, .. } = self.role {
            if host_steam_id != 0 {
                peers.insert(host_steam_id);
            }
        }
        if self.lobby != 0 {
            for index in 0..self.steam.lobby_member_count(self.lobby) {
                let member = self.steam.lobby_member(self.lobby, index);
                if member != 0 {
                    peers.insert(member);
                }
            }
        }
        let me = self.steam.my_steam_id();
        for peer in peers {
            if peer == me || self.has_bridge_for(peer) || self.pending_peers.contains_key(&peer) {
                continue;
            }
            if self.pending_peers.len() >= MAX_BRIDGES {
                break;
            }
            self.steam.accept_session(peer);
            self.pending_peers.insert(peer, now + PENDING_PEER_TIMEOUT_MS);
        }
    }

    fn cleanup_sessions(&mut self, now: i64) {
        let expired = self
            .pending_peers
            .iter()
            .filter(|(_, deadline)| **deadline <= now)
            .map(|(peer, _)| *peer)
            .collect::<Vec<_>>();
        for peer in expired {
            self.pending_peers.remove(&peer);
            if !self.has_bridge_for(peer) {
                self.steam.close_session(peer);
            }
        }
        let idle = self
            .idle_sessions
            .iter()
            .filter(|(_, deadline)| **deadline <= now)
            .map(|(peer, _)| *peer)
            .collect::<Vec<_>>();
        for peer in idle {
            self.idle_sessions.remove(&peer);
            if !self.has_bridge_for(peer) && !self.pending_peers.contains_key(&peer) {
                self.steam.close_session(peer);
            }
        }
    }

    fn cleanup_graceful_closures(&mut self, now: i64) {
        for bridge in self.bridges.values() {
            bridge.close_if_graceful_close_timed_out(now);
        }
    }

    /// Guest side: a local Minecraft client arriving is when the Steam bridge finally starts.
    fn accept_local_client(&mut self) {
        let (host_steam_id, token) = match self.role {
            Role::Guest { host_steam_id, token, .. } => (host_steam_id, token),
            Role::Host { .. } => return,
        };
        if !matches!(self.lobby_phase, LobbyPhase::Idle) {
            return;
        }
        let cloned = self.listener.as_ref().map(TcpListener::try_clone);
        let listener = match cloned {
            Some(Ok(listener)) => listener,
            Some(Err(error)) => {
                self.fail(format!("could not duplicate the loopback listener: {error}"));
                return;
            }
            None => return,
        };
        loop {
            let socket = match listener.accept() {
                Ok((socket, _)) => socket,
                Err(ref error)
                    if error.kind() == std::io::ErrorKind::WouldBlock
                        || error.kind() == std::io::ErrorKind::TimedOut =>
                {
                    return;
                }
                Err(error) => {
                    self.fail(format!("the loopback listener failed: {error}"));
                    return;
                }
            };
            let connection_id =
                next_connection_id(|candidate| self.bridges.contains_key(&(host_steam_id, candidate)));
            let bridge = Bridge::new(host_steam_id, connection_id, socket, false, self.bridge_sink());
            if self.bridges.len() >= MAX_BRIDGES {
                bridge.close(false);
                continue;
            }
            bridge.wait_peer_ready_until(now_ms() + GUEST_PEER_READY_FALLBACK_MS);
            self.bridges.insert((host_steam_id, connection_id), Arc::clone(&bridge));
            let bytes = match frame::encode_open(connection_id, &token) {
                Ok(bytes) => bytes,
                Err(_) => {
                    self.unregister(host_steam_id, connection_id);
                    bridge.close(false);
                    continue;
                }
            };
            if !self.outbound.offer(Packet::new(host_steam_id, connection_id, bytes)) {
                self.unregister(host_steam_id, connection_id);
                bridge.close(false);
                continue;
            }
            bridge.start();
            self.pending_peers.remove(&host_steam_id);
            self.idle_sessions.remove(&host_steam_id);
        }
    }

    fn handle_events(&mut self, events: Vec<Event>) {
        for event in events {
            self.handle_event(event);
        }
    }

    fn handle_event(&mut self, event: Event) {
        match event {
            Event::Message { from, bytes } => self.dispatch(from, bytes),
            Event::LobbyCreated { result, lobby } => self.on_lobby_created(result, lobby),
            Event::LobbyEntered { lobby, enter_response } => self.on_lobby_entered(lobby, enter_response),
            Event::LobbyDataUpdated { lobby, .. } => {
                if lobby == self.lobby {
                    self.resolve_guest_endpoint();
                }
            }
            Event::LobbyMemberChanged { lobby, member, .. } => {
                if lobby == self.lobby && member != self.steam.my_steam_id() {
                    self.steam.accept_session(member);
                }
            }
            Event::RichPresenceJoin { connect, .. } => {
                if let Some(id) = connect.strip_prefix(RICH_PRESENCE_CONNECT_PREFIX) {
                    if let Ok(lobby) = id.parse::<u64>() {
                        self.join_lobby_now(lobby);
                    }
                }
            }
            Event::LobbyJoinRequested { lobby, .. } => self.join_lobby_now(lobby),
        }
    }

    fn dispatch(&mut self, from: u64, bytes: Vec<u8>) {
        let frame = match frame::decode(&bytes) {
            Decode::Frame(frame) => frame,
            _ => return,
        };
        let key = (from, frame.connection_id);
        match frame.kind {
            frame::OPEN => self.handle_open(from, frame.connection_id, &frame.payload),
            frame::DATA => {
                if let Some(bridge) = self.bridges.get(&key).cloned() {
                    bridge.accept_steam_data(frame.payload);
                }
            }
            frame::FIN => {
                if let Some(bridge) = self.bridges.get(&key).cloned() {
                    bridge.accept_remote_fin();
                }
            }
            frame::RESET => {
                if let Some(bridge) = self.bridges.get(&key).cloned() {
                    bridge.reset_from_remote();
                }
            }
            frame::OPEN_ACK => self.handle_open_ack(key, &frame.payload),
            frame::BRIDGE_READY => self.handle_bridge_ready(key),
            // Voice datagrams and the addon protocol are deliberately out of scope here.
            _ => {}
        }
    }

    fn handle_open(&mut self, from: u64, connection_id: u32, token: &[u8]) {
        let (access, expected, lan_port) = match &self.role {
            Role::Host { access, token, lan_port, .. } => (*access, *token, *lan_port),
            Role::Guest { .. } => {
                self.send_standalone_reset(from, connection_id);
                return;
            }
        };
        let key = (from, connection_id);
        if self.bridges.contains_key(&key) {
            return;
        }
        // A sidecar has no external access gate, so CUSTOM degrades to friends-plus-membership.
        let is_friend = self.steam.relationship(from) == steam::RELATIONSHIP_FRIEND;
        let in_lobby = self.is_lobby_member(from);
        let admitted =
            decide_admission(access, is_friend, false, in_lobby) == Admission::Allowed;
        if !admitted || !token_matches(&expected, token) {
            self.send_standalone_reset(from, connection_id);
            return;
        }
        let now = now_ms();
        if self.open_host_bridges() >= MAX_HOST_CONNECTIONS || now < self.next_open_attempt_ms {
            self.send_standalone_reset(from, connection_id);
            return;
        }
        let target = SocketAddr::from((Ipv4Addr::LOCALHOST, lan_port));
        let socket = match TcpStream::connect_timeout(&target, LOOPBACK_CONNECT_TIMEOUT) {
            Ok(socket) => socket,
            Err(_) => {
                self.next_open_attempt_ms = now + LOOPBACK_COOLDOWN_MS;
                self.send_standalone_reset(from, connection_id);
                return;
            }
        };
        self.next_open_attempt_ms = 0;
        let bridge = Bridge::new(from, connection_id, socket, true, self.bridge_sink());
        let port = bridge.local_port();
        if let Some(existing) = self.loopback_peers.get(&port).copied() {
            let busy = self
                .bridges
                .get(&existing)
                .is_some_and(|live| !live.is_closed());
            if busy {
                bridge.close(true);
                return;
            }
            self.loopback_peers.remove(&port);
        }
        self.bridges.insert(key, Arc::clone(&bridge));
        self.loopback_peers.insert(port, key);
        bridge.wait_peer_ready_until(now + HOST_PEER_READY_FALLBACK_MS);
        let ack = frame::encode_open_ack(connection_id, 1, 0);
        if !self.outbound.offer(Packet::new(from, connection_id, ack)) {
            self.unregister(from, connection_id);
            bridge.close(true);
            return;
        }
        bridge.start();
        self.pending_peers.remove(&from);
        self.idle_sessions.remove(&from);
    }

    fn handle_open_ack(&mut self, key: (u64, u32), payload: &[u8]) {
        let bridge = match self.bridges.get(&key) {
            Some(bridge) if !bridge.host_side && !bridge.is_closed() => Arc::clone(bridge),
            _ => return,
        };
        if payload.len() != frame::OPEN_ACK_PAYLOAD_SIZE {
            bridge.close(true);
            return;
        }
        // payload[0] is the client port mode, payload[1..3] the voice host port; the sidecar does
        // not carry voice, so only the shape of the handshake matters.
        let ready = frame::encode_bridge_ready(key.1);
        let _ = self.outbound.offer(Packet::new(key.0, key.1, ready));
        bridge.mark_peer_ready();
    }

    fn handle_bridge_ready(&mut self, key: (u64, u32)) {
        if let Some(bridge) = self.bridges.get(&key).cloned() {
            if bridge.host_side && !bridge.is_closed() {
                bridge.mark_peer_ready();
            }
        }
    }

    fn is_lobby_member(&self, steam_id: u64) -> bool {
        if self.lobby == 0 {
            return false;
        }
        (0..self.steam.lobby_member_count(self.lobby))
            .any(|index| self.steam.lobby_member(self.lobby, index) == steam_id)
    }

    fn send_standalone_reset(&mut self, steam_id: u64, connection_id: u32) {
        let bytes = frame::encode_reset(connection_id);
        let _ = self
            .outbound
            .offer(Packet::detached(steam_id, connection_id, bytes));
        if !self.has_bridge_for(steam_id) {
            self.idle_sessions.insert(steam_id, now_ms() + IDLE_SESSION_CLOSE_DELAY_MS);
        }
    }

    /// `drainOutbound`: ≤32 frames per tick, one held packet, jittered RESET retries first.
    fn drain_outbound(&mut self) {
        let mut sent = self.drain_reset_retries(reset::DRAIN_PER_TICK);
        while sent < SENDS_PER_TICK {
            let now = now_ms();
            if self.held.is_some() && now < self.held_not_before_ms {
                return;
            }
            let packet = match self.held.take() {
                Some(packet) => packet,
                None => match self.outbound.poll() {
                    Some(packet) => packet,
                    None => return,
                },
            };
            sent += 1;
            let live = self.bridges.get(&(packet.steam_id, packet.connection_id)).cloned();
            if packet.kind != frame::RESET && packet.attached_to_bridge {
                if let Some(bridge) = &live {
                    let gated = !bridge.is_outbound_ready(now)
                        || (packet.kind == frame::DATA && !bridge.is_outbound_data_ready(now));
                    if gated {
                        self.hold(packet, now, HELD_NOT_READY_BACKOFF_MS);
                        return;
                    }
                }
            }
            if !self.is_packet_current(&packet, &live) {
                continue;
            }
            let unreliable = packet.kind == frame::DATAGRAM;
            let kind = packet.kind;
            let flags = if unreliable { steam::SEND_UNRELIABLE_NO_DELAY } else { steam::SEND_RELIABLE_NO_NAGLE };
            match self.steam.send(packet.steam_id, &packet.bytes, flags) {
                Ok(()) => {
                    if let Some(bridge) = &live {
                        match kind {
                            frame::FIN => bridge.mark_fin_submitted(),
                            frame::RESET => bridge.mark_reset_submitted(),
                            _ => {}
                        }
                    }
                }
                Err(_) if unreliable => {}
                Err(failure) => {
                    if failure.retryable {
                        if kind == frame::RESET {
                            self.retry_reset(&packet, &live, now);
                            continue;
                        }
                        if self.held.is_none() {
                            self.held = Some(packet);
                            self.held_deadline_ms = now + SEND_RETRY_DEADLINE_MS;
                        }
                        if now < self.held_deadline_ms {
                            self.held_not_before_ms = now + HELD_RETRY_BACKOFF_MS;
                            return;
                        }
                        self.held = None;
                    }
                    if let Some(bridge) = &live {
                        if kind == frame::RESET {
                            bridge.mark_reset_submitted();
                        } else {
                            bridge.close(false);
                        }
                    }
                }
            }
        }
    }

    fn hold(&mut self, packet: Packet, now: i64, backoff: i64) {
        if self.held.is_none() {
            self.held = Some(packet);
            self.held_deadline_ms = now + SEND_RETRY_DEADLINE_MS;
        }
        self.held_not_before_ms = now + backoff;
    }

    fn retry_reset(&mut self, packet: &Packet, live: &Option<Arc<Bridge>>, now: i64) {
        let bridge = if packet.attached_to_bridge { live.clone() } else { None };
        let offer = self.reset_retries.offer(
            packet.steam_id,
            packet.connection_id,
            packet.bytes.clone(),
            bridge,
            now,
        );
        if offer == reset::Offer::Full {
            if let Some(bridge) = live {
                bridge.mark_reset_submitted();
            }
        }
    }

    fn drain_reset_retries(&mut self, limit: usize) -> usize {
        let mut handled = 0;
        while handled < limit {
            let due = match self.reset_retries.next_due(now_ms()) {
                Some(due) => due,
                None => return handled,
            };
            handled += 1;
            if due.state != RetryState::AwaitingSend {
                continue;
            }
            let live = self.bridges.get(&(due.steam_id, due.connection_id)).cloned();
            let current = match (&due.bridge, &live) {
                (Some(held), Some(found)) => Arc::ptr_eq(held, found),
                (None, None) => true,
                _ => false,
            };
            if !current {
                self.reset_retries.cancel_stale(&due);
                if let Some(bridge) = live {
                    bridge.mark_reset_submitted();
                }
                continue;
            }
            match self.steam.send(due.steam_id, &due.bytes, steam::SEND_RELIABLE_NO_NAGLE) {
                Ok(()) => {
                    self.reset_retries.complete(&due, Outcome::Success, now_ms());
                    if let Some(bridge) = live {
                        bridge.mark_reset_submitted();
                    }
                }
                Err(failure) => {
                    let outcome = if failure.retryable { Outcome::Temporary } else { Outcome::Permanent };
                    let state = self.reset_retries.complete(&due, outcome, now_ms());
                    if state != RetryState::Scheduled {
                        if let Some(bridge) = live {
                            bridge.mark_reset_submitted();
                        }
                    }
                }
            }
        }
        handled
    }

    /// A packet is only worth sending while its bridge is still the one it was queued for.
    fn is_packet_current(&self, packet: &Packet, live: &Option<Arc<Bridge>>) -> bool {
        if !packet.attached_to_bridge {
            return packet.kind == frame::RESET && live.is_none();
        }
        match live {
            Some(bridge) => packet.kind == frame::RESET || !bridge.is_closed(),
            None => false,
        }
    }

    fn advance_lobby(&mut self, now: i64) {
        match self.lobby_phase {
            LobbyPhase::Creating { attempts, retry_at_ms, waiting } => {
                if waiting || now < retry_at_ms {
                    return;
                }
                let access = match &self.role {
                    Role::Host { access, .. } => *access,
                    Role::Guest { .. } => {
                        self.lobby_phase = LobbyPhase::Idle;
                        return;
                    }
                };
                let handle = self.steam.create_lobby(access.lobby_type(), steam::LOBBY_CAPACITY);
                if handle == 0 {
                    self.schedule_lobby_retry(attempts);
                } else {
                    self.lobby_phase = LobbyPhase::Creating {
                        attempts,
                        retry_at_ms: now + LOBBY_RETRY_DELAY.as_millis() as i64,
                        waiting: true,
                    };
                }
            }
            LobbyPhase::Joining { deadline_ms } if now >= deadline_ms => {
                self.fail("the host did not answer the lobby join within 30 seconds".to_string());
            }
            LobbyPhase::Resolving { deadline_ms } if now >= deadline_ms => {
                self.fail("could not read the host's invite from the lobby".to_string());
            }
            _ => {}
        }
    }

    fn schedule_lobby_retry(&mut self, attempts: u32) {
        if attempts + 1 >= LOBBY_CREATE_ATTEMPTS {
            self.fail("Steam refused to create a lobby".to_string());
            return;
        }
        self.lobby_phase = LobbyPhase::Creating {
            attempts: attempts + 1,
            retry_at_ms: now_ms() + LOBBY_RETRY_DELAY.as_millis() as i64,
            waiting: false,
        };
    }

    fn on_lobby_created(&mut self, result: i32, lobby: u64) {
        let attempts = match self.lobby_phase {
            LobbyPhase::Creating { attempts, .. } => attempts,
            _ => return,
        };
        if result != steam::RESULT_OK || lobby == 0 {
            if !steam::result_is_lobby_retryable(result) {
                self.fail(format!("Steam rejected the lobby (result {result})"));
                return;
            }
            self.schedule_lobby_retry(attempts);
            return;
        }
        self.lobby = lobby;
        if self.publish_lobby(lobby) {
            self.lobby_phase = LobbyPhase::Published;
        } else {
            self.steam.leave_lobby(lobby);
            self.lobby = 0;
            self.fail("Steam rejected the lobby metadata".to_string());
        }
    }

    /// Atomic republish: not joinable, write the three keys, joinable again.
    fn publish_lobby(&mut self, lobby: u64) -> bool {
        let (access, token) = match &self.role {
            Role::Host { access, token, .. } => (*access, *token),
            Role::Guest { .. } => return false,
        };
        let endpoint = match SteamAddress::new(self.steam.my_steam_id(), token) {
            Some(address) => address.invite_string(),
            None => return false,
        };
        self.steam.set_lobby_joinable(lobby, false);
        let written = self.steam.set_lobby_data(lobby, LOBBY_KEY_PROTOCOL, PROTOCOL_VALUE)
            && self.steam.set_lobby_data(lobby, LOBBY_KEY_MINECRAFT, &self.minecraft)
            && self.steam.set_lobby_data(lobby, LOBBY_KEY_ENDPOINT, &endpoint);
        if !written || !self.steam.set_lobby_joinable(lobby, true) {
            return false;
        }
        if access.wants_rich_presence() {
            self.steam.set_rich_presence("status", RICH_PRESENCE_STATUS);
            self.steam.set_rich_presence(
                RICH_PRESENCE_CONNECT,
                &format!("{RICH_PRESENCE_CONNECT_PREFIX}{lobby}"),
            );
        }
        true
    }

    fn join_lobby_now(&mut self, lobby: u64) {
        if !matches!(self.role, Role::Guest { .. }) || lobby == 0 || self.lobby != 0 {
            return;
        }
        self.lobby = lobby;
        self.steam.join_lobby(lobby);
        self.lobby_phase = LobbyPhase::Joining { deadline_ms: now_ms() + GUEST_RESOLVE_DEADLINE_MS };
    }

    fn on_lobby_entered(&mut self, lobby: u64, enter_response: u32) {
        if !matches!(self.lobby_phase, LobbyPhase::Joining { .. }) {
            return;
        }
        if enter_response != CHAT_ENTER_SUCCESS {
            self.fail(format!("Steam would not let the guest into the lobby (response {enter_response})"));
            return;
        }
        self.lobby = lobby;
        let owner = self.steam.lobby_owner(lobby);
        if owner == 0 || owner == self.steam.my_steam_id() {
            self.fail("that lobby is not hosted by anyone".to_string());
            return;
        }
        if let Role::Guest { host_steam_id, .. } = &mut self.role {
            *host_steam_id = owner;
        }
        self.steam.accept_session(owner);
        self.steam.request_lobby_data(lobby);
        self.lobby_phase = LobbyPhase::Resolving { deadline_ms: now_ms() + GUEST_RESOLVE_DEADLINE_MS };
    }

    /// A guest only trusts a lobby whose protocol, version and endpoint all agree.
    fn resolve_guest_endpoint(&mut self) {
        if !matches!(self.lobby_phase, LobbyPhase::Resolving { .. }) || self.lobby == 0 {
            return;
        }
        let protocol = self.steam.lobby_data(self.lobby, LOBBY_KEY_PROTOCOL);
        let endpoint = self.steam.lobby_data(self.lobby, LOBBY_KEY_ENDPOINT);
        if protocol.trim().is_empty() || endpoint.trim().is_empty() {
            self.steam.request_lobby_data(self.lobby);
            return;
        }
        let minecraft = self.steam.lobby_data(self.lobby, LOBBY_KEY_MINECRAFT);
        if protocol != PROTOCOL_VALUE {
            self.fail(format!(
                "the host speaks wire protocol {protocol}, this sidecar speaks {PROTOCOL_VALUE}"
            ));
            return;
        }
        if minecraft != self.minecraft {
            self.fail(format!(
                "the host is on Minecraft {minecraft}, you asked for {}",
                self.minecraft
            ));
            return;
        }
        match SteamAddress::try_parse(endpoint.trim()) {
            Some(address) if self.host_matches(address.steam_id) => self.apply_endpoint(address),
            _ => self.fail("the lobby points at an invalid invite address".to_string()),
        }
    }

    fn host_matches(&self, steam_id: u64) -> bool {
        match &self.role {
            Role::Host { .. } => false,
            Role::Guest { host_steam_id, .. } => {
                if *host_steam_id != 0 {
                    *host_steam_id == steam_id
                } else {
                    // An invite arrived through a lobby, so the lobby owner is the only host claim.
                    self.lobby != 0 && self.steam.lobby_owner(self.lobby) == steam_id
                }
            }
        }
    }

    fn apply_endpoint(&mut self, address: SteamAddress) {
        if let Role::Guest { host_steam_id, token, .. } = &mut self.role {
            *host_steam_id = address.steam_id;
            *token = address.token;
        }
        self.lobby_phase = LobbyPhase::Idle;
        self.steam.accept_session(address.steam_id);
    }
}

/// Waits for Horizon's LAN multicast announce to learn the OS-assigned world port.
pub fn detect_lan_port(host: Ipv4Addr, timeout: Duration) -> Result<u16, String> {
    let listener = lan::Listener::bind()
        .map_err(|error| format!("could not listen for LAN announces: {error}"))?;
    let announce = listener
        .wait_for(host, timeout)
        .map_err(|error| format!("LAN announce capture failed: {error}"))?
        .ok_or_else(|| format!("no LAN world announce for {host} within {timeout:?}"))?;
    announce
        .target()
        .map(|target| target.port())
        .ok_or_else(|| format!("the LAN announce carried an unusable address: {}", announce.address))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn friends_only_admits_every_friend_and_nobody_else() {
        assert_eq!(
            decide_admission(Access::FriendsOnly, true, false, false),
            Admission::Allowed
        );
        assert_eq!(
            decide_admission(Access::FriendsOnly, false, false, true),
            Admission::NotFriend
        );
    }

    #[test]
    fn invite_only_needs_membership_even_for_friends() {
        assert_eq!(
            decide_admission(Access::InviteOnly, true, false, false),
            Admission::NotLobbyMember
        );
        assert_eq!(
            decide_admission(Access::InviteOnly, true, false, true),
            Admission::Allowed
        );
    }

    #[test]
    fn custom_needs_membership_even_when_its_gate_is_open() {
        // A stranger passes the friend check only through an open custom gate, and then still has
        // to appear in the lobby member list, exactly like isAllowedHostPeer.
        assert_eq!(
            decide_admission(Access::Custom, true, false, false),
            Admission::NotLobbyMember
        );
        assert_eq!(
            decide_admission(Access::Custom, false, true, false),
            Admission::NotLobbyMember
        );
        assert_eq!(
            decide_admission(Access::Custom, false, true, true),
            Admission::Allowed
        );
        assert_eq!(
            decide_admission(Access::LocalOnly, true, false, false),
            Admission::NotLobbyMember
        );
    }

    #[test]
    fn lobby_types_match_the_mod() {
        assert_eq!(Access::FriendsOnly.lobby_type(), steam::LOBBY_FRIENDS_ONLY);
        assert_eq!(Access::Custom.lobby_type(), steam::LOBBY_PUBLIC);
        assert_eq!(Access::InviteOnly.lobby_type(), steam::LOBBY_PRIVATE);
        assert!(!Access::LocalOnly.wants_lobby());
        assert!(!Access::InviteOnly.wants_rich_presence());
    }

    #[test]
    fn access_names_are_the_mods_plus_short_forms() {
        for (text, expected) in [
            ("local", Access::LocalOnly),
            ("LOCAL_ONLY", Access::LocalOnly),
            ("friends-only", Access::FriendsOnly),
            ("invite", Access::InviteOnly),
            ("custom", Access::Custom),
        ] {
            if text == "LOCAL_ONLY" {
                assert_eq!(Access::parse("local-only"), Some(expected));
            } else {
                assert_eq!(Access::parse(text), Some(expected));
            }
        }
        assert_eq!(Access::parse("public"), None);
    }

    #[test]
    fn token_comparison_rejects_wrong_length_and_tampering() {
        let token = [7u8; 16];
        assert!(token_matches(&token, &[7u8; 16]));
        assert!(!token_matches(&token, &[7u8; 15]));
        assert!(!token_matches(&token, &[7u8; 17]));
        let mut mutated = [7u8; 16];
        mutated[15] = 8;
        assert!(!token_matches(&token, &mutated));
    }

    #[test]
    fn connection_ids_are_nonzero_and_unique() {
        let mut seen = vec![0u32; 4096];
        for slot in seen.iter_mut() {
            *slot = next_connection_id(|candidate| candidate == 0);
        }
        assert!(seen.iter().all(|id| *id != 0));
        let taken = next_connection_id(|candidate| candidate < 16);
        assert!(taken >= 16);
    }

    #[test]
    fn detached_packets_only_survive_without_a_live_bridge() {
        let queue = OutboundQueue::new();
        let _ = queue;
        let reset = Packet::detached(1, 2, frame::encode_reset(2));
        assert_eq!(reset.kind, frame::RESET);
        assert!(!reset.attached_to_bridge);
    }

    #[test]
    fn lobby_metadata_keys_are_stable() {
        assert_eq!(PROTOCOL_VALUE, "4");
        assert_eq!(LOBBY_KEY_ENDPOINT, "e4steam_endpoint");
        assert_eq!(LOBBY_KEY_PROTOCOL, "e4steam_protocol");
        assert_eq!(LOBBY_KEY_MINECRAFT, "e4steam_minecraft");
    }
}
