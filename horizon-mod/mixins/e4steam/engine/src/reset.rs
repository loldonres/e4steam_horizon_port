//! Retries for RESET frames that Steam refused temporarily.
//!
//! A RESET is the only frame a peer cannot recover from by itself: if it is lost, the other side
//! keeps a half-dead bridge until its own timeout fires. The mod therefore keeps a small,
//! jittered retry list for it, separate from the single held outbound slot.

use crate::bridge::Bridge;
use std::sync::Arc;

pub const CAPACITY: usize = 64;
pub const MAX_ATTEMPTS: i32 = 10;
pub const MAX_AGE_MS: i64 = 30_000;
pub const BASE_DELAY_MS: i64 = 25;
pub const MAX_DELAY_MS: i64 = 2_000;
pub const DRAIN_PER_TICK: usize = 8;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum State {
    Scheduled,
    AwaitingSend,
    Sent,
    StaleConnection,
    Exhausted,
    Permanent,
    Shutdown,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Offer {
    Accepted,
    Duplicate,
    Full,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Outcome {
    Success,
    Temporary,
    Permanent,
}

/// A RESET that is due for another attempt; `state` tells the caller whether to actually send it.
pub struct Due {
    pub steam_id: u64,
    pub connection_id: u32,
    pub bridge: Option<Arc<Bridge>>,
    pub bytes: Vec<u8>,
    pub attempts: i32,
    pub state: State,
}

struct Entry {
    steam_id: u64,
    connection_id: u32,
    bridge: Option<Arc<Bridge>>,
    bytes: Vec<u8>,
    created_ms: i64,
    attempts: i32,
    next_attempt_ms: i64,
    state: State,
}

impl Entry {
    fn is_same(&self, steam_id: u64, connection_id: u32, bridge: &Option<Arc<Bridge>>) -> bool {
        if self.steam_id != steam_id || self.connection_id != connection_id {
            return false;
        }
        match (&self.bridge, bridge) {
            (Some(held), Some(wanted)) => Arc::ptr_eq(held, wanted),
            (None, None) => true,
            _ => false,
        }
    }

    fn due(&self) -> Due {
        Due {
            steam_id: self.steam_id,
            connection_id: self.connection_id,
            bridge: self.bridge.clone(),
            bytes: self.bytes.clone(),
            attempts: self.attempts,
            state: self.state,
        }
    }
}

pub struct ResetRetries {
    entries: Vec<Entry>,
    jitter: bool,
}

impl Default for ResetRetries {
    fn default() -> Self {
        Self::new()
    }
}

impl ResetRetries {
    pub fn new() -> Self {
        Self { entries: Vec::new(), jitter: true }
    }

    /// Deterministic delays for tests.
    pub fn without_jitter(mut self) -> Self {
        self.jitter = false;
        self
    }

    pub fn len(&self) -> usize {
        self.entries.len()
    }

    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    pub fn offer(
        &mut self,
        steam_id: u64,
        connection_id: u32,
        bytes: Vec<u8>,
        bridge: Option<Arc<Bridge>>,
        now: i64,
    ) -> Offer {
        if self
            .entries
            .iter()
            .any(|entry| entry.is_same(steam_id, connection_id, &bridge))
        {
            return Offer::Duplicate;
        }
        if self.entries.len() >= CAPACITY {
            return Offer::Full;
        }
        self.entries.push(Entry {
            steam_id,
            connection_id,
            bridge,
            bytes,
            created_ms: now,
            attempts: 1,
            next_attempt_ms: now + self.retry_delay(1),
            state: State::Scheduled,
        });
        Offer::Accepted
    }

    /// The oldest entry that is ready, retiring the ones whose budget ran out on the way.
    pub fn next_due(&mut self, now: i64) -> Option<Due> {
        for index in 0..self.entries.len() {
            let expired = self.entries[index].attempts >= MAX_ATTEMPTS
                || now - self.entries[index].created_ms >= MAX_AGE_MS;
            if expired {
                let mut entry = self.entries.remove(index);
                entry.state = State::Exhausted;
                return Some(entry.due());
            }
            if self.entries[index].state == State::Scheduled && now >= self.entries[index].next_attempt_ms
            {
                self.entries[index].state = State::AwaitingSend;
                return Some(self.entries[index].due());
            }
        }
        None
    }

    pub fn complete(&mut self, due: &Due, outcome: Outcome, now: i64) -> State {
        let index = match self.find(due) {
            Some(index) => index,
            None => return due.state,
        };
        if self.entries[index].state != State::AwaitingSend {
            return self.entries[index].state;
        }
        match outcome {
            Outcome::Success => {
                self.entries.remove(index);
                State::Sent
            }
            Outcome::Temporary => {
                let attempts = self.entries[index].attempts + 1;
                let age = now - self.entries[index].created_ms;
                let delay = self.retry_delay(attempts);
                if attempts < MAX_ATTEMPTS && age < MAX_AGE_MS {
                    self.entries[index].attempts = attempts;
                    self.entries[index].next_attempt_ms = now + delay;
                    self.entries[index].state = State::Scheduled;
                    return State::Scheduled;
                }
                self.entries.remove(index);
                State::Exhausted
            }
            Outcome::Permanent => {
                self.entries.remove(index);
                State::Permanent
            }
        }
    }

    pub fn cancel_stale(&mut self, due: &Due) -> State {
        match self.find(due) {
            Some(index) => {
                self.entries.remove(index);
                State::StaleConnection
            }
            None => due.state,
        }
    }

    pub fn purge(&mut self, bridge: &Arc<Bridge>) {
        self.entries
            .retain(|entry| !entry.bridge.as_ref().is_some_and(|held| Arc::ptr_eq(&held, bridge)));
    }

    pub fn cancel_all(&mut self) -> Vec<(u64, u32)> {
        self.entries
            .drain(..)
            .map(|entry| (entry.steam_id, entry.connection_id))
            .collect()
    }

    fn find(&self, due: &Due) -> Option<usize> {
        self.entries
            .iter()
            .position(|entry| entry.is_same(due.steam_id, due.connection_id, &due.bridge))
    }

    /// Exponential 25 ms → 2 s plus a bounded jitter, so many peers never retry in lockstep.
    fn retry_delay(&self, failed_attempts: i32) -> i64 {
        let mut delay = BASE_DELAY_MS;
        let mut attempt = 1;
        while attempt < failed_attempts && delay < MAX_DELAY_MS {
            delay = if delay > MAX_DELAY_MS / 2 { MAX_DELAY_MS } else { delay * 2 };
            attempt += 1;
        }
        let bound = delay / 4 + 1;
        let jitter = if self.jitter { crate::rng::below(bound as u64) as i64 } else { 0 };
        delay + jitter
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::bridge::now_ms;

    fn bytes() -> Vec<u8> {
        vec![0u8; 12]
    }

    fn delays() -> Vec<i64> {
        let retries = ResetRetries::new().without_jitter();
        (1..=12).map(|attempt| retries.retry_delay(attempt)).collect()
    }

    #[test]
    fn first_attempt_is_scheduled_and_becomes_due() {
        let mut retries = ResetRetries::new().without_jitter();
        let now = now_ms();
        assert_eq!(retries.offer(7, 1, bytes(), None, now), Offer::Accepted);
        assert!(retries.next_due(now).is_none());
        let due = retries.next_due(now + 25).expect("due after the base delay");
        assert_eq!(due.state, State::AwaitingSend);
        assert_eq!(due.attempts, 1);
    }

    #[test]
    fn duplicates_are_collapsed_per_connection() {
        let mut retries = ResetRetries::new().without_jitter();
        let now = now_ms();
        assert_eq!(retries.offer(7, 1, bytes(), None, now), Offer::Accepted);
        assert_eq!(retries.offer(7, 1, bytes(), None, now), Offer::Duplicate);
        assert_eq!(retries.offer(7, 2, bytes(), None, now), Offer::Accepted);
        assert_eq!(retries.len(), 2);
    }

    #[test]
    fn success_drops_the_entry() {
        let mut retries = ResetRetries::new().without_jitter();
        let now = now_ms();
        retries.offer(7, 1, bytes(), None, now);
        let due = retries.next_due(now + 25).unwrap();
        assert_eq!(retries.complete(&due, Outcome::Success, now + 25), State::Sent);
        assert!(retries.is_empty());
    }

    #[test]
    fn temporary_failures_back_off_until_the_budget_is_spent() {
        let mut retries = ResetRetries::new().without_jitter();
        let now = now_ms();
        retries.offer(7, 1, bytes(), None, now);
        let mut clock = now + BASE_DELAY_MS;
        let mut states = Vec::new();
        while let Some(due) = retries.next_due(clock) {
            assert_eq!(due.state, State::AwaitingSend);
            states.push(retries.complete(&due, Outcome::Temporary, clock));
            clock += MAX_DELAY_MS;
            if states.len() > MAX_ATTEMPTS as usize {
                break;
            }
        }
        assert_eq!(states.len(), (MAX_ATTEMPTS - 1) as usize);
        assert!(states[..states.len() - 1].iter().all(|state| *state == State::Scheduled));
        assert_eq!(states.last(), Some(&State::Exhausted));
        assert!(retries.is_empty());
    }

    #[test]
    fn age_cap_expires_an_entry_even_while_scheduled() {
        let mut retries = ResetRetries::new().without_jitter();
        let now = now_ms();
        retries.offer(7, 1, bytes(), None, now);
        let due = retries.next_due(now + MAX_AGE_MS).unwrap();
        assert_eq!(due.state, State::Exhausted);
        assert!(retries.is_empty());
    }

    #[test]
    fn capacity_is_bounded_and_reports_full() {
        let mut retries = ResetRetries::new().without_jitter();
        let now = now_ms();
        for connection in 0..CAPACITY as u32 {
            assert_eq!(retries.offer(7, connection, bytes(), None, now), Offer::Accepted);
        }
        assert_eq!(retries.offer(7, 999, bytes(), None, now), Offer::Full);
    }

    #[test]
    fn delay_doubles_and_clamps() {
        assert_eq!(delays(), vec![25, 50, 100, 200, 400, 800, 1600, 2000, 2000, 2000, 2000, 2000]);
    }

    #[test]
    fn jitters_within_a_quarter_of_the_delay() {
        let retries = ResetRetries::new();
        for attempt in 1..6 {
            let delay = retries.retry_delay(attempt);
            let base = 25i64 << (attempt - 1);
            assert!((base..base + base / 4 + 2).contains(&delay), "{attempt}: {delay}");
        }
    }

    #[test]
    fn purging_only_removes_the_named_bridge() {
        let mut retries = ResetRetries::new().without_jitter();
        let now = now_ms();
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let first = loopback(address);
        let second = loopback(address);
        drop(listener);
        retries.offer(7, 1, bytes(), Some(Arc::clone(&first)), now);
        retries.offer(7, 2, bytes(), Some(second), now);
        retries.purge(&first);
        assert_eq!(retries.len(), 1);
    }

    fn loopback(address: std::net::SocketAddr) -> Arc<Bridge> {
        struct NullSink;
        impl crate::bridge::RemoteSink for NullSink {
            fn send_data(&self, _: u64, _: u32, _: &[u8]) -> bool {
                true
            }
            fn send_fin(&self, _: u64, _: u32) -> bool {
                true
            }
            fn send_reset(&self, _: u64, _: u32) -> bool {
                true
            }
            fn forget_bridge(&self, _: u64, _: u32) {}
        }
        let socket = std::net::TcpStream::connect(address).unwrap();
        Bridge::new(7, 1, socket, false, Arc::new(NullSink))
    }
}
