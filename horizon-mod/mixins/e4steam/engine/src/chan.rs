//! The small slice of channel behaviour the bridge needs, built on `std` alone.
//!
//! The Horizon native mod's crate may only depend on crates already in Horizon's `Cargo.lock`, and
//! `crossbeam-channel` is not one of them, so this replaces the handful of methods the bridge and
//! the runtime actually call: `bounded`, `unbounded`, `try_send`, `send`, `try_recv` and `recv`.

use std::collections::VecDeque;
use std::fmt;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Condvar, Mutex};

/// A message could not be queued because the channel is full or its receiver is gone.
pub enum TrySendError<T> {
    Full(T),
    Disconnected(T),
}

impl<T> fmt::Debug for TrySendError<T> {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Full(_) => formatter.write_str("Full(..)"),
            Self::Disconnected(_) => formatter.write_str("Disconnected(..)"),
        }
    }
}

#[derive(Debug, PartialEq, Eq, Clone, Copy)]
pub struct SendError<T>(pub T);

#[derive(Debug, PartialEq, Eq, Clone, Copy)]
pub enum RecvError {
    Disconnected,
}

#[derive(Debug, PartialEq, Eq, Clone, Copy)]
pub enum TryRecvError {
    Empty,
    Disconnected,
}

#[derive(Debug)]
struct Shared<T> {
    queue: Mutex<VecDeque<T>>,
    /// Wakes senders parked in `send` once a receiver takes a message out.
    slots: Condvar,
    /// Wakes receivers parked in `recv` once a sender offers a message or goes away.
    messages: Condvar,
    senders: AtomicUsize,
    receiver_alive: AtomicBool,
    capacity: usize,
}

impl<T> Shared<T> {
    fn new(capacity: usize) -> Self {
        Self {
            queue: Mutex::new(VecDeque::new()),
            slots: Condvar::new(),
            messages: Condvar::new(),
            senders: AtomicUsize::new(1),
            receiver_alive: AtomicBool::new(true),
            capacity,
        }
    }
}

pub struct Sender<T> {
    shared: Arc<Shared<T>>,
}

pub struct Receiver<T> {
    shared: Arc<Shared<T>>,
}

impl<T> Clone for Sender<T> {
    fn clone(&self) -> Self {
        self.shared.senders.fetch_add(1, Ordering::AcqRel);
        Self {
            shared: Arc::clone(&self.shared),
        }
    }
}

impl<T> Drop for Sender<T> {
    fn drop(&mut self) {
        if self.shared.senders.fetch_sub(1, Ordering::AcqRel) == 1 {
            self.shared.messages.notify_all();
        }
    }
}

impl<T> Drop for Receiver<T> {
    fn drop(&mut self) {
        self.shared.receiver_alive.store(false, Ordering::Release);
        self.shared.slots.notify_all();
    }
}

/// A channel that holds at most `capacity` messages, so a producer feels the backlog.
pub fn bounded<T>(capacity: usize) -> (Sender<T>, Receiver<T>) {
    let shared = Arc::new(Shared::new(capacity));
    (
        Sender {
            shared: Arc::clone(&shared),
        },
        Receiver { shared },
    )
}

/// A channel that never refuses a message while its receiver is alive.
pub fn unbounded<T>() -> (Sender<T>, Receiver<T>) {
    bounded(usize::MAX)
}

impl<T> Sender<T> {
    pub fn try_send(&self, message: T) -> Result<(), TrySendError<T>> {
        if !self.shared.receiver_alive.load(Ordering::Acquire) {
            return Err(TrySendError::Disconnected(message));
        }
        let mut queue = self.shared.queue.lock().unwrap();
        if queue.len() >= self.shared.capacity {
            return Err(TrySendError::Full(message));
        }
        queue.push_back(message);
        drop(queue);
        self.shared.messages.notify_one();
        Ok(())
    }

