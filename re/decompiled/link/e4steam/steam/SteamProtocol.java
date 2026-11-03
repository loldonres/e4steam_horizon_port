package link.e4steam.steam;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

final class SteamProtocol {
   static final int MAGIC = 1161057108;
   static final byte VERSION = 4;
   static final byte OPEN = 1;
   static final byte DATA = 2;
   static final byte FIN = 3;
   static final byte RESET = 4;
   static final byte DATAGRAM = 5;
   static final byte OPEN_ACK = 6;
   static final byte BRIDGE_READY = 7;
   static final byte DEDICATED_OPEN = 8;
   static final byte DEDICATED_OPEN_ACK = 9;
   static final byte ADDON_HELLO = 10;
   static final byte ADDON_DATA = 11;
   private static final short FLAG_COMPRESSED = 1;
   private static final short KNOWN_FLAGS = 1;
   private static final int COMPRESSED_LENGTH_SIZE = 4;
   static final int OPEN_ACK_PAYLOAD_SIZE = 3;
   static final int DEDICATED_ACK_PAYLOAD_SIZE = 8;
   static final int MIN_DEDICATED_NONCE_SIZE = 16;
   static final int MAX_DEDICATED_NONCE_SIZE = 64;
   static final int MAX_AUTH_TICKET_SIZE = 4096;
   static final int DATA_CHUNK_SIZE = 32768;
   static final int HEADER_SIZE = 12;
   static final int MAX_DATAGRAM_SIZE = 1188;
   static final int MAX_PACKET_SIZE = 12 + Math.max(32768, 1188);
   static final int MAX_ACCEPTED_STEAM_PACKET_SIZE = 1048576;

   private SteamProtocol() {
   }

   static byte[] encodeOpen(int connectionId, byte[] token) {
      if (token.length != 16) {
         throw new IllegalArgumentException("Invalid invite token length");
      } else {
         ByteBuffer buffer = header((byte)1, connectionId, 16);
         buffer.put(token);
         return buffer.array();
      }
   }

   static byte[] encodeData(int connectionId, byte[] payload) {
      if (payload.length != 0 && payload.length <= 32768) {
         ByteBuffer buffer = header((byte)2, (short)0, connectionId, payload.length);
         buffer.put(payload);
         return buffer.array();
      } else {
         throw new IllegalArgumentException("Invalid Steam payload length: " + payload.length);
      }
   }

   static byte[] encodeOpenAck(int connectionId, byte clientPortMode, int hostPort) {
      if (hostPort >= 0 && hostPort <= 65535) {
         ByteBuffer buffer = header((byte)6, connectionId, 3);
         buffer.put(clientPortMode);
         buffer.putShort((short)hostPort);
         return buffer.array();
      } else {
         throw new IllegalArgumentException("Invalid UDP host port");
      }
   }

   static byte[] encodeBridgeReady(int connectionId) {
      return header((byte)7, connectionId, 0).array();
   }

   static byte[] encodeDedicatedOpen(int connectionId, long generation, byte[] nonce, byte[] authTicket) {
      if (generation <= 0L) {
         throw new IllegalArgumentException("Invalid dedicated generation");
      } else if (nonce == null || nonce.length < 16 || nonce.length > 64) {
         throw new IllegalArgumentException("Invalid dedicated nonce length");
      } else if (authTicket != null && authTicket.length != 0 && authTicket.length <= 4096) {
         int size = 9 + nonce.length + 2 + authTicket.length;
         ByteBuffer buffer = header((byte)8, connectionId, size);
         buffer.putLong(generation);
         buffer.put((byte)nonce.length);
         buffer.put(nonce);
         buffer.putShort((short)authTicket.length);
         buffer.put(authTicket);
         return buffer.array();
      } else {
         throw new IllegalArgumentException("Invalid Steam auth proof length");
      }
   }

   static byte[] encodeDedicatedOpenAck(int connectionId, long generation) {
      if (generation <= 0L) {
         throw new IllegalArgumentException("Invalid dedicated generation");
      } else {
         return header((byte)9, connectionId, 8).putLong(generation).array();
      }
   }

   static byte[] encodeAddonHello(int connectionId, byte[] payload) {
      return encodeAddonFrame((byte)10, connectionId, payload);
   }

   static byte[] encodeAddonData(int connectionId, byte[] payload) {
      return encodeAddonFrame((byte)11, connectionId, payload);
   }

   static SteamProtocol.DedicatedOpen decodeDedicatedOpen(byte[] payload) {
      if (payload != null && payload.length >= 28) {
         ByteBuffer buffer = ByteBuffer.wrap(payload);
         long generation = buffer.getLong();
         int nonceLength = Byte.toUnsignedInt(buffer.get());
         if (generation > 0L && nonceLength >= 16 && nonceLength <= 64 && buffer.remaining() >= nonceLength + 2 + 1) {
            byte[] nonce = new byte[nonceLength];
            buffer.get(nonce);
            int ticketLength = Short.toUnsignedInt(buffer.getShort());
            if (ticketLength >= 1 && ticketLength <= 4096 && buffer.remaining() == ticketLength) {
               byte[] ticket = new byte[ticketLength];
               buffer.get(ticket);
               return new SteamProtocol.DedicatedOpen(generation, nonce, ticket);
            } else {
               Arrays.fill(nonce, (byte)0);
               return null;
            }
         } else {
            return null;
         }
      } else {
         return null;
      }
   }

