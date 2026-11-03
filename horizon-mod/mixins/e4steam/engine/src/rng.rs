//! Random identifiers for the mod's protocol, without a `rand` dependency.
//!
//! Horizon only lets a mod's crate use crates already in its `Cargo.lock`, and `rand` is not in it.
//! `std` still gives a well-seeded hash state: `RandomState` draws its keys from the operating
//! system, so hashing a value that changes per call yields a value nobody outside the process can
//! predict. The tickets and connection ids below are exactly that kind of randomness — they have
//! to be unique and unguessable, not cryptographically audited.

use std::collections::hash_map::RandomState;
use std::hash::BuildHasher;
use std::sync::atomic::{AtomicU64, Ordering};

static COUNTER: AtomicU64 = AtomicU64::new(0x243f_6a88_85a3_08d3);

fn next_u64(label: u64) -> u64 {
    let tick = COUNTER.fetch_add(0x9e37_79b9_7f4a_7c15, Ordering::AcqRel);
    RandomState::new().hash_one((label, tick))
}

/// A `u32` for connection ids; the caller keeps asking until it is neither zero nor taken.
pub fn u32_value() -> u32 {
    next_u64(1) as u32
}

/// Sixteen secret bytes for an invite token.
pub fn bytes16() -> [u8; 16] {
    let first = next_u64(2).to_be_bytes();
    let second = next_u64(3).to_be_bytes();
    let mut token = [0u8; 16];
    token[..8].copy_from_slice(&first);
    token[8..].copy_from_slice(&second);
    token
}

/// A value in `0..bound`, for the jitter of a retry schedule.
pub fn below(bound: u64) -> u64 {
    if bound == 0 {
        return 0;
    }
    next_u64(4) % bound
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tokens_differ_from_call_to_call() {
        assert_ne!(bytes16(), bytes16());
        assert_ne!(u32_value(), u32_value());
    }

    #[test]
    fn jitter_stays_inside_its_bound() {
        for _ in 0..1000 {
            assert!(below(2000) < 2000);
        }
        assert_eq!(below(0), 0);
    }
}
