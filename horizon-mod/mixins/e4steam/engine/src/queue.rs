use crate::frame;
use std::collections::VecDeque;
use std::sync::{Mutex, MutexGuard};

pub const OUTBOUND_CAPACITY: usize = 2048;
pub const OUTBOUND_DATA_CAPACITY: usize = 1344;
pub const OUTBOUND_DATAGRAM_CAPACITY: usize = 320;
pub const OUTBOUND_HANDSHAKE_CAPACITY: usize = 64;
pub const OUTBOUND_STANDALONE_RESET_CAPACITY: usize = 64;

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
enum Permit {
    Handshake,
    Data,
    Datagram,
    StandaloneReset,
}

/// `reserve_exempt` mirrors the mod's `isTerminal`: FIN and RESET are always admitted.
/// `permit` mirrors `categorySlots`: a bridge-bound RESET holds no category slot.
#[derive(Clone, Copy)]
struct Class {
    reserve_exempt: bool,
    permit: Option<Permit>,
}

fn classify(kind: u8, attached_to_bridge: bool) -> Option<Class> {
    Some(match kind {
        frame::OPEN | frame::OPEN_ACK | frame::BRIDGE_READY | frame::DEDICATED_OPEN | frame::DEDICATED_OPEN_ACK => Class {
            reserve_exempt: false,
            permit: Some(Permit::Handshake),
        },
        frame::DATA => Class {
            reserve_exempt: false,
            permit: Some(Permit::Data),
        },
        frame::DATAGRAM => Class {
            reserve_exempt: false,
            permit: Some(Permit::Datagram),
        },
        frame::FIN => Class {
            reserve_exempt: true,
            permit: None,
        },
        frame::RESET => Class {
            reserve_exempt: true,
            permit: if attached_to_bridge {
                None
            } else {
                Some(Permit::StandaloneReset)
            },
        },
        _ => return None,
    })
}

/// A fully encoded E4ST frame plus the Steam peer it is addressed to.
#[derive(Clone, Debug)]
pub struct Packet {
    pub steam_id: u64,
    pub connection_id: u32,
    pub kind: u8,
    pub bytes: Vec<u8>,
    pub attached_to_bridge: bool,
}

impl Packet {
    pub fn new(steam_id: u64, connection_id: u32, bytes: Vec<u8>) -> Self {
        let kind = bytes.get(5).copied().unwrap_or(0);
        Self {
            steam_id,
            connection_id,
            kind,
            bytes,
            attached_to_bridge: true,
        }
    }

    /// A frame that belongs to no live bridge, e.g. RESET for an unknown invite token.
    pub fn detached(steam_id: u64, connection_id: u32, bytes: Vec<u8>) -> Self {
        let mut packet = Self::new(steam_id, connection_id, bytes);
        packet.attached_to_bridge = false;
        packet
    }

    fn class(&self) -> Option<Class> {
        classify(self.kind, self.attached_to_bridge)
    }
}

#[derive(Clone, Copy, Default)]
struct Permits {
    handshake: usize,
    data: usize,
    datagram: usize,
    standalone_reset: usize,
}

#[derive(Clone, Copy)]
pub struct Limits {
    pub capacity: usize,
    pub handshake: usize,
    pub data: usize,
    pub datagram: usize,
    pub standalone_reset: usize,
}

#[derive(Default)]
struct Used {
    packets: VecDeque<Packet>,
    permits: Permits,
}

pub struct OutboundQueue {
    limits: Limits,
    terminal_reserve: usize,
    used: Mutex<Used>,
}

impl OutboundQueue {
    pub fn new() -> Self {
        Self::with_limits(Limits {
            capacity: OUTBOUND_CAPACITY,
            handshake: OUTBOUND_HANDSHAKE_CAPACITY,
            data: OUTBOUND_DATA_CAPACITY,
            datagram: OUTBOUND_DATAGRAM_CAPACITY,
            standalone_reset: OUTBOUND_STANDALONE_RESET_CAPACITY,
        })
    }

    /// `terminal_reserve` mirrors the mod: `max(1, min(64, capacity / 16))`.
    pub fn with_limits(limits: Limits) -> Self {
        Self {
            limits,
            terminal_reserve: (limits.capacity / 16).clamp(1, 64),
            used: Mutex::new(Used {
                packets: VecDeque::with_capacity(limits.capacity.min(1024)),
                permits: Permits::default(),
            }),
        }
    }

    pub fn offer(&self, packet: Packet) -> bool {
        let Some(class) = packet.class() else {
            return false;
        };
        let mut used = self.lock();
        if !class.reserve_exempt && self.limits.capacity - used.packets.len() <= self.terminal_reserve {
            return false;
        }
        if !self.take(&mut used.permits, class) {
            return false;
        }
        if used.packets.len() >= self.limits.capacity {
            self.give(&mut used.permits, class);
            return false;
        }
        used.packets.push_back(packet);
        true
    }

    pub fn poll(&self) -> Option<Packet> {
        let mut used = self.lock();
        let packet = used.packets.pop_front()?;
        if let Some(class) = packet.class() {
            self.give(&mut used.permits, class);
        }
        Some(packet)
    }

    /// Drop every queued frame of a bridge that went away.
    pub fn purge(&self, steam_id: u64, connection_id: u32) {
        let mut used = self.lock();
        let stale: Vec<usize> = used
            .packets
            .iter()
            .enumerate()
            .filter(|(_, packet)| packet.steam_id == steam_id && packet.connection_id == connection_id)
            .map(|(index, _)| index)
            .collect();
        for index in stale.into_iter().rev() {
            if let Some(packet) = used.packets.remove(index) {
                if let Some(class) = packet.class() {
                    self.give(&mut used.permits, class);
                }
            }
        }
    }