   static byte[] encodeFin(int connectionId) {
      return header((byte)3, connectionId, 0).array();
   }

   static byte[] encodeReset(int connectionId) {
      return header((byte)4, connectionId, 0).array();
   }

   static byte[] encodeDatagram(int connectionId, byte[] payload) {
      if (payload.length != 0 && payload.length <= 1188) {
         ByteBuffer buffer = header((byte)5, connectionId, payload.length);
         buffer.put(payload);
         return buffer.array();
      } else {
         throw new IllegalArgumentException("Invalid UDP payload length: " + payload.length);
      }
   }

   static SteamProtocol.Frame decode(ByteBuffer source) {
      if (source.remaining() < 12) {
         return null;
      } else if (source.getInt() == 1161057108 && source.get() == 4) {
         byte type = source.get();
         short flags = source.getShort();
         if ((flags & -2) == 0 && (flags == 0 || type == 2)) {
            int connectionId = source.getInt();
            int payloadLength = source.remaining();
            if (connectionId == 0) {
               return null;
            } else if (type == 1 && payloadLength != 16) {
               return null;
            } else if (type == 6 && payloadLength != 3) {
               return null;
            } else if (type == 9 && payloadLength != 8) {
               return null;
            } else if (type != 8 || payloadLength >= 28 && payloadLength <= 4171) {
               if (type != 2 || payloadLength != 0 && payloadLength <= 32768) {
                  if (type != 5 || payloadLength != 0 && payloadLength <= 1188) {
                     if (type != 10 && type != 11 || payloadLength != 0 && payloadLength <= 32768) {
                        if ((type == 3 || type == 4 || type == 7) && payloadLength != 0) {
                           return null;
                        } else if (type != 1
                           && type != 6
                           && type != 7
                           && type != 8
                           && type != 9
                           && type != 2
                           && type != 3
                           && type != 4
                           && type != 5
                           && type != 10
                           && type != 11) {
                           return null;
                        } else {
                           byte[] payload;
                           if (type == 2 && (flags & 1) != 0) {
                              payload = decompress(source);
                              if (payload == null) {
                                 return null;
                              }
                           } else {
                              payload = new byte[payloadLength];
                              source.get(payload);
                           }

                           return new SteamProtocol.Frame(type, connectionId, payload);
                        }
                     } else {
                        return null;
                     }
                  } else {
                     return null;
                  }
               } else {
                  return null;
               }
            } else {
               return null;
            }
         } else {
            return null;
         }
      } else {
         return null;
      }
   }

   private static ByteBuffer header(byte type, int connectionId, int payloadLength) {
      return header(type, (short)0, connectionId, payloadLength);
   }

   private static byte[] encodeAddonFrame(byte type, int connectionId, byte[] payload) {
      if (payload != null && payload.length != 0 && payload.length <= 32768) {
         ByteBuffer buffer = header(type, connectionId, payload.length);
         buffer.put(payload);
         return buffer.array();
      } else {
         throw new IllegalArgumentException("Invalid addon frame length");
      }
   }

   private static ByteBuffer header(byte type, short flags, int connectionId, int payloadLength) {
      return ByteBuffer.allocate(12 + payloadLength).putInt(1161057108).put((byte)4).put(type).putShort(flags).putInt(connectionId);
   }

   private static byte[] decompress(ByteBuffer source) {
      if (source.remaining() <= 4) {
         return null;
      } else {
         int uncompressedLength = source.getInt();
         if (uncompressedLength > 0 && uncompressedLength <= 32768) {
            byte[] compressed = new byte[source.remaining()];
            source.get(compressed);
            byte[] result = new byte[uncompressedLength];
            Inflater inflater = new Inflater();

            try {
               inflater.setInput(compressed);
               int offset = 0;

               while (!inflater.finished() && offset < result.length) {
                  int read = inflater.inflate(result, offset, result.length - offset);
                  if (read == 0) {
                     return null;
                  }

                  offset += read;
               }

               return offset == result.length && inflater.finished() && inflater.getRemaining() == 0 ? result : null;
            } catch (DataFormatException var11) {
               return null;
            } finally {
               inflater.end();
            }
         } else {
            return null;
         }
      }
   }

   static final class DedicatedOpen {
      private final long generation;
      private final byte[] nonce;
      private byte[] ticket;

      private DedicatedOpen(long generation, byte[] nonce, byte[] ticket) {
         this.generation = generation;
         this.nonce = nonce;
         this.ticket = ticket;
      }

      long generation() {
         return this.generation;
      }

      byte[] nonce() {
         return (byte[])this.nonce.clone();
      }

      synchronized byte[] takeTicket() {
         byte[] value = this.ticket;
         this.ticket = null;
         return value == null ? new byte[0] : value;
      }

      synchronized void destroy() {
         Arrays.fill(this.nonce, (byte)0);
         if (this.ticket != null) {
            Arrays.fill(this.ticket, (byte)0);
            this.ticket = null;
         }
      }

      @Override
      public String toString() {
         return "DedicatedOpen{generation=" + this.generation + ", credentials=redacted}";
      }
   }

   static final class Frame {
      private final byte type;
      private final int connectionId;
      private final byte[] payload;

      Frame(byte type, int connectionId, byte[] payload) {
         this.type = type;
         this.connectionId = connectionId;
         this.payload = payload;
      }

      byte type() {
         return this.type;
      }

      int connectionId() {
         return this.connectionId;
      }

      byte[] payload() {
         return this.payload;
      }
   }
}
