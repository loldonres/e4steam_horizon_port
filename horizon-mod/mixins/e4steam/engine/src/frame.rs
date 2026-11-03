//! Wire framing compatible with `link.e4steam.steam.SteamProtocol` from the Java mod.

pub const MAGIC: u32 = 0x4534_5354; // "E4ST"
pub const VERSION: u8 = 4;
pub const HEADER_SIZE: usize = 12;
pub const TOKEN_LENGTH: usize = 16;
pub const DATA_CHUNK_SIZE: usize = 32_768;
pub const MAX_DATAGRAM_SIZE: usize = 1_188;
pub const MAX_AUTH_TICKET_SIZE: usize = 4_096;
pub const MIN_DEDICATED_NONCE_SIZE: usize = 16;
pub const MAX_DEDICATED_NONCE_SIZE: usize = 64;
pub const OPEN_ACK_PAYLOAD_SIZE: usize = 3;
pub const DEDICATED_ACK_PAYLOAD_SIZE: usize = 8;
pub const MAX_PACKET_SIZE: usize = HEADER_SIZE + DATA_CHUNK_SIZE;
pub const MAX_ACCEPTED_PACKET_SIZE: usize = 1_048_576;

/// The only flag bit the Java mod defines. Its encoder never sets it — `SteamProtocol` has no
/// `Deflater` — but its decoder takes it for `DATA`, so a peer that does compress must be served.
pub const FLAG_COMPRESSED: u16 = 1;

pub const OPEN: u8 = 1;
pub const DATA: u8 = 2;
pub const FIN: u8 = 3;
pub const RESET: u8 = 4;
pub const DATAGRAM: u8 = 5;
pub const OPEN_ACK: u8 = 6;
pub const BRIDGE_READY: u8 = 7;
pub const DEDICATED_OPEN: u8 = 8;
pub const DEDICATED_OPEN_ACK: u8 = 9;
pub const ADDON_HELLO: u8 = 10;
pub const ADDON_DATA: u8 = 11;

#[derive(Debug)]
pub enum FrameError {
    TokenLength,
    PayloadLength(String),
    DedicatedGeneration,
    DedicatedNonceLength,
    AuthTicketLength,
    AddonFrameLength,
}

impl std::fmt::Display for FrameError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            FrameError::TokenLength => write!(f, "invite tokens must be 16 bytes"),
            FrameError::PayloadLength(len) => write!(f, "invalid steam payload length: {len}"),
            FrameError::DedicatedGeneration => write!(f, "invalid dedicated generation"),
            FrameError::DedicatedNonceLength => write!(f, "invalid dedicated nonce length"),
            FrameError::AuthTicketLength => write!(f, "invalid steam auth proof length"),
            FrameError::AddonFrameLength => write!(f, "invalid addon frame length"),
        }
    }
}

impl std::error::Error for FrameError {}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Frame {
    pub kind: u8,
    pub connection_id: u32,
    pub payload: Vec<u8>,
}

#[derive(Debug, PartialEq, Eq)]
pub enum Decode {
    Frame(Frame),
    NeedMore,
    Invalid,
}

fn header(kind: u8, flags: u16, connection_id: u32, payload_length: usize) -> Vec<u8> {
    let mut buf = Vec::with_capacity(HEADER_SIZE + payload_length);
    buf.extend_from_slice(&MAGIC.to_be_bytes());
    buf.push(VERSION);
    buf.push(kind);
    buf.extend_from_slice(&flags.to_be_bytes());
    buf.extend_from_slice(&connection_id.to_be_bytes());
    buf
}

pub fn encode_open(connection_id: u32, token: &[u8]) -> Result<Vec<u8>, FrameError> {
    if token.len() != TOKEN_LENGTH {
        return Err(FrameError::TokenLength);
    }
    let mut buf = header(OPEN, 0, connection_id, TOKEN_LENGTH);
    buf.extend_from_slice(token);
    Ok(buf)
}

pub fn encode_data(connection_id: u32, payload: &[u8]) -> Result<Vec<u8>, FrameError> {
    if payload.is_empty() || payload.len() > DATA_CHUNK_SIZE {
        return Err(FrameError::PayloadLength(payload.len().to_string()));
    }
    let mut buf = header(DATA, 0, connection_id, payload.len());
    buf.extend_from_slice(payload);
    Ok(buf)
}