    /// Parks until there is room, then hands the message over.
    pub fn send(&self, message: T) -> Result<(), SendError<T>> {
        let mut waiting = message;
        loop {
            match self.try_send(waiting) {
                Ok(()) => return Ok(()),
                Err(TrySendError::Disconnected(value)) => return Err(SendError(value)),
                Err(TrySendError::Full(value)) => waiting = value,
            }
            let mut queue = self.shared.queue.lock().unwrap();
            while queue.len() >= self.shared.capacity
                && self.shared.receiver_alive.load(Ordering::Acquire)
            {
                queue = self.shared.slots.wait(queue).unwrap();
            }
        }
    }

    pub fn is_closed(&self) -> bool {
        !self.shared.receiver_alive.load(Ordering::Acquire)
    }
}

impl<T> Receiver<T> {
    pub fn try_recv(&self) -> Result<T, TryRecvError> {
        if let Some(message) = self.take_queued() {
            return Ok(message);
        }
        if self.shared.senders.load(Ordering::Acquire) == 0 {
            return Err(TryRecvError::Disconnected);
        }
        Err(TryRecvError::Empty)
    }

    pub fn recv(&self) -> Result<T, RecvError> {
        let mut queue = self.shared.queue.lock().unwrap();
        loop {
            if let Some(message) = queue.pop_front() {
                drop(queue);
                self.shared.slots.notify_one();
                return Ok(message);
            }
            if self.shared.senders.load(Ordering::Acquire) == 0 {
                return Err(RecvError::Disconnected);
            }
            queue = self.shared.messages.wait(queue).unwrap();
        }
    }

    /// Pops one message and, in the same step, frees a slot for a parked sender.
    fn take_queued(&self) -> Option<T> {
        let message = self.shared.queue.lock().unwrap().pop_front()?;
        self.shared.slots.notify_one();
        Some(message)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Barrier;

    #[test]
    fn a_bounded_channel_refuses_more_than_its_capacity() {
        let (tx, rx) = bounded(2);
        assert!(tx.try_send(1).is_ok());
        assert!(tx.try_send(2).is_ok());
        assert!(matches!(tx.try_send(3), Err(TrySendError::Full(3))));
        assert_eq!(rx.try_recv(), Ok(1));
        assert!(tx.try_send(4).is_ok());
    }

    #[test]
    fn a_parked_receiver_wakes_for_the_message_its_sender_offers() {
        let (tx, rx) = bounded(1);
        let gate = Arc::new(Barrier::new(2));
        let reader = {
            let gate = Arc::clone(&gate);
            std::thread::spawn(move || {
                gate.wait();
                rx.recv().unwrap()
            })
        };
        gate.wait();
        tx.send(7).unwrap();
        assert_eq!(reader.join().unwrap(), 7);
    }

    #[test]
    fn dropping_the_last_sender_ends_the_receiver() {
        let (tx, rx) = unbounded::<u8>();
        tx.send(1).unwrap();
        drop(tx);
        assert_eq!(rx.try_recv(), Ok(1));
        assert_eq!(rx.try_recv(), Err(TryRecvError::Disconnected));
        assert_eq!(rx.recv(), Err(RecvError::Disconnected));
    }

    #[test]
    fn send_after_the_receiver_is_gone_gives_the_message_back() {
        let (tx, rx) = unbounded();
        drop(rx);
        assert_eq!(tx.send(5), Err(SendError(5)));
        assert!(tx.is_closed());
    }

    #[test]
    fn a_full_bounded_send_wakes_when_the_receiver_drains_it() {
        let (tx, rx) = bounded(1);
        tx.try_send("one").unwrap();
        let sender = {
            let tx = tx.clone();
            std::thread::spawn(move || tx.send("two"))
        };
        assert_eq!(rx.recv(), Ok("one"));
        sender.join().unwrap().unwrap();
        assert_eq!(rx.recv(), Ok("two"));
    }
}
