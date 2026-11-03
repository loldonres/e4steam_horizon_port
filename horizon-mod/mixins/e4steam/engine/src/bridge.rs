use crate::frame::DATA_CHUNK_SIZE;
use crate::chan::{bounded, Receiver, Sender};
use std::io::{Read, Write};
use std::net::{Shutdown, TcpStream};
use std::sync::atomic::{AtomicBool, AtomicI32, AtomicI64, Ordering};
use std::sync::{Arc, OnceLock};
use std::time::{Duration, Instant};

/// How long a local TCP reader spins waiting for outbound Steam capacity before dropping the bridge.
pub const OUTBOUND_BACKPRESSURE_TIMEOUT_MS: i64 = 30_000;
const OUTBOUND_BACKPRESSURE_RETRY: Duration = Duration::from_millis(10);
/// Shared by FIN from either direction; after this the bridge is torn down hard.
pub const GRACEFUL_CLOSE_TIMEOUT_MS: i64 = 2000;
pub const MAX_QUEUED_INBOUND_CHUNKS: i32 = 256;
const INBOUND_CHANNEL_CAPACITY: usize = MAX_QUEUED_INBOUND_CHUNKS as usize + 1;

/// Monotonic millisecond clock: deadlines must not move when the wall clock is adjusted.
pub fn now_ms() -> i64 {
    static START: OnceLock<Instant> = OnceLock::new();
    let start = START.get_or_init(Instant::now);
    start.elapsed().as_millis() as i64
}

/// The Steam half of a bridge: the runtime owns frame encoding, queues and retries.
pub trait RemoteSink: Send + Sync {
    fn send_data(&self, steam_id: u64, connection_id: u32, payload: &[u8]) -> bool;
    fn send_fin(&self, steam_id: u64, connection_id: u32) -> bool;
    fn send_reset(&self, steam_id: u64, connection_id: u32) -> bool;
    fn forget_bridge(&self, steam_id: u64, connection_id: u32);
}

enum Inbound {
    Data(Vec<u8>),
    Fin,
    Abort,
}

pub struct Bridge {
    pub steam_id: u64,
    pub connection_id: u32,
    pub host_side: bool,
    socket: Arc<TcpStream>,
    sink: Arc<dyn RemoteSink>,
    inbound_tx: Sender<Inbound>,
    inbound_rx: Receiver<Inbound>,
    inbound_slots: AtomicI32,
    started: AtomicBool,
    closed: AtomicBool,
    outbound_fin_queued: AtomicBool,
    outbound_fin_submitted: AtomicBool,
    inbound_fin_queued: AtomicBool,
    inbound_finished: AtomicBool,
    peer_ready: AtomicBool,
    graceful_deadline_ms: AtomicI64,
    outbound_not_before_ms: AtomicI64,
    outbound_data_fallback_ms: AtomicI64,
}

impl Bridge {
    pub fn new(
        steam_id: u64,
        connection_id: u32,
        socket: TcpStream,
        host_side: bool,
        sink: Arc<dyn RemoteSink>,
    ) -> Arc<Self> {
        let _ = socket.set_nodelay(true);
        let (inbound_tx, inbound_rx) = bounded(INBOUND_CHANNEL_CAPACITY);
        Arc::new(Self {
            steam_id,
            connection_id,
            host_side,
            socket: Arc::new(socket),
            sink,
            inbound_tx,
            inbound_rx,
            inbound_slots: AtomicI32::new(MAX_QUEUED_INBOUND_CHUNKS),
            started: AtomicBool::new(false),
            closed: AtomicBool::new(false),
            outbound_fin_queued: AtomicBool::new(false),
            outbound_fin_submitted: AtomicBool::new(false),
            inbound_fin_queued: AtomicBool::new(false),
            inbound_finished: AtomicBool::new(false),
            peer_ready: AtomicBool::new(false),
            graceful_deadline_ms: AtomicI64::new(i64::MAX),
            outbound_not_before_ms: AtomicI64::new(0),
            outbound_data_fallback_ms: AtomicI64::new(0),
        })
    }

    pub fn local_port(&self) -> u16 {
        self.socket.local_addr().map(|addr| addr.port()).unwrap_or(0)
    }

    pub fn is_closed(&self) -> bool {
        self.closed.load(Ordering::Acquire)
    }

    pub fn start(self: &Arc<Self>) {
        if self.started.swap(true, Ordering::SeqCst) || self.is_closed() {
            return;
        }
        let write_half = self.socket.try_clone().expect("cloning a loopback socket");
        let read_half = self.socket.try_clone().expect("cloning a loopback socket");
        std::thread::spawn({
            let bridge = Arc::clone(self);
            move || bridge.write_loop(write_half)
        });
        std::thread::spawn({
            let bridge = Arc::clone(self);
            move || bridge.read_loop(read_half)
        });
    }