    pub fn clear(&self) {
        let mut used = self.lock();
        while let Some(packet) = used.packets.pop_front() {
            if let Some(class) = packet.class() {
                self.give(&mut used.permits, class);
            }
        }
    }

    pub fn len(&self) -> usize {
        self.lock().packets.len()
    }

    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    pub fn remaining_capacity(&self) -> usize {
        self.limits.capacity.saturating_sub(self.len())
    }

    fn take(&self, permits: &mut Permits, class: Class) -> bool {
        let Some((used, limit)) = self.pair(permits, class) else {
            return true;
        };
        if *used >= limit {
            return false;
        }
        *used += 1;
        true
    }

    fn give(&self, permits: &mut Permits, class: Class) {
        if let Some((used, _)) = self.pair(permits, class) {
            *used = used.saturating_sub(1);
        }
    }

    fn pair<'a>(&self, permits: &'a mut Permits, class: Class) -> Option<(&'a mut usize, usize)> {
        match class.permit? {
            Permit::Handshake => Some((&mut permits.handshake, self.limits.handshake)),
            Permit::Data => Some((&mut permits.data, self.limits.data)),
            Permit::Datagram => Some((&mut permits.datagram, self.limits.datagram)),
            Permit::StandaloneReset => Some((&mut permits.standalone_reset, self.limits.standalone_reset)),
        }
    }

    fn lock(&self) -> MutexGuard<'_, Used> {
        self.used.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
    }
}

impl Default for OutboundQueue {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn limits(capacity: usize, data: usize, datagram: usize, handshake: usize, reset: usize) -> Limits {
        Limits {
            capacity,
            handshake,
            data,
            datagram,
            standalone_reset: reset,
        }
    }

    fn data_packet(id: u32) -> Packet {
        Packet::new(1, id, frame::encode_data(id, b"x").expect("valid payload"))
    }

    #[test]
    fn category_permits_are_independent() {
        let queue = OutboundQueue::with_limits(limits(1000, 2, 1, 1, 1));
        assert!(queue.offer(data_packet(1)));
        assert!(queue.offer(data_packet(2)));
        assert!(!queue.offer(data_packet(3)), "DATA saturates its own category");
        assert!(queue.offer(Packet::new(1, 9, frame::encode_datagram(9, b"y").expect("valid"))));
        assert_eq!(queue.len(), 3);
        queue.poll();
        assert!(queue.offer(data_packet(4)), "poll releases the permit");
    }

    #[test]
    fn terminal_frames_bypass_the_reserve() {
        // capacity 100 -> reserve = 100/16 = 6, so the last six slots are terminal-only.
        let queue = OutboundQueue::with_limits(limits(100, 94, 1, 1, 1));
        for id in 0..94u32 {
            assert!(queue.offer(data_packet(id)));
        }
        assert_eq!(queue.remaining_capacity(), queue.terminal_reserve);
        assert!(!queue.offer(data_packet(94)), "reserve blocks non-terminal frames");
        assert!(queue.offer(Packet::new(1, 900, frame::encode_fin(900))));
        assert!(queue.offer(Packet::detached(1, 0, frame::encode_reset(0))));
    }

    #[test]
    fn bridge_bound_reset_is_free_but_standalone_is_not() {
        let queue = OutboundQueue::with_limits(limits(100, 10, 10, 10, 1));
        assert!(queue.offer(Packet::detached(5, 0, frame::encode_reset(0))));
        assert!(!queue.offer(Packet::detached(5, 0, frame::encode_reset(0))), "1 standalone slot");
        for _ in 0..99 {
            let mut packet = Packet::new(5, 7, frame::encode_reset(7));
            packet.attached_to_bridge = true;
            assert!(queue.offer(packet), "bridge RESET takes no permit");
        }
        assert_eq!(queue.len(), 100);
        let mut overflow = Packet::new(5, 7, frame::encode_reset(7));
        overflow.attached_to_bridge = true;
        assert!(!queue.offer(overflow), "total capacity still applies");
    }

    #[test]
    fn purge_returns_permits_of_a_dead_bridge() {
        let queue = OutboundQueue::with_limits(limits(1000, 2, 1, 1, 1));
        assert!(queue.offer(data_packet(11)));
        assert!(queue.offer(data_packet(11)));
        queue.purge(1, 11);
        assert!(queue.is_empty());
        assert!(queue.offer(data_packet(12)));
        assert!(queue.offer(data_packet(13)));
        assert!(!queue.offer(data_packet(14)));
    }

    #[test]
    fn reserve_matches_the_mod_formula() {
        assert_eq!(OutboundQueue::new().terminal_reserve, 64);
        assert_eq!(OutboundQueue::with_limits(limits(16, 1, 1, 1, 1)).terminal_reserve, 1);
    }

    #[test]
    fn handshake_frames_share_one_category() {
        let queue = OutboundQueue::with_limits(limits(1000, 10, 10, 2, 10));
        assert!(queue.offer(Packet::new(1, 2, frame::encode_open(2, &[7u8; 16]).expect("token"))));
        assert!(queue.offer(Packet::new(1, 2, frame::encode_open_ack(2, 1, 0))));
        assert!(
            !queue.offer(Packet::new(1, 2, frame::encode_bridge_ready(2))),
            "third handshake frame must fail"
        );
    }

    #[test]
    fn addon_frames_are_rejected() {
        let queue = OutboundQueue::new();
        let bytes = frame::encode_addon(frame::ADDON_DATA, 3, b"hi").expect("addon payload");
        assert!(!queue.offer(Packet::new(1, 3, bytes)));
    }
}