pub fn encode_open_ack(connection_id: u32, client_port_mode: u8, host_port: u16) -> Vec<u8> {
    let mut buf = header(OPEN_ACK, 0, connection_id, OPEN_ACK_PAYLOAD_SIZE);
    buf.push(client_port_mode);
    buf.extend_from_slice(&host_port.to_be_bytes());
    buf
}

pub fn encode_bridge_ready(connection_id: u32) -> Vec<u8> {
    header(BRIDGE_READY, 0, connection_id, 0)
}

pub fn encode_fin(connection_id: u32) -> Vec<u8> {
    header(FIN, 0, connection_id, 0)
}

pub fn encode_reset(connection_id: u32) -> Vec<u8> {
    header(RESET, 0, connection_id, 0)
}

pub fn encode_datagram(connection_id: u32, payload: &[u8]) -> Result<Vec<u8>, FrameError> {
    if payload.is_empty() || payload.len() > MAX_DATAGRAM_SIZE {
        return Err(FrameError::PayloadLength(payload.len().to_string()));
    }
    let mut buf = header(DATAGRAM, 0, connection_id, payload.len());
    buf.extend_from_slice(payload);
    Ok(buf)
}

pub fn encode_dedicated_open(
    connection_id: u32,
    generation: u64,
    nonce: &[u8],
    auth_ticket: &[u8],
) -> Result<Vec<u8>, FrameError> {
    if generation == 0 {
        return Err(FrameError::DedicatedGeneration);
    }
    if nonce.len() < MIN_DEDICATED_NONCE_SIZE || nonce.len() > MAX_DEDICATED_NONCE_SIZE {
        return Err(FrameError::DedicatedNonceLength);
    }
    if auth_ticket.is_empty() || auth_ticket.len() > MAX_AUTH_TICKET_SIZE {
        return Err(FrameError::AuthTicketLength);
    }
    let size = 9 + nonce.len() + 2 + auth_ticket.len();
    let mut buf = header(DEDICATED_OPEN, 0, connection_id, size);
    buf.extend_from_slice(&generation.to_be_bytes());
    buf.push(nonce.len() as u8);
    buf.extend_from_slice(nonce);
    buf.extend_from_slice(&(auth_ticket.len() as u16).to_be_bytes());
    buf.extend_from_slice(auth_ticket);
    Ok(buf)
}

pub fn encode_dedicated_open_ack(connection_id: u32, generation: u64) -> Result<Vec<u8>, FrameError> {
    if generation == 0 {
        return Err(FrameError::DedicatedGeneration);
    }
    let mut buf = header(DEDICATED_OPEN_ACK, 0, connection_id, DEDICATED_ACK_PAYLOAD_SIZE);
    buf.extend_from_slice(&generation.to_be_bytes());
    Ok(buf)
}

pub fn encode_addon(kind: u8, connection_id: u32, payload: &[u8]) -> Result<Vec<u8>, FrameError> {
    if payload.is_empty() || payload.len() > DATA_CHUNK_SIZE {
        return Err(FrameError::AddonFrameLength);
    }
    let mut buf = header(kind, 0, connection_id, payload.len());
    buf.extend_from_slice(payload);
    Ok(buf)
}