    /// Frames may only flow once the peer answered OPEN_ACK/BRIDGE_READY, or the fallback fired.
    pub fn is_outbound_data_ready(&self, now: i64) -> bool {
        let fallback = self.outbound_data_fallback_ms.load(Ordering::Acquire);
        fallback == 0 || self.peer_ready.load(Ordering::Acquire) || now >= fallback
    }

    pub fn wait_peer_ready_until(&self, fallback_ms: i64) {
        self.outbound_data_fallback_ms.fetch_max(fallback_ms, Ordering::AcqRel);
    }

    pub fn mark_peer_ready(&self) {
        self.peer_ready.store(true, Ordering::Release);
    }

    pub fn delay_outbound_until(&self, deadline_ms: i64) {
        self.outbound_not_before_ms.fetch_max(deadline_ms, Ordering::AcqRel);
    }

    pub fn is_outbound_ready(&self, now: i64) -> bool {
        now >= self.outbound_not_before_ms.load(Ordering::Acquire)
    }

    pub fn accept_steam_data(&self, payload: Vec<u8>) -> bool {
        if self.is_closed() {
            return false;
        }
        if self.inbound_fin_queued.load(Ordering::Acquire) {
            self.close_for_slow_or_invalid_peer();
            return false;
        }
        if !self.take_inbound_slot() {
            self.close_for_slow_or_invalid_peer();
            return false;
        }
        if self.inbound_tx.try_send(Inbound::Data(payload)).is_err() {
            self.release_inbound_slot();
            self.close_for_slow_or_invalid_peer();
            return false;
        }
        true
    }

    pub fn accept_remote_fin(&self) {
        if self.is_closed() || self.inbound_fin_queued.swap(true, Ordering::SeqCst) {
            return;
        }
        self.begin_graceful_close();
        if self.inbound_tx.try_send(Inbound::Fin).is_err() {
            self.close(true);
        }
    }

    pub fn reset_from_remote(&self) {
        self.close(false);
    }

    /// Called by the runtime once a queued FIN actually reached Steam.
    pub fn mark_fin_submitted(&self) {
        if self.outbound_fin_queued.load(Ordering::Acquire) {
            self.outbound_fin_submitted.store(true, Ordering::Release);
            self.close_if_fully_finished();
        }
    }

    /// A RESET that really reached Steam ends the bridge: no FIN exchange can follow it.
    pub fn mark_reset_submitted(&self) {
        self.sink.forget_bridge(self.steam_id, self.connection_id);
    }

    pub fn close_if_graceful_close_timed_out(&self, now: i64) {
        let deadline = self.graceful_deadline_ms.load(Ordering::Acquire);
        if !self.is_closed() && deadline != i64::MAX && now >= deadline {
            self.close(true);
        }
    }

    pub fn close(&self, notify_remote: bool) {
        if self.closed.swap(true, Ordering::SeqCst) {
            return;
        }
        let _ = self.socket.shutdown(Shutdown::Both);
        while self.inbound_rx.try_recv().is_ok() {}
        let _ = self.inbound_tx.try_send(Inbound::Abort);
        if !notify_remote || !self.sink.send_reset(self.steam_id, self.connection_id) {
            self.sink.forget_bridge(self.steam_id, self.connection_id);
        }
    }

    fn read_loop(&self, mut socket: TcpStream) {
        let mut buffer = vec![0u8; DATA_CHUNK_SIZE];
        loop {
            if self.is_closed() {
                return;
            }
            match socket.read(&mut buffer) {
                Ok(0) => {
                    self.queue_local_fin();
                    return;
                }
                Ok(read) => self.send_data_with_backpressure(&buffer[..read]),
                Err(ref error) if error.kind() == std::io::ErrorKind::Interrupted => continue,
                Err(_) => {
                    if !self.is_closed() {
                        self.close(true);
                    }
                    return;
                }
            }
        }
    }

    fn queue_local_fin(&self) {
        if self.outbound_fin_queued.swap(true, Ordering::SeqCst) {
            return;
        }
        self.begin_graceful_close();
        if !self.sink.send_fin(self.steam_id, self.connection_id) {
            self.close(true);
        }
    }

