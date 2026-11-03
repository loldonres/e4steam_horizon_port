//! Thin, hand-written binding to the Steamworks **Flat API**.
//!
//! Only symbols exported by the redist `steam_api64.dll` are used, so no SDK, no C++
//! and no callback machinery is required: everything is polled with
//! `SteamAPI_ManualDispatch_*`. `Steam` is `Send` but deliberately not `Sync`, because
//! the Flat API is documented as not thread-safe: one thread owns it.

use crate::dll::Library;
use crate::frame::MAX_ACCEPTED_PACKET_SIZE;
use std::ffi::{c_char, c_void, CString};
use std::path::PathBuf;

pub const APP_ID: u32 = 480;
/// Virtual port / channel the Java mod uses for LAN sessions.
pub const CHANNEL: i32 = 480;
pub const LOBBY_CAPACITY: i32 = 8;

pub const SEND_NO_NAGLE: i32 = 1;
pub const SEND_NO_DELAY: i32 = 4;
pub const SEND_RELIABLE: i32 = 8;
pub const SEND_USE_CURRENT_THREAD: i32 = 16;
pub const SEND_AUTO_RESTART_BROKEN_SESSION: i32 = 32;
pub const SEND_RELIABLE_NO_NAGLE: i32 = SEND_RELIABLE | SEND_NO_NAGLE;
pub const SEND_UNRELIABLE_NO_DELAY: i32 = SEND_NO_DELAY | SEND_NO_NAGLE;

pub const IDENTITY_INVALID: i32 = 0;
pub const IDENTITY_STEAM_ID: i32 = 16;

pub const CONNECTION_NONE: i32 = 0;
pub const CONNECTION_CONNECTING: i32 = 1;
pub const CONNECTION_FINDING_ROUTE: i32 = 2;
pub const CONNECTION_CONNECTED: i32 = 3;
pub const CONNECTION_CLOSED_BY_PEER: i32 = 4;
pub const CONNECTION_PROBLEM_DETECTED: i32 = 5;

pub const RESULT_OK: i32 = 1;
pub const RESULT_NO_CONNECTION: i32 = 3;
pub const RESULT_INVALID_PARAM: i32 = 8;
pub const RESULT_BUSY: i32 = 10;
pub const RESULT_ACCESS_DENIED: i32 = 15;
pub const RESULT_TIMEOUT: i32 = 16;
pub const RESULT_SERVICE_UNAVAILABLE: i32 = 20;
pub const RESULT_LIMIT_EXCEEDED: i32 = 25;

/// Mirrors the mod's retryable set for outbound frames.
pub fn result_is_retryable(result: i32) -> bool {
    matches!(
        result,
        RESULT_LIMIT_EXCEEDED | RESULT_BUSY | RESULT_NO_CONNECTION | RESULT_SERVICE_UNAVAILABLE
    )
}

/// The mod retries lobby creation on a narrower set than frame sends.
pub fn result_is_lobby_retryable(result: i32) -> bool {
    matches!(
        result,
        RESULT_TIMEOUT | RESULT_NO_CONNECTION | RESULT_SERVICE_UNAVAILABLE | RESULT_BUSY
    )
}

pub const CALLBACK_GET_AUTH_SESSION_TICKET: i32 = 163;
pub const CALLBACK_VALIDATE_AUTH_TICKET: i32 = 143;
pub const CALLBACK_GAME_LOBBY_JOIN_REQUESTED: i32 = 333;
pub const CALLBACK_GAME_RICH_PRESENCE_JOIN_REQUESTED: i32 = 337;
pub const CALLBACK_LOBBY_ENTER: i32 = 504;
pub const CALLBACK_LOBBY_DATA_UPDATE: i32 = 505;
pub const CALLBACK_LOBBY_CHAT_UPDATE: i32 = 506;
pub const CALLBACK_LOBBY_CREATED: i32 = 513;
pub const CALLBACK_STEAM_API_CALL_COMPLETED: i32 = 703;

pub const LOBBY_PRIVATE: i32 = 0;
pub const LOBBY_FRIENDS_ONLY: i32 = 1;
pub const LOBBY_PUBLIC: i32 = 2;
pub const LOBBY_INVISIBLE: i32 = 3;

pub const RELATIONSHIP_NONE: i32 = 0;
/// `EFriendRelationship::EFriend` — the only value the mod's host admits without a lobby.
pub const RELATIONSHIP_FRIEND: i32 = 3;
pub const SEND_RATE_CEILING_BYTES_PER_SEC: i32 = 4_194_304;
/// `ESteamNetworkingConfigValue::k_ESteamNetworkingConfig_SendRateMax`.
pub const CONFIG_SEND_RATE_MAX: i32 = 11;
const STEAM_ERR_MESSAGE_SIZE: usize = 1024;