/// `source` holds exactly one Steam message, as in the Java decoder.
pub fn decode(source: &[u8]) -> Decode {
    if source.len() < HEADER_SIZE {
        return Decode::NeedMore;
    }
    let mut pos = 0usize;
    let read_i32 = |p: &mut usize| -> i32 {
        let v = i32::from_be_bytes(source[*p..*p + 4].try_into().unwrap());
        *p += 4;
        v
    };
    let read_i8 = |p: &mut usize| -> i8 {
        let v = source[*p] as i8;
        *p += 1;
        v
    };
    let read_i16 = |p: &mut usize| -> i16 {
        let v = i16::from_be_bytes(source[*p..*p + 2].try_into().unwrap());
        *p += 2;
        v
    };
    let read_u8 = |p: &mut usize| -> u8 {
        let v = source[*p];
        *p += 1;
        v
    };

    if read_i32(&mut pos) as u32 != MAGIC || read_i8(&mut pos) as i8 != VERSION as i8 {
        return Decode::Invalid;
    }
    let kind = read_u8(&mut pos);
    let flags = read_i16(&mut pos);
    // `SteamProtocol.decode`: only the compression bit is known, and only a DATA frame may carry it.
    if flags & !1 != 0 || (flags != 0 && kind != DATA) {
        return Decode::Invalid;
    }
    let connection_id = read_i32(&mut pos) as u32;
    let payload_length = source.len() - pos;

    if connection_id == 0 {
        return Decode::Invalid;
    }
    match kind {
        OPEN if payload_length != TOKEN_LENGTH => return Decode::Invalid,
        OPEN_ACK if payload_length != OPEN_ACK_PAYLOAD_SIZE => return Decode::Invalid,
        DEDICATED_OPEN_ACK if payload_length != DEDICATED_ACK_PAYLOAD_SIZE => return Decode::Invalid,
        DEDICATED_OPEN
            if !(28..=4171).contains(&payload_length) =>
        {
            return Decode::Invalid
        }
        DATA | DATAGRAM | ADDON_HELLO | ADDON_DATA
            if payload_length == 0 || payload_length > chunk_limit(kind) =>
        {
            return Decode::Invalid
        }
        FIN | RESET | BRIDGE_READY if payload_length != 0 => return Decode::Invalid,
        OPEN | OPEN_ACK | BRIDGE_READY | DEDICATED_OPEN | DEDICATED_OPEN_ACK | DATA | FIN | RESET
        | DATAGRAM | ADDON_HELLO | ADDON_DATA => {}
        _ => return Decode::Invalid,
    }

    let payload = if flags & 1 != 0 {
        match decompress(&source[pos..]) {
            Some(payload) => payload,
            None => return Decode::Invalid,
        }
    } else {
        source[pos..].to_vec()
    };

    Decode::Frame(Frame {
        kind,
        connection_id,
        payload,
    })
}

/// `SteamProtocol.decompress`: a big-endian `uncompressedLength` in 1..=32768, then one zlib
/// stream. Java accepts it only when the stream finishes on exactly that many bytes and leaves
/// nothing unread, so a short, long or trailing payload is refused here too.
fn decompress(source: &[u8]) -> Option<Vec<u8>> {
    if source.len() <= 4 {
        return None;
    }
    let uncompressed = i32::from_be_bytes(source[..4].try_into().ok()?) as usize;
    if uncompressed == 0 || uncompressed > DATA_CHUNK_SIZE {
        return None;
    }
    let compressed = &source[4..];
    let mut out = vec![0u8; uncompressed];
    let mut inflator = flate2::Decompress::new(true);
    let status = inflator
        .decompress(compressed, &mut out, flate2::FlushDecompress::Finish)
        .ok()?;
    // `total_in` is what the stream itself consumed: trailing bytes must stay outside it, and the
    // whole rest of the frame has to be part of the zlib stream.
    if status != flate2::Status::StreamEnd
        || inflator.total_out() as usize != uncompressed
        || inflator.total_in() as usize != compressed.len()
    {
        return None;
    }
    out.truncate(inflator.total_out() as usize);
    Some(out)
}

fn chunk_limit(kind: u8) -> usize {
    match kind {
        DATAGRAM => MAX_DATAGRAM_SIZE,
        _ => DATA_CHUNK_SIZE,
    }
}

#[derive(Clone, Debug)]
pub struct DedicatedOpen {
    pub generation: u64,
    pub nonce: Vec<u8>,
    pub ticket: Vec<u8>,
}