    fn send_data_with_backpressure(&self, payload: &[u8]) {
        let deadline = now_ms() + OUTBOUND_BACKPRESSURE_TIMEOUT_MS;
        loop {
            if self.is_closed() {
                return;
            }
            if self
                .sink
                .send_data(self.steam_id, self.connection_id, payload)
            {
                return;
            }
            if now_ms() >= deadline {
                self.close(true);
                return;
            }
            std::thread::sleep(OUTBOUND_BACKPRESSURE_RETRY);
        }
    }

    fn write_loop(&self, mut socket: TcpStream) {
        loop {
            match self.inbound_rx.recv() {
                Err(_) | Ok(Inbound::Abort) => return,
                Ok(Inbound::Data(payload)) => {
                    self.release_inbound_slot();
                    if socket.write_all(&payload).and_then(|_| socket.flush()).is_err() {
                        if !self.is_closed() {
                            self.close(true);
                        }
                        return;
                    }
                }
                Ok(Inbound::Fin) => {
                    let _ = socket.flush();
                    let _ = socket.shutdown(Shutdown::Write);
                    self.inbound_finished.store(true, Ordering::Release);
                    self.close_if_fully_finished();
                    return;
                }
            }
        }
    }

    fn close_if_fully_finished(&self) {
        if self.outbound_fin_submitted.load(Ordering::Acquire)
            && self.inbound_finished.load(Ordering::Acquire)
        {
            self.close(false);
        }
    }

    fn close_for_slow_or_invalid_peer(&self) {
        if !self.is_closed() {
            self.close(true);
        }
    }

    fn begin_graceful_close(&self) {
        let deadline = now_ms() + GRACEFUL_CLOSE_TIMEOUT_MS;
        self.graceful_deadline_ms.fetch_min(deadline, Ordering::AcqRel);
    }

    fn take_inbound_slot(&self) -> bool {
        let mut current = self.inbound_slots.load(Ordering::Acquire);
        loop {
            if current <= 0 {
                return false;
            }
            match self.inbound_slots.compare_exchange_weak(
                current,
                current - 1,
                Ordering::AcqRel,
                Ordering::Acquire,
            ) {
                Ok(_) => return true,
                Err(next) => current = next,
            }
        }
    }