#[repr(C)]
#[derive(Clone, Copy)]
pub struct CallbackMsg {
    pub h_steam_user: i32,
    pub i_callback: i32,
    pub pub_param: *mut u8,
    pub cub_param: i32,
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct SteamNetworkingIdentity {
    pub e_type: i32,
    pub cb_size: i32,
    pub value: [u8; 128],
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct SteamNetworkingMessage {
    pub p_data: *mut c_void,
    pub cb_size: i32,
    pub conn: i32,
    pub identity_peer: SteamNetworkingIdentity,
    pub n_conn_user_data: i64,
    pub usec_time_received: i64,
    pub n_message_number: i64,
    pub pfn_free_data: Option<unsafe extern "C" fn(*mut SteamNetworkingMessage)>,
    pub pfn_release: Option<unsafe extern "C" fn(*mut SteamNetworkingMessage)>,
    pub n_channel: i32,
    pub n_flags: i32,
    pub n_user_data: i64,
    pub idx_lane: u16,
    pub pad: u16,
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct ConnectionInfo {
    pub identity_remote: SteamNetworkingIdentity,
    pub n_user_data: i64,
    pub h_listen_socket: i32,
    pub addr_remote: [u8; 18],
    pub pad1: [u8; 2],
    pub pop_remote: u32,
    pub pop_relay: u32,
    pub state: i32,
    pub end_reason: i32,
    pub end_debug: [u8; 128],
    pub connection_description: [u8; 128],
    pub n_flags: i32,
    /// 63 is what this SDK ships; the slack matches the Java mod's over-allocation so a newer
    /// `steam_api64.dll` that grows the struct cannot overflow our buffer.
    pub reserved: [u32; 81],
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct RealTimeStatus {
    pub state: i32,
    pub ping: i32,
    pub queue_local: i32,
    pub queue_remote: i32,
    pub out_packets_per_sec: i32,
    pub out_bytes_per_sec: i32,
    pub in_packets_per_sec: i32,
    pub in_bytes_per_sec: i32,
    pub send_rate_bytes_per_sec: i32,
    pub pending_unreliable: i32,
    pub pending_reliable: i32,
    pub sent_unacked: i32,
    pub usec_queue_time: i64,
    pub reserved: [i32; 18],
}

macro_rules! zeroed_default {
    ($($type:ty),+ $(,)?) => {
        $(impl Default for $type {
            fn default() -> Self {
                // Every field is an integer, a pointer or a function pointer: all-zero is valid.
                unsafe { std::mem::zeroed() }
            }
        })+
    };
}

zeroed_default!(
    CallbackMsg,
    SteamNetworkingIdentity,
    SteamNetworkingMessage,
    ConnectionInfo,
    RealTimeStatus
);

impl SteamNetworkingIdentity {
    pub fn from_steam_id(steam_id: u64) -> Self {
        let mut identity = Self::default();
        identity.e_type = IDENTITY_STEAM_ID;
        identity.cb_size = 8;
        identity.value[..8].copy_from_slice(&steam_id.to_ne_bytes());
        identity
    }

    pub fn steam_id(&self) -> u64 {
        if self.e_type != IDENTITY_STEAM_ID || self.cb_size < 8 {
            return 0;
        }
        let mut bytes = [0u8; 8];
        bytes.copy_from_slice(&self.value[..8]);
        u64::from_ne_bytes(bytes)
    }
}

#[repr(C)]
#[derive(Clone, Copy)]
struct RawLobbyCreated {
    result: i32,
    lobby: u64,
}

#[repr(C)]
#[derive(Clone, Copy)]
struct RawLobbyEnter {
    lobby: u64,
    chat_permissions: u32,
    locked: u8,
    enter_response: u32,
}

#[repr(C)]
#[derive(Clone, Copy)]
struct RawLobbyDataUpdate {
    lobby: u64,
    member: u64,
    success: u8,
}

#[repr(C)]
#[derive(Clone, Copy)]
struct RawLobbyChatUpdate {
    lobby: u64,
    user_changed: u64,
    user_making_change: u64,
    flags: u32,
}

#[repr(C)]
#[derive(Clone, Copy)]
struct RawRichPresenceJoin {
    friend: u64,
    connect: [u8; 256],
}

#[repr(C)]
#[derive(Clone, Copy)]
struct RawLobbyJoinRequested {
    lobby: u64,
    friend: u64,
}

#[repr(C)]
#[derive(Clone, Copy)]
struct RawApiCallCompleted {
    h_async_call: u64,
    i_callback: i32,
    cub_param: u32,
}

zeroed_default!(
    RawLobbyCreated,
    RawLobbyEnter,
    RawLobbyDataUpdate,
    RawLobbyChatUpdate,
    RawRichPresenceJoin,
    RawLobbyJoinRequested,
    RawApiCallCompleted
);

const _: () = assert!(std::mem::size_of::<CallbackMsg>() == 24);
const _: () = assert!(std::mem::size_of::<SteamNetworkingIdentity>() == 136);
const _: () = assert!(std::mem::size_of::<SteamNetworkingMessage>() == 216);
const _: () = assert!(std::mem::size_of::<ConnectionInfo>() == 768);
const _: () = assert!(std::mem::size_of::<RealTimeStatus>() == 128);
const _: () = assert!(std::mem::size_of::<RawLobbyCreated>() == 16);
const _: () = assert!(std::mem::size_of::<RawLobbyEnter>() == 24);
const _: () = assert!(std::mem::size_of::<RawLobbyDataUpdate>() == 24);
const _: () = assert!(std::mem::size_of::<RawRichPresenceJoin>() == 264);
const _: () = assert!(std::mem::offset_of!(SteamNetworkingMessage, p_data) == 0);
const _: () = assert!(std::mem::offset_of!(SteamNetworkingMessage, cb_size) == 8);
const _: () = assert!(std::mem::offset_of!(SteamNetworkingMessage, identity_peer) == 16);
const _: () = assert!(std::mem::offset_of!(SteamNetworkingMessage, pfn_release) == 184);
const _: () = assert!(std::mem::offset_of!(SteamNetworkingMessage, n_channel) == 192);
const _: () = assert!(std::mem::offset_of!(ConnectionInfo, state) == 176);
const _: () = assert!(std::mem::offset_of!(RealTimeStatus, pending_reliable) == 40);
const _: () = assert!(std::mem::offset_of!(RealTimeStatus, sent_unacked) == 44);

#[derive(Debug)]
pub enum Event {
    LobbyCreated { result: i32, lobby: u64 },
    LobbyEntered { lobby: u64, enter_response: u32 },
    LobbyDataUpdated { lobby: u64, member: u64 },
    LobbyMemberChanged { lobby: u64, member: u64, flags: u32 },
    RichPresenceJoin { friend: u64, connect: String },
    LobbyJoinRequested { lobby: u64, friend: u64 },
    /// One Steam message carries exactly one E4ST frame; the runtime decodes it.
    Message { from: u64, bytes: Vec<u8> },
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SendFailure {
    pub result: i32,
    pub retryable: bool,
}

/// Lets a worker thread free its own per-thread SDK scratch buffers before exiting.
#[derive(Clone, Copy)]
pub struct ThreadMemoryReleaser(unsafe extern "C" fn());

unsafe impl Send for ThreadMemoryReleaser {}
unsafe impl Sync for ThreadMemoryReleaser {}

impl ThreadMemoryReleaser {
    pub fn release(&self) {
        unsafe { (self.0)() }
    }
}

macro_rules! api_fns {
    ($($name:ident : $ty:ty = $symbol:literal ;) +) => {
        struct Api {
            $( $name: $ty, )+
        }

        fn load_api(library: &Library) -> Result<Api, String> {
            Ok(Api {
                $( $name: need!(library, $symbol, $ty), )+
            })
        }
    };
}

macro_rules! need {
    ($library:expr, $symbol:expr, $ty:ty) => {{
        let pointer = unsafe { try_symbol::<$ty>($library, $symbol) };
        pointer.ok_or_else(|| format!("steam api export missing: {}", $symbol))?
    }};
}

api_fns! {
    init_flat: unsafe extern "C" fn(*mut c_char) -> i32 = "SteamAPI_InitFlat";
    manual_dispatch_init: unsafe extern "C" fn() = "SteamAPI_ManualDispatch_Init";
    manual_run_frame: unsafe extern "C" fn(i32) = "SteamAPI_ManualDispatch_RunFrame";
    manual_get_next_callback: unsafe extern "C" fn(i32, *mut CallbackMsg) -> bool = "SteamAPI_ManualDispatch_GetNextCallback";
    manual_free_last_callback: unsafe extern "C" fn(i32) = "SteamAPI_ManualDispatch_FreeLastCallback";
    manual_get_api_call_result: unsafe extern "C" fn(i32, u64, *mut c_void, i32, i32, *mut bool) -> bool = "SteamAPI_ManualDispatch_GetAPICallResult";
    get_h_steam_pipe: unsafe extern "C" fn() -> i32 = "SteamAPI_GetHSteamPipe";
    shutdown: unsafe extern "C" fn() = "SteamAPI_Shutdown";
    release_thread_memory: unsafe extern "C" fn() = "SteamAPI_ReleaseCurrentThreadMemory";
    interface_user: unsafe extern "C" fn() -> *mut c_void = "SteamAPI_SteamUser_v023";
    interface_matchmaking: unsafe extern "C" fn() -> *mut c_void = "SteamAPI_SteamMatchmaking_v009";
    interface_friends: unsafe extern "C" fn() -> *mut c_void = "SteamAPI_SteamFriends_v018";
    interface_messages: unsafe extern "C" fn() -> *mut c_void = "SteamAPI_SteamNetworkingMessages_SteamAPI_v002";
    interface_networking_utils: unsafe extern "C" fn() -> *mut c_void = "SteamAPI_SteamNetworkingUtils_SteamAPI_v004";
    user_get_steam_id: unsafe extern "C" fn(*mut c_void) -> u64 = "SteamAPI_ISteamUser_GetSteamID";
    user_get_auth_ticket: unsafe extern "C" fn(*mut c_void, *mut c_void, i32, *mut u32, *const SteamNetworkingIdentity) -> u32 = "SteamAPI_ISteamUser_GetAuthSessionTicket";
    messages_send: unsafe extern "C" fn(*mut c_void, *const SteamNetworkingIdentity, *const c_void, u32, i32, i32) -> i32 = "SteamAPI_ISteamNetworkingMessages_SendMessageToUser";
    messages_receive: unsafe extern "C" fn(*mut c_void, i32, *mut *mut SteamNetworkingMessage, i32) -> i32 = "SteamAPI_ISteamNetworkingMessages_ReceiveMessagesOnChannel";
    messages_accept_session: unsafe extern "C" fn(*mut c_void, *const SteamNetworkingIdentity) -> i32 = "SteamAPI_ISteamNetworkingMessages_AcceptSessionWithUser";
    messages_close_session: unsafe extern "C" fn(*mut c_void, *const SteamNetworkingIdentity) -> bool = "SteamAPI_ISteamNetworkingMessages_CloseSessionWithUser";
    messages_session_info: unsafe extern "C" fn(*mut c_void, *const SteamNetworkingIdentity, *mut ConnectionInfo, *mut RealTimeStatus) -> i32 = "SteamAPI_ISteamNetworkingMessages_GetSessionConnectionInfo";
    release_message: unsafe extern "C" fn(*mut SteamNetworkingMessage) = "SteamAPI_SteamNetworkingMessage_t_Release";
    create_lobby: unsafe extern "C" fn(*mut c_void, i32, i32) -> u64 = "SteamAPI_ISteamMatchmaking_CreateLobby";
    join_lobby: unsafe extern "C" fn(*mut c_void, u64) -> u64 = "SteamAPI_ISteamMatchmaking_JoinLobby";
    leave_lobby: unsafe extern "C" fn(*mut c_void, u64) -> bool = "SteamAPI_ISteamMatchmaking_LeaveLobby";
    set_lobby_data: unsafe extern "C" fn(*mut c_void, u64, *const c_char, *const c_char) -> bool = "SteamAPI_ISteamMatchmaking_SetLobbyData";
    get_lobby_data: unsafe extern "C" fn(*mut c_void, u64, *const c_char) -> *const c_char = "SteamAPI_ISteamMatchmaking_GetLobbyData";
    set_lobby_joinable: unsafe extern "C" fn(*mut c_void, u64, bool) -> bool = "SteamAPI_ISteamMatchmaking_SetLobbyJoinable";
    set_lobby_type: unsafe extern "C" fn(*mut c_void, u64, i32) -> bool = "SteamAPI_ISteamMatchmaking_SetLobbyType";
    get_lobby_owner: unsafe extern "C" fn(*mut c_void, u64) -> u64 = "SteamAPI_ISteamMatchmaking_GetLobbyOwner";
    count_lobby_members: unsafe extern "C" fn(*mut c_void, u64) -> i32 = "SteamAPI_ISteamMatchmaking_GetNumLobbyMembers";
    get_lobby_member: unsafe extern "C" fn(*mut c_void, u64, i32) -> u64 = "SteamAPI_ISteamMatchmaking_GetLobbyMemberByIndex";
    invite_user_to_lobby: unsafe extern "C" fn(*mut c_void, u64, u64) -> bool = "SteamAPI_ISteamMatchmaking_InviteUserToLobby";
    request_lobby_data: unsafe extern "C" fn(*mut c_void, u64) -> bool = "SteamAPI_ISteamMatchmaking_RequestLobbyData";
    get_friend_relationship: unsafe extern "C" fn(*mut c_void, u64) -> i32 = "SteamAPI_ISteamFriends_GetFriendRelationship";
    get_friend_persona_name: unsafe extern "C" fn(*mut c_void, u64) -> *const c_char = "SteamAPI_ISteamFriends_GetFriendPersonaName";
    set_rich_presence: unsafe extern "C" fn(*mut c_void, *const c_char, *const c_char) -> bool = "SteamAPI_ISteamFriends_SetRichPresence";
    clear_rich_presence: unsafe extern "C" fn(*mut c_void) = "SteamAPI_ISteamFriends_ClearRichPresence";
    activate_invite_dialog: unsafe extern "C" fn(*mut c_void, u64) -> bool = "SteamAPI_ISteamFriends_ActivateGameOverlayInviteDialog";
    set_global_int32: unsafe extern "C" fn(*mut c_void, i32, i32) = "SteamAPI_ISteamNetworkingUtils_SetGlobalConfigValueInt32";
    init_relay_access: unsafe extern "C" fn(*mut c_void) = "SteamAPI_ISteamNetworkingUtils_InitRelayNetworkAccess";
}

unsafe fn try_symbol<T: Copy>(library: &Library, name: &str) -> Option<T> {
    debug_assert_eq!(std::mem::size_of::<T>(), std::mem::size_of::<usize>());
    let address = library.address(name)?;
    // Every Steam entry point is an ordinary `__cdecl` function, so the address *is* the pointer.
    Some(std::mem::transmute_copy(&address))
}

pub struct Steam {
    _library: Library,
    api: Api,
    matchmaking: *mut c_void,
    messages: *mut c_void,
    user: *mut c_void,
    friends: *mut c_void,
    networking_utils: *mut c_void,
    pipe: i32,
    steam_id: u64,
}

unsafe impl Send for Steam {}

impl Steam {
    /// Loads the redist DLL, makes sure App ID 480 is visible to it and starts manual dispatch.
    pub fn init() -> Result<Self, String> {
        ensure_app_id_file()?;
        let path = locate_steam_dll()?;
        let library = Library::open(&path).map_err(|error| format!("could not load {path:?}: {error}"))?;
        let api = load_api(&library)?;
        let mut error_message = [0i8; STEAM_ERR_MESSAGE_SIZE];
        let code = unsafe { (api.init_flat)(error_message.as_mut_ptr() as *mut c_char) };
        if code != 0 {
            let text = unsafe { std::ffi::CStr::from_ptr(error_message.as_ptr() as *const c_char) }
                .to_string_lossy()
                .into_owned();
            let trimmed = text.trim();
            return Err(if trimmed.is_empty() {
                format!("SteamAPI_InitFlat failed with code {code}; is Steam running?")
            } else {
                trimmed.to_string()
            });
        }
        unsafe { (api.manual_dispatch_init)() };
        let user = unsafe { (api.interface_user)() };
        let matchmaking = unsafe { (api.interface_matchmaking)() };
        let messages = unsafe { (api.interface_messages)() };
        let friends = unsafe { (api.interface_friends)() };
        let networking_utils = unsafe { (api.interface_networking_utils)() };
        let pipe = unsafe { (api.get_h_steam_pipe)() };
        let my_steam_id = if user.is_null() {
            0
        } else {
            unsafe { (api.user_get_steam_id)(user) }
        };
        if my_steam_id == 0 || matchmaking.is_null() || messages.is_null() {
            unsafe { (api.shutdown)() };
            return Err("Steam is running but did not expose a logged-on user and its lobby interfaces".to_string());
        }
        let steam = Self {
            _library: library,
            api,
            matchmaking,
            messages,
            user,
            friends,
            networking_utils,
            pipe,
            steam_id: my_steam_id,
        };
        steam.configure_transport();
        Ok(steam)
    }

    pub fn my_steam_id(&self) -> u64 {
        self.steam_id
    }

    pub fn pipe(&self) -> i32 {
        self.pipe
    }

    pub fn thread_memory_releaser(&self) -> ThreadMemoryReleaser {
        ThreadMemoryReleaser(self.api.release_thread_memory)
    }

    /// Raises the reliable-send ceiling, exactly what the Java mod's `(char*)11` cast achieves:
    /// the second argument is the `ESteamNetworkingConfigValue` enum, and 11 is `SendRateMax`.
    pub fn configure_transport(&self) {
        if self.networking_utils.is_null() {
            return;
        }
        unsafe {
            (self.api.set_global_int32)(
                self.networking_utils,
                CONFIG_SEND_RATE_MAX,
                SEND_RATE_CEILING_BYTES_PER_SEC,
            );
            (self.api.init_relay_access)(self.networking_utils);
        }
    }

    pub fn accept_session(&self, steam_id: u64) {
        let identity = SteamNetworkingIdentity::from_steam_id(steam_id);
        unsafe { (self.api.messages_accept_session)(self.messages, &identity) };
    }

    pub fn close_session(&self, steam_id: u64) {
        let identity = SteamNetworkingIdentity::from_steam_id(steam_id);
        unsafe { (self.api.messages_close_session)(self.messages, &identity) };
    }

    /// `(state, pending_reliable, pending_unreliable, sent_unacked)` for the reliability pacer.
    pub fn session_status(&self, steam_id: u64) -> (i32, i32, i32, i32) {
        let identity = SteamNetworkingIdentity::from_steam_id(steam_id);
        let mut info = ConnectionInfo::default();
        let mut status = RealTimeStatus::default();
        unsafe { (self.api.messages_session_info)(self.messages, &identity, &mut info, &mut status) };
        (
            info.state,
            status.pending_reliable,
            status.pending_unreliable,
            status.sent_unacked,
        )
    }

    pub fn send(&self, steam_id: u64, bytes: &[u8], flags: i32) -> Result<(), SendFailure> {
        let identity = SteamNetworkingIdentity::from_steam_id(steam_id);
        let result = unsafe {
            (self.api.messages_send)(
                self.messages,
                &identity,
                bytes.as_ptr() as *const c_void,
                bytes.len() as u32,
                flags,
                CHANNEL,
            )
        };
        if result == RESULT_OK {
            Ok(())
        } else {
            Err(SendFailure {
                result,
                retryable: result_is_retryable(result),
            })
        }
    }

    pub fn receive(&self, max: i32) -> Vec<(u64, Vec<u8>)> {
        let mut out = Vec::new();
        let mut slots: Vec<*mut SteamNetworkingMessage> = vec![std::ptr::null_mut(); max.max(0) as usize];
        let count = unsafe { (self.api.messages_receive)(self.messages, CHANNEL, slots.as_mut_ptr(), max) };
        for index in 0..count.max(0) as usize {
            let message = match slots.get(index) {
                Some(message) if !message.is_null() => *message,
                _ => continue,
            };
            let (from, bytes) = unsafe {
                let view = &*message;
                let size = (view.cb_size.max(0) as usize).min(MAX_ACCEPTED_PACKET_SIZE);
                let slice = std::slice::from_raw_parts(view.p_data as *const u8, size);
                (view.identity_peer.steam_id(), slice.to_vec())
            };
            unsafe { (self.api.release_message)(message) };
            out.push((from, bytes));
        }
        out
    }

    /// Runs one Steam frame and drains callbacks and messages; the runtime keeps them separate
    /// so outbound frames go out before inbound ones are dispatched, exactly like the mod.
    pub fn pump(&mut self) -> Vec<Event> {
        let mut events = self.pump_callbacks();
        events.extend(self.poll_messages());
        events
    }

    pub fn pump_callbacks(&mut self) -> Vec<Event> {
        let mut events = Vec::new();
        unsafe { (self.api.manual_run_frame)(self.pipe) };
        loop {
            let mut callback = CallbackMsg::default();
            if !(unsafe { (self.api.manual_get_next_callback)(self.pipe, &mut callback) }) {
                break;
            }
            if let Some(event) = unsafe { self.translate(&callback) } {
                events.push(event);
            }
            unsafe { (self.api.manual_free_last_callback)(self.pipe) };
        }
        events
    }

    /// Up to 32 Steam messages, one E4ST frame each.
    pub fn poll_messages(&self) -> Vec<Event> {
        self.receive(32)
            .into_iter()
            .map(|(from, bytes)| Event::Message { from, bytes })
            .collect()
    }

    unsafe fn translate(&self, callback: &CallbackMsg) -> Option<Event> {
        Some(match callback.i_callback {
            CALLBACK_LOBBY_CREATED => {
                let raw = read::<RawLobbyCreated>(callback)?;
                Event::LobbyCreated { result: raw.result, lobby: raw.lobby }
            }
            CALLBACK_LOBBY_ENTER => {
                let raw = read::<RawLobbyEnter>(callback)?;
                Event::LobbyEntered { lobby: raw.lobby, enter_response: raw.enter_response }
            }
            CALLBACK_LOBBY_DATA_UPDATE => {
                let raw = read::<RawLobbyDataUpdate>(callback)?;
                Event::LobbyDataUpdated { lobby: raw.lobby, member: raw.member }
            }
            CALLBACK_LOBBY_CHAT_UPDATE => {
                let raw = read::<RawLobbyChatUpdate>(callback)?;
                Event::LobbyMemberChanged { lobby: raw.lobby, member: raw.user_changed, flags: raw.flags }
            }
            CALLBACK_GAME_RICH_PRESENCE_JOIN_REQUESTED => {
                let raw = read::<RawRichPresenceJoin>(callback)?;
                Event::RichPresenceJoin { friend: raw.friend, connect: c_string(&raw.connect) }
            }
            CALLBACK_GAME_LOBBY_JOIN_REQUESTED => {
                let raw = read::<RawLobbyJoinRequested>(callback)?;
                Event::LobbyJoinRequested { lobby: raw.lobby, friend: raw.friend }
            }
            CALLBACK_STEAM_API_CALL_COMPLETED => {
                let completed = read::<RawApiCallCompleted>(callback)?;
                self.resolve_api_call(completed)?
            }
            _ => return None,
        })
    }

    /// Async lobby calls also arrive as `SteamAPICallCompleted_t`; pull the typed result once.
    fn resolve_api_call(&self, completed: RawApiCallCompleted) -> Option<Event> {
        let expected = match completed.i_callback {
            CALLBACK_LOBBY_CREATED => std::mem::size_of::<RawLobbyCreated>(),
            CALLBACK_LOBBY_ENTER => std::mem::size_of::<RawLobbyEnter>(),
            _ => return None,
        };
        let mut buffer = vec![0u8; expected.max(32)];
        let mut failed = false;
        let delivered = unsafe {
            (self.api.manual_get_api_call_result)(
                self.pipe,
                completed.h_async_call,
                buffer.as_mut_ptr() as *mut c_void,
                buffer.len() as i32,
                completed.i_callback,
                &mut failed,
            )
        };
        if !delivered || failed {
            return None;
        }
        unsafe { translate_raw(completed.i_callback, buffer.as_ptr()) }
    }

    pub fn create_lobby(&self, lobby_type: i32, capacity: i32) -> u64 {
        unsafe { (self.api.create_lobby)(self.matchmaking, lobby_type, capacity) }
    }

    pub fn join_lobby(&self, lobby: u64) -> u64 {
        unsafe { (self.api.join_lobby)(self.matchmaking, lobby) }
    }

    pub fn leave_lobby(&self, lobby: u64) -> bool {
        unsafe { (self.api.leave_lobby)(self.matchmaking, lobby) }
    }

    pub fn request_lobby_data(&self, lobby: u64) -> bool {
        unsafe { (self.api.request_lobby_data)(self.matchmaking, lobby) }
    }

    pub fn set_lobby_data(&self, lobby: u64, key: &str, value: &str) -> bool {
        let key = CString::new(key).expect("lobby key without NUL");
        let value = CString::new(value).expect("lobby value without NUL");
        unsafe { (self.api.set_lobby_data)(self.matchmaking, lobby, key.as_ptr(), value.as_ptr()) }
    }

    /// The SDK hands back a thread-local buffer, so copy immediately and never keep the pointer.
    pub fn lobby_data(&self, lobby: u64, key: &str) -> String {
        let key = CString::new(key).expect("lobby key without NUL");
        let pointer = unsafe { (self.api.get_lobby_data)(self.matchmaking, lobby, key.as_ptr()) };
        if pointer.is_null() {
            return String::new();
        }
        unsafe { std::ffi::CStr::from_ptr(pointer) }.to_string_lossy().into_owned()
    }

    pub fn set_lobby_joinable(&self, lobby: u64, joinable: bool) -> bool {
        unsafe { (self.api.set_lobby_joinable)(self.matchmaking, lobby, joinable) }
    }

    pub fn set_lobby_type(&self, lobby: u64, lobby_type: i32) -> bool {
        unsafe { (self.api.set_lobby_type)(self.matchmaking, lobby, lobby_type) }
    }

    pub fn lobby_owner(&self, lobby: u64) -> u64 {
        unsafe { (self.api.get_lobby_owner)(self.matchmaking, lobby) }
    }

    pub fn lobby_member_count(&self, lobby: u64) -> i32 {
        unsafe { (self.api.count_lobby_members)(self.matchmaking, lobby) }
    }

    pub fn lobby_member(&self, lobby: u64, index: i32) -> u64 {
        unsafe { (self.api.get_lobby_member)(self.matchmaking, lobby, index) }
    }

    pub fn invite_user_to_lobby(&self, lobby: u64, steam_id: u64) -> bool {
        unsafe { (self.api.invite_user_to_lobby)(self.matchmaking, lobby, steam_id) }
    }

    pub fn relationship(&self, steam_id: u64) -> i32 {
        if self.friends.is_null() {
            return RELATIONSHIP_NONE;
        }
        unsafe { (self.api.get_friend_relationship)(self.friends, steam_id) }
    }

    pub fn persona_name(&self, steam_id: u64) -> String {
        if self.friends.is_null() {
            return String::new();
        }
        let pointer = unsafe { (self.api.get_friend_persona_name)(self.friends, steam_id) };
        if pointer.is_null() {
            return String::new();
        }
        unsafe { std::ffi::CStr::from_ptr(pointer) }.to_string_lossy().into_owned()
    }

    pub fn set_rich_presence(&self, key: &str, value: &str) -> bool {
        if self.friends.is_null() {
            return false;
        }
        let key = CString::new(key).expect("rich presence key without NUL");
        let value = CString::new(value).expect("rich presence value without NUL");
        unsafe { (self.api.set_rich_presence)(self.friends, key.as_ptr(), value.as_ptr()) }
    }

    pub fn clear_rich_presence(&self) {
        if !self.friends.is_null() {
            unsafe { (self.api.clear_rich_presence)(self.friends) };
        }
    }

    pub fn show_invite_dialog(&self, lobby: u64) -> bool {
        if self.friends.is_null() {
            return false;
        }
        unsafe { (self.api.activate_invite_dialog)(self.friends, lobby) }
    }

    /// Auth ticket for a dedicated server; `None` when Steam refuses to issue one.
    pub fn auth_session_ticket(&self, max_size: usize) -> Option<(u32, Vec<u8>)> {
        let mut buffer = vec![0u8; max_size];
        let mut size: u32 = 0;
        let handle = unsafe {
            (self.api.user_get_auth_ticket)(
                self.user,
                buffer.as_mut_ptr() as *mut c_void,
                max_size as i32,
                &mut size,
                std::ptr::null(),
            )
        };
        if handle == 0 || size == 0 || size as usize > max_size {
            return None;
        }
        buffer.truncate(size as usize);
        Some((handle, buffer))
    }
}

impl Drop for Steam {
    fn drop(&mut self) {
        unsafe { (self.api.shutdown)() };
    }
}

unsafe fn read<T: Copy + Default>(callback: &CallbackMsg) -> Option<T> {
    if callback.pub_param.is_null() || callback.cub_param < std::mem::size_of::<T>() as i32 {
        return None;
    }
    Some(std::ptr::read_unaligned(callback.pub_param as *const T))
}

unsafe fn translate_raw(i_callback: i32, pointer: *const u8) -> Option<Event> {
    match i_callback {
        CALLBACK_LOBBY_CREATED => {
            let raw: RawLobbyCreated = std::ptr::read_unaligned(pointer as *const RawLobbyCreated);
            Some(Event::LobbyCreated { result: raw.result, lobby: raw.lobby })
        }
        CALLBACK_LOBBY_ENTER => {
            let raw: RawLobbyEnter = std::ptr::read_unaligned(pointer as *const RawLobbyEnter);
            Some(Event::LobbyEntered { lobby: raw.lobby, enter_response: raw.enter_response })
        }
        _ => None,
    }
}

fn c_string(bytes: &[u8]) -> String {
    let end = bytes.iter().position(|byte| *byte == 0).unwrap_or(bytes.len());
    String::from_utf8_lossy(&bytes[..end]).into_owned()
}

/// Writes the `480` app id where Valve's redist looks for it: the working directory first, then the
/// folder holding the executable. Without it `SteamAPI_InitFlat` has no app to join the client with.
pub fn ensure_app_id_file() -> Result<(), String> {
    let wanted = format!("{APP_ID}\n");
    let mut directories = vec![std::path::PathBuf::from(".")];
    if let Some(folder) = std::env::current_exe().ok().and_then(|exe| exe.parent().map(|p| p.to_path_buf())) {
        directories.push(folder);
    }
    let mut refused = Vec::new();
    for directory in directories {
        let path = directory.join("steam_appid.txt");
        if path.is_file() || std::fs::write(&path, &wanted).is_ok() {
            return Ok(());
        }
        refused.push(format!("{path:?}"));
    }
    Err(format!("could not write steam_appid.txt in {}", refused.join(" or ")))
}

pub fn steam_dll_name() -> &'static str {
    if cfg!(target_os = "windows") {
        "steam_api64.dll"
    } else if cfg!(target_os = "macos") {
        "libsteam_api.dylib"
    } else {
        "libsteam_api.so"
    }
}

/// Finds the redist library. Players supply their own copy — Valve's license is not ours to
/// redistribute — so a game install beside the running Steam client counts as one.
pub fn locate_steam_dll() -> Result<PathBuf, String> {
    if let Some(path) = std::env::var_os("E4STEAM_STEAM_API") {
        let path = PathBuf::from(path);
        return if path.is_file() {
            Ok(path)
        } else {
            Err(format!("{:?} does not exist", path))
        };
    }
    let name = steam_dll_name();
    let bare = PathBuf::from(name);
    if bare.is_file() {
        return Ok(bare);
    }
    let mut candidates: Vec<PathBuf> = Vec::new();
    if let Ok(exe) = std::env::current_exe() {
        if let Some(directory) = exe.parent() {
            candidates.push(directory.join(name));
            candidates.push(directory.join("steam").join(name));
        }
    }
    candidates.extend(copies_beside_installed_games(name));
    for candidate in &candidates {
        if candidate.is_file() {
            return Ok(candidate.clone());
        }
    }
    // Last resort: hand the bare name to the OS loader, which also searches the Steam install.
    Ok(bare)
}

/// Every `<game folder>/<name>` under the local Steam library, plus a loose copy in its root.
fn copies_beside_installed_games(name: &str) -> Vec<PathBuf> {
    let mut found = Vec::new();
    for root in steam_library_roots() {
        let direct = root.join(name);
        if direct.is_file() {
            found.push(direct);
        }
        let games = root.join("steamapps").join("common");
        let Ok(entries) = std::fs::read_dir(&games) else { continue };
        let mut folders: Vec<PathBuf> = entries.flatten().map(|entry| entry.path()).collect();
        folders.sort();
        found.extend(
            folders
                .iter()
                .map(|folder| folder.join(name))
                .filter(|copy| copy.is_file()),
        );
    }
    found
}

#[cfg(windows)]
fn steam_library_roots() -> Vec<PathBuf> {
    let mut roots = Vec::new();
    for key in ["Software\\Valve\\Steam", "Software\\WOW6432Node\\Valve\\Steam"] {
        let Some(text) = registry_string(key, "SteamPath") else { continue };
        let root = PathBuf::from(text);
        if root.is_dir() && !roots.contains(&root) {
            roots.push(root);
        }
    }
    roots
}

#[cfg(not(windows))]
fn steam_library_roots() -> Vec<PathBuf> {
    let Some(home) = std::env::var_os("HOME").map(PathBuf::from) else {
        return Vec::new();
    };
    if cfg!(target_os = "macos") {
        return vec![home.join("Library/Application Support/Steam")];
    }
    vec![home.join(".local/share/Steam"), home.join(".steam/root")]
}

/// `REG_SZ` under `HKCU\Software\Valve\Steam`, the value the Steam client itself writes.
///
/// `advapi32.dll` is opened at run time rather than linked, the same way the Steam library is: an
/// extra import library would tie every build of the game to it.
#[cfg(windows)]
fn registry_string(key: &str, value: &str) -> Option<String> {
    use std::ffi::c_void;
    use std::os::windows::ffi::OsStrExt;

    type RegGetValue = unsafe extern "system" fn(
        key: *mut c_void,
        sub_key: *const u16,
        value: *const u16,
        flags: u32,
        value_kind: *mut u32,
        data: *mut c_void,
        data_length: *mut u32,
    ) -> i32;

    const HKEY_CURRENT_USER: usize = 0x8000_0001;
    const RRF_RT_REG_SZ: u32 = 0x0000_0002;

    let library = crate::dll::Library::open(std::path::Path::new("advapi32.dll")).ok()?;
    let address = unsafe { library.address("RegGetValueW") }?;
    let read: RegGetValue = unsafe { std::mem::transmute_copy(&address) };

    let wide = |text: &str| -> Vec<u16> {
        std::ffi::OsStr::new(text)
            .encode_wide()
            .chain(std::iter::once(0))
            .collect()
    };
    let mut buffer = [0u16; 512];
    let mut length = (buffer.len() * 2) as u32;
    let outcome = unsafe {
        read(
            HKEY_CURRENT_USER as *mut c_void,
            wide(key).as_ptr(),
            wide(value).as_ptr(),
            RRF_RT_REG_SZ,
            core::ptr::null_mut(),
            buffer.as_mut_ptr() as *mut c_void,
            &mut length,
        )
    };
    if outcome != 0 {
        return None;
    }
    let units = (length as usize / 2).min(buffer.len());
    let text = String::from_utf16_lossy(&buffer[..units]);
    let trimmed = text.trim_end_matches('\0');
    if trimmed.is_empty() {
        None
    } else {
        Some(trimmed.to_owned())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn identity_roundtrips_a_steam_id() {
        for id in [1u64, 76561198000000000, u64::MAX] {
            assert_eq!(SteamNetworkingIdentity::from_steam_id(id).steam_id(), id);
        }
        let mut bogus = SteamNetworkingIdentity::from_steam_id(5);
        bogus.e_type = IDENTITY_INVALID;
        assert_eq!(bogus.steam_id(), 0);
        assert_eq!(SteamNetworkingIdentity::default().steam_id(), 0);
    }

    #[test]
    fn identity_keeps_room_for_a_generic_string() {
        let identity = SteamNetworkingIdentity::from_steam_id(7);
        assert_eq!(identity.value.len(), 128);
        assert_eq!(identity.cb_size, 8);
    }

    #[test]
    fn retryable_sets_match_the_mod() {
        assert!(result_is_retryable(RESULT_LIMIT_EXCEEDED));
        assert!(result_is_retryable(RESULT_BUSY));
        assert!(result_is_retryable(RESULT_NO_CONNECTION));
        assert!(result_is_retryable(RESULT_SERVICE_UNAVAILABLE));
        assert!(!result_is_retryable(RESULT_OK));
        assert!(!result_is_retryable(RESULT_INVALID_PARAM));
        assert!(!result_is_retryable(RESULT_ACCESS_DENIED));
        assert!(!result_is_lobby_retryable(RESULT_LIMIT_EXCEEDED));
        assert!(result_is_lobby_retryable(RESULT_TIMEOUT));
    }

    #[test]
    fn send_flags_are_the_mod_combinations() {
        assert_eq!(SEND_RELIABLE_NO_NAGLE, 9);
        assert_eq!(SEND_UNRELIABLE_NO_DELAY, 5);
        assert_eq!(SEND_RELIABLE | SEND_NO_NAGLE | SEND_AUTO_RESTART_BROKEN_SESSION, 41);
    }

    #[test]
    fn short_or_null_callback_payloads_are_rejected() {
        let mut buffer = [0u8; 32];
        unsafe {
            let short = CallbackMsg {
                h_steam_user: 1,
                i_callback: CALLBACK_LOBBY_CREATED,
                pub_param: buffer.as_mut_ptr(),
                cub_param: 8,
            };
            assert!(read::<RawLobbyCreated>(&short).is_none());
            buffer[..4].copy_from_slice(&RESULT_TIMEOUT.to_le_bytes());
            buffer[8..16].copy_from_slice(&1234u64.to_le_bytes());
            let full = CallbackMsg {
                h_steam_user: 1,
                i_callback: CALLBACK_LOBBY_CREATED,
                pub_param: buffer.as_mut_ptr(),
                cub_param: 16,
            };
            let parsed: RawLobbyCreated = read(&full).expect("payload fits");
            assert_eq!(parsed.result, RESULT_TIMEOUT);
            assert_eq!(parsed.lobby, 1234);
        }
    }

    #[test]
    fn connect_strings_are_nul_terminated() {
        let mut bytes = [0u8; 256];
        bytes[..12].copy_from_slice(b"e4steam-lobb");
        assert_eq!(c_string(&bytes), "e4steam-lobb");
        assert_eq!(c_string(&[0u8; 4]), "");
    }

    #[test]
    fn app_id_file_is_idempotent() {
        let directory = std::env::temp_dir().join(format!("e4steam-appid-{}", std::process::id()));
        std::fs::create_dir_all(&directory).expect("temp dir");
        let previous = std::env::current_dir().expect("cwd");
        std::env::set_current_dir(&directory).expect("chdir");
        let first = ensure_app_id_file();
        let written = std::fs::read_to_string("steam_appid.txt").unwrap_or_default();
        let second = ensure_app_id_file();
        std::env::set_current_dir(previous).expect("restore cwd");
        let _ = std::fs::remove_dir_all(&directory);
        assert!(first.is_ok() && second.is_ok());
        assert_eq!(written.trim(), "480");
    }
}