/// Mirrors `decodeDedicatedOpen`, including its length gates.
pub fn decode_dedicated_open(payload: &[u8]) -> Option<DedicatedOpen> {
    if payload.len() < 28 {
        return None;
    }
    let mut pos = 0usize;
    let generation = u64::from_be_bytes(payload[pos..pos + 8].try_into().ok()?);
    pos += 8;
    let nonce_len = payload[pos] as usize;
    pos += 1;
    if generation == 0 || !(MIN_DEDICATED_NONCE_SIZE..=MAX_DEDICATED_NONCE_SIZE).contains(&nonce_len) {
        return None;
    }
    if payload.len() - pos < nonce_len + 3 {
        return None;
    }
    let nonce = payload[pos..pos + nonce_len].to_vec();
    pos += nonce_len;
    let ticket_len = u16::from_be_bytes(payload[pos..pos + 2].try_into().ok()?) as usize;
    pos += 2;
    if ticket_len < 1 || ticket_len > MAX_AUTH_TICKET_SIZE || payload.len() - pos != ticket_len {
        return None;
    }
    Some(DedicatedOpen {
        generation,
        nonce,
        ticket: payload[pos..].to_vec(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip_data() {
        let token = [7u8; 16];
        let open = encode_open(3, &token).unwrap();
        assert_eq!(&open[..4], &MAGIC.to_be_bytes());
        match decode(&open) {
            Decode::Frame(f) => {
                assert_eq!(f.kind, OPEN);
                assert_eq!(f.connection_id, 3);
                assert_eq!(f.payload, token.to_vec());
            }
            other => panic!("{other:?}"),
        }
    }

    #[test]
    fn a_compressed_frame_is_inflated() {
        use flate2::write::ZlibEncoder;
        use flate2::Compression;
        use std::io::Write;

        let body = b"minecraft sends its packets in 32 KiB chunks".to_vec();
        let mut encoder = ZlibEncoder::new(Vec::new(), Compression::default());
        encoder.write_all(&body).unwrap();
        let stream = encoder.finish().unwrap();

        let mut frame = header(DATA, FLAG_COMPRESSED, 9, 4 + stream.len());
        frame.extend_from_slice(&(body.len() as i32).to_be_bytes());
        frame.extend_from_slice(&stream);
        match decode(&frame) {
            Decode::Frame(f) => {
                assert_eq!(f.kind, DATA);
                assert_eq!(f.connection_id, 9);
                assert_eq!(f.payload, body);
            }
            other => panic!("{other:?}"),
        }
    }

    #[test]
    fn a_compressed_frame_that_is_not_a_zlib_stream_is_refused() {
        let mut frame = encode_data(9, b"hello").unwrap();
        frame[6..8].copy_from_slice(&FLAG_COMPRESSED.to_be_bytes());
        assert_eq!(decode(&frame), Decode::Invalid);
    }

    #[test]
    fn trailing_bytes_after_the_zlib_stream_are_refused() {
        use flate2::write::ZlibEncoder;
        use flate2::Compression;
        use std::io::Write;

        let mut encoder = ZlibEncoder::new(Vec::new(), Compression::default());
        encoder.write_all(b"payload").unwrap();
        let stream = encoder.finish().unwrap();

        let mut frame = header(DATA, FLAG_COMPRESSED, 4, 5 + stream.len());
        frame.extend_from_slice(&(7i32).to_be_bytes());
        frame.extend_from_slice(&stream);
        frame.push(0xAA);
        assert_eq!(decode(&frame), Decode::Invalid);
    }

    #[test]
    fn only_a_data_frame_may_carry_the_compression_bit() {
        for mut frame in [encode_open_ack(2, 0, 25565), encode_datagram(2, b"hi").unwrap()] {
            frame[6..8].copy_from_slice(&FLAG_COMPRESSED.to_be_bytes());
            assert_eq!(decode(&frame), Decode::Invalid);
        }
        let mut unknown_flag = encode_data(2, b"payload").unwrap();
        unknown_flag[6..8].copy_from_slice(&2i16.to_be_bytes());
        assert_eq!(decode(&unknown_flag), Decode::Invalid);
    }

    #[test]
    fn rejects_bad_frames() {
        assert_eq!(decode(&[0u8; 12]), Decode::Invalid);
        let mut zero_cid = encode_bridge_ready(0);
        zero_cid[8..12].copy_from_slice(&0u32.to_be_bytes());
        assert_eq!(decode(&zero_cid), Decode::Invalid);
        let mut short = encode_open(1, &[0u8; 16]).unwrap();
        short.truncate(short.len() - 1);
        assert_eq!(decode(&short), Decode::Invalid);
        assert_eq!(decode(&[0u8; 4]), Decode::NeedMore);
    }

    #[test]
    fn dedicated_open_roundtrip() {
        let nonce = [0xA5u8; 16];
        let ticket = vec![0x11u8; 40];
        let buf = encode_dedicated_open(11, 42, &nonce, &ticket).unwrap();
        match decode(&buf) {
            Decode::Frame(f) => {
                assert_eq!(f.kind, DEDICATED_OPEN);
                let d = decode_dedicated_open(&f.payload).unwrap();
                assert_eq!(d.generation, 42);
                assert_eq!(d.nonce, nonce.to_vec());
                assert_eq!(d.ticket, ticket);
            }
            other => panic!("{other:?}"),
        }
    }
}