    fn release_inbound_slot(&self) {
        self.inbound_slots.fetch_add(1, Ordering::Release);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::TcpListener;
    use std::sync::Mutex;

    /// Loopback pair: the bridge owns one end, the test drives the other.
    fn pair(steam_id: u64, connection_id: u32) -> (TcpStream, Arc<Bridge>, Arc<Mutex<Vec<String>>>) {
        let listener = TcpListener::bind("127.0.0.1:0").expect("bind");
        let address = listener.local_addr().expect("address");
        let client = TcpStream::connect(address).expect("connect");
        let (server, _) = listener.accept().expect("accept");
        drop(listener);
        let sink = Arc::new(Mutex::new(Vec::new()));
        let bridge = Bridge::new(
            steam_id,
            connection_id,
            server,
            true,
            Arc::clone(&sink) as Arc<dyn RemoteSink>,
        );
        (client, bridge, sink)
    }

    impl RemoteSink for Mutex<Vec<String>> {
        fn send_data(&self, steam_id: u64, connection_id: u32, payload: &[u8]) -> bool {
            self.lock().unwrap().push(format!(
                "DATA {steam_id}/{connection_id} {}",
                String::from_utf8_lossy(payload)
            ));
            true
        }
        fn send_fin(&self, steam_id: u64, connection_id: u32) -> bool {
            self.lock().unwrap().push(format!("FIN {steam_id}/{connection_id}"));
            true
        }
        fn send_reset(&self, steam_id: u64, connection_id: u32) -> bool {
            self.lock().unwrap().push(format!("RESET {steam_id}/{connection_id}"));
            true
        }
        fn forget_bridge(&self, steam_id: u64, connection_id: u32) {
            self.lock()
                .unwrap()
                .push(format!("FORGET {steam_id}/{connection_id}"));
        }
    }

    fn events(sink: &Arc<Mutex<Vec<String>>>) -> Vec<String> {
        sink.lock().unwrap().clone()
    }

    fn wait_until(what: &str, mut predicate: impl FnMut() -> bool) {
        for _ in 0..500 {
            if predicate() {
                return;
            }
            std::thread::sleep(Duration::from_millis(10));
        }
        panic!("condition never reached: {what}");
    }

    #[test]
    fn local_bytes_become_data_frames() {
        let (mut client, bridge, sink) = pair(7, 42);
        bridge.start();
        client.write_all(b"hello").expect("write");
        wait_until("data frame", || {
            events(&sink).iter().any(|line| line.ends_with("hello"))
        });
        assert!(!bridge.outbound_fin_queued.load(Ordering::Acquire));
    }

    #[test]
    fn local_eof_queues_exactly_one_fin() {
        let (client, bridge, sink) = pair(7, 42);
        bridge.start();
        drop(client);
        wait_until("fin frame", || {
            events(&sink).iter().any(|line| line.starts_with("FIN"))
        });
        std::thread::sleep(Duration::from_millis(50));
        let fins = events(&sink)
            .iter()
            .filter(|line| line.starts_with("FIN"))
            .count();
        assert_eq!(fins, 1);
        assert!(bridge.graceful_deadline_ms.load(Ordering::Acquire) < i64::MAX);
    }

    #[test]
    fn remote_data_is_written_to_the_local_socket() {
        let (mut client, bridge, _sink) = pair(7, 42);
        bridge.start();
        assert!(bridge.accept_steam_data(b"push".to_vec()));
        let mut buffer = [0u8; 8];
        client
            .set_read_timeout(Some(Duration::from_secs(5)))
            .expect("timeout");
        let read = client.read(&mut buffer).expect("read");
        assert_eq!(&buffer[..read], b"push");
    }

    #[test]
    fn remote_fin_shuts_down_the_local_write_half() {
        let (mut client, bridge, sink) = pair(7, 42);
        client
            .set_read_timeout(Some(Duration::from_secs(5)))
            .expect("timeout");
        bridge.start();
        bridge.accept_remote_fin();
        assert_eq!(client.read(&mut [0u8; 4]).expect("eof"), 0);
        assert!(
            !bridge.is_closed(),
            "graceful close waits for our own FIN to reach Steam"
        );
        drop(client);
        wait_until("local fin queued", || {
            bridge.outbound_fin_queued.load(Ordering::Acquire)
        });
        bridge.mark_fin_submitted();
        wait_until("fully finished", || bridge.is_closed());
        assert!(!events(&sink).iter().any(|line| line.starts_with("RESET")));
    }

    #[test]
    fn slow_local_consumer_gets_reset() {
        let (_client, bridge, sink) = pair(7, 42);
        // Not started: nothing drains the inbound queue, mirroring a stalled Minecraft client.
        for _ in 0..MAX_QUEUED_INBOUND_CHUNKS {
            assert!(bridge.accept_steam_data(vec![1, 2, 3]));
        }
        assert!(!bridge.accept_steam_data(vec![4, 5, 6]), "257th chunk must be refused");
        assert!(bridge.is_closed());
        assert!(events(&sink).iter().any(|line| line.starts_with("RESET")));
    }

    #[test]
    fn data_after_fin_kills_the_bridge() {
        let (_client, bridge, sink) = pair(7, 42);
        bridge.accept_remote_fin();
        assert!(!bridge.accept_steam_data(b"late".to_vec()));
        assert!(bridge.is_closed());
        assert!(events(&sink).iter().any(|line| line.starts_with("RESET")));
    }

    #[test]
    fn remote_reset_does_not_echo_a_reset() {
        let (_client, bridge, sink) = pair(7, 42);
        bridge.reset_from_remote();
        assert!(bridge.is_closed());
        assert_eq!(events(&sink), vec!["FORGET 7/42".to_string()]);
    }

    #[test]
    fn graceful_close_is_forced_after_the_deadline() {
        let (_client, bridge, sink) = pair(7, 42);
        bridge.accept_remote_fin();
        assert!(!bridge.is_closed());
        bridge.close_if_graceful_close_timed_out(now_ms() + GRACEFUL_CLOSE_TIMEOUT_MS);
        assert!(bridge.is_closed());
        assert!(events(&sink).iter().any(|line| line.starts_with("RESET")));
    }

    #[test]
    fn data_is_gated_until_the_peer_is_ready() {
        let (_client, bridge, _sink) = pair(7, 42);
        assert!(bridge.is_outbound_data_ready(now_ms()), "no fallback = open gate");
        bridge.wait_peer_ready_until(now_ms() + 60_000);
        assert!(!bridge.is_outbound_data_ready(now_ms()));
        assert!(bridge.is_outbound_data_ready(now_ms() + 60_001));
        let (_client, bridge, _sink) = pair(7, 43);
        bridge.wait_peer_ready_until(now_ms() + 60_000);
        bridge.mark_peer_ready();
        assert!(bridge.is_outbound_data_ready(now_ms()));
    }

    #[test]
    fn deadlines_only_move_forward() {
        let (_client, bridge, _sink) = pair(7, 42);
        bridge.wait_peer_ready_until(5_000);
        bridge.wait_peer_ready_until(3_000);
        assert_eq!(bridge.outbound_data_fallback_ms.load(Ordering::Acquire), 5_000);
        bridge.delay_outbound_until(1_000);
        bridge.delay_outbound_until(2_000);
        assert_eq!(bridge.outbound_not_before_ms.load(Ordering::Acquire), 2_000);
        assert!(!bridge.is_outbound_ready(1_999));
        assert!(bridge.is_outbound_ready(2_000));
    }

    /// Frames are delivered straight to the peer bridge, and the submit markers mirror what
    /// `Runtime::drain_outbound` does once a frame really leaves.
    struct Link {
        local: Mutex<Option<Arc<Bridge>>>,
        peer: Mutex<Option<Arc<Bridge>>>,
    }

    impl Link {
        fn new() -> Arc<Self> {
            Arc::new(Self { local: Mutex::new(None), peer: Mutex::new(None) })
        }
        fn bridge(cell: &Mutex<Option<Arc<Bridge>>>) -> Option<Arc<Bridge>> {
            cell.lock().unwrap().clone()
        }
    }

    impl RemoteSink for Link {
        fn send_data(&self, _steam_id: u64, _connection_id: u32, payload: &[u8]) -> bool {
            match Self::bridge(&self.peer) {
                Some(peer) => peer.accept_steam_data(payload.to_vec()),
                None => false,
            }
        }
        fn send_fin(&self, _steam_id: u64, _connection_id: u32) -> bool {
            match (Self::bridge(&self.local), Self::bridge(&self.peer)) {
                (Some(local), Some(peer)) => {
                    peer.accept_remote_fin();
                    local.mark_fin_submitted();
                    true
                }
                _ => false,
            }
        }
        fn send_reset(&self, _steam_id: u64, _connection_id: u32) -> bool {
            match (Self::bridge(&self.local), Self::bridge(&self.peer)) {
                (Some(local), Some(peer)) => {
                    peer.reset_from_remote();
                    local.mark_reset_submitted();
                    true
                }
                _ => false,
            }
        }
        fn forget_bridge(&self, _steam_id: u64, _connection_id: u32) {}
    }

    /// An app socket plus the bridge on its other end; the caller wires the links, then starts.
    fn bridged(steam_id: u64, connection_id: u32, link: Arc<Link>) -> (TcpStream, Arc<Bridge>) {
        let listener = TcpListener::bind("127.0.0.1:0").expect("bind");
        let address = listener.local_addr().expect("address");
        let app = TcpStream::connect(address).expect("connect");
        let (bridge_side, _) = listener.accept().expect("accept");
        drop(listener);
        app.set_read_timeout(Some(Duration::from_secs(10))).expect("timeout");
        let bridge =
            Bridge::new(steam_id, connection_id, bridge_side, true, link as Arc<dyn RemoteSink>);
        bridge.wait_peer_ready_until(now_ms() + 60_000);
        bridge.mark_peer_ready();
        (app, bridge)
    }

    #[test]
    fn a_whole_stream_survives_two_chained_bridges() {
        let left_link = Link::new();
        let right_link = Link::new();
        let (mut left_app, left) = bridged(9, 1, Arc::clone(&left_link));
        let (mut right_app, right) = bridged(9, 1, Arc::clone(&right_link));
        *left_link.local.lock().unwrap() = Some(Arc::clone(&left));
        *left_link.peer.lock().unwrap() = Some(Arc::clone(&right));
        *right_link.local.lock().unwrap() = Some(Arc::clone(&right));
        *right_link.peer.lock().unwrap() = Some(Arc::clone(&left));
        left.start();
        right.start();

        // larger than one 32 KiB chunk, so the frames really have to be reassembled in order
        let payload: Vec<u8> = (0..200_000u32).map(|index| (index % 251) as u8).collect();
        left_app.write_all(&payload).expect("write left");
        let mut received = Vec::with_capacity(payload.len());
        let mut window = [0u8; 8_192];
        while received.len() < payload.len() {
            let read = right_app.read(&mut window).expect("read right");
            assert_ne!(read, 0, "the stream ended early after {} bytes", received.len());
            received.extend_from_slice(&window[..read]);
        }
        assert_eq!(received, payload);

        right_app.write_all(b"ping from the guest").expect("write right");
        let mut answer = [0u8; 19];
        left_app.read_exact(&mut answer).expect("read back");
        assert_eq!(&answer, b"ping from the guest");

        drop(left_app);
        let mut tail = [0u8; 1];
        wait_until("eof at the peer", || {
            right_app.read(&mut tail).map(|read| read == 0).unwrap_or(false)
        });
        drop(right_app);
        wait_until("both bridges closed", || left.is_closed() && right.is_closed());
    }
}
