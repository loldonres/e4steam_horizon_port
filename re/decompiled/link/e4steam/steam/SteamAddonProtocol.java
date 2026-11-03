package link.e4steam.steam;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import link.e4steam.api.network.NetworkService;

final class SteamAddonProtocol {
   static final byte VERSION = 1;
   static final int NONCE_SIZE = 16;
   static final int MAX_CHANNELS = 32;
   static final int MAX_FRAGMENT_BYTES = 32256;
   static final int MAX_FRAGMENTS = 33;

   private SteamAddonProtocol() {
   }

   static byte[] encodeHello(byte[] nonce, List<NetworkService.ChannelDescriptor> descriptors) {
      if (nonce != null && nonce.length == 16 && descriptors != null && descriptors.size() <= 32) {
         List<NetworkService.ChannelDescriptor> ordered = new ArrayList<>(descriptors);
         ordered.sort(Comparator.comparing(value -> value.id().value()));
         Set<String> unique = new HashSet<>();

         try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeByte(1);
            output.write(nonce);
            output.writeShort(ordered.size());

            for (NetworkService.ChannelDescriptor descriptor : ordered) {
               if (!unique.add(descriptor.id().value())) {
                  throw new IllegalArgumentException("Duplicate addon channel");
               }

               writeUtf8(output, descriptor.id().value(), 384);
               output.writeShort(descriptor.minimumVersion());
               output.writeShort(descriptor.maximumVersion());
               output.writeByte(descriptor.requirement().ordinal());
               output.writeByte(descriptor.direction().ordinal());
               output.writeByte(descriptor.delivery().ordinal());
               output.writeInt(descriptor.maximumMessageBytes());
               output.writeInt(descriptor.bytesPerSecond());
               output.writeShort(descriptor.queueMessages());
               writeUtf8(output, descriptor.schemaId(), 384);
            }

            output.flush();
            byte[] encoded = bytes.toByteArray();
            if (encoded.length > 32768) {
               throw new IllegalArgumentException("Addon hello exceeds the frame limit");
            } else {
               return encoded;
            }
         } catch (IOException var8) {
            throw new IllegalStateException("In-memory addon encoding failed", var8);
         }
      } else {
         throw new IllegalArgumentException("Invalid addon hello");
      }
   }

   static SteamAddonProtocol.Hello decodeHello(byte[] payload) {
      if (payload != null && payload.length >= 19 && payload.length <= 32768) {
         try {
            ByteBuffer input = ByteBuffer.wrap(payload);
            if (input.get() != 1) {
               return null;
            } else {
               byte[] nonce = new byte[16];
               input.get(nonce);
               int count = Short.toUnsignedInt(input.getShort());
               if (count > 32) {
                  return null;
               } else {
                  List<SteamAddonProtocol.ChannelOffer> channels = new ArrayList<>(count);
                  Set<String> unique = new HashSet<>();
                  int index = 0;

                  while (index < count) {
                     String id = readUtf8(input, 384);
                     if (unique.add(id) && input.remaining() >= 17) {
                        int minimumVersion = Short.toUnsignedInt(input.getShort());
                        int maximumVersion = Short.toUnsignedInt(input.getShort());
                        NetworkService.Requirement requirement = enumValue(NetworkService.Requirement.values(), input.get());
                        NetworkService.Direction direction = enumValue(NetworkService.Direction.values(), input.get());
                        NetworkService.Delivery delivery = enumValue(NetworkService.Delivery.values(), input.get());
                        int maximumMessageBytes = input.getInt();
                        int bytesPerSecond = input.getInt();
                        int queueMessages = Short.toUnsignedInt(input.getShort());
                        String schemaId = readUtf8(input, 384);
                        if (requirement != null && direction != null && delivery != null) {
                           NetworkService.ChannelDescriptor descriptor = new NetworkService.ChannelDescriptor(
                              new NetworkService.ChannelId(id),
                              minimumVersion,
                              maximumVersion,
                              requirement,
                              direction,
                              delivery,
                              maximumMessageBytes,
                              bytesPerSecond,
                              queueMessages,
                              schemaId
                           );
                           channels.add(new SteamAddonProtocol.ChannelOffer(descriptor));
                           index++;
                           continue;
                        }

                        return null;
                     }

                     return null;
                  }

                  if (input.hasRemaining()) {
                     return null;
                  } else {
                     channels.sort(Comparator.comparing(value -> value.descriptor().id().value()));
                     return new SteamAddonProtocol.Hello(nonce, channels);
                  }
               }
            }
         } catch (RuntimeException var18) {
            return null;
         }
      } else {
         return null;
      }
   }

   static List<byte[]> encodeData(byte[] bindingNonce, String channelId, int negotiatedVersion, long sequence, byte[] payload) {
      if (bindingNonce != null
         && bindingNonce.length == 16
         && channelId != null
         && negotiatedVersion >= 1
         && sequence > 0L
         && payload != null
         && payload.length != 0
         && payload.length <= 1048576) {
         new NetworkService.ChannelId(channelId);
         int fragments = (payload.length + 32256 - 1) / 32256;
         if (fragments >= 1 && fragments <= 33) {
            List<byte[]> result = new ArrayList<>(fragments);

            for (int index = 0; index < fragments; index++) {
               int offset = index * 32256;
               int length = Math.min(32256, payload.length - offset);
               ByteArrayOutputStream bytes = new ByteArrayOutputStream(length + 160);

               try {
                  DataOutputStream output = new DataOutputStream(bytes);
                  output.writeByte(1);
                  output.write(bindingNonce);
                  writeUtf8(output, channelId, 384);
                  output.writeShort(negotiatedVersion);
                  output.writeLong(sequence);
                  output.writeShort(index);
                  output.writeShort(fragments);
                  output.writeInt(payload.length);
                  output.write(payload, offset, length);
                  output.flush();
               } catch (IOException var13) {
                  throw new IllegalStateException("In-memory addon encoding failed", var13);
               }

               byte[] encoded = bytes.toByteArray();
               if (encoded.length > 32768) {
                  throw new IllegalArgumentException("Addon fragment exceeds the frame limit");
               }

               result.add(encoded);
            }

            return Collections.unmodifiableList(result);
         } else {
            throw new IllegalArgumentException("Invalid addon fragment count");
         }
      } else {
         throw new IllegalArgumentException("Invalid addon data message");
      }
   }

   static SteamAddonProtocol.Fragment decodeData(byte[] payload) {
      if (payload != null && payload.length >= 38 && payload.length <= 32768) {
         try {
            ByteBuffer input = ByteBuffer.wrap(payload);
            if (input.get() != 1) {
               return null;
            } else {
               byte[] nonce = new byte[16];
               input.get(nonce);
               String channelId = readUtf8(input, 384);
               if (input.remaining() < 19) {
                  return null;
               } else {
                  int version = Short.toUnsignedInt(input.getShort());
                  long sequence = input.getLong();
                  int index = Short.toUnsignedInt(input.getShort());
                  int count = Short.toUnsignedInt(input.getShort());
                  int totalLength = input.getInt();
                  int fragmentLength = input.remaining();
                  if (version >= 1
                     && sequence > 0L
                     && count >= 1
                     && count <= 33
                     && index < count
                     && totalLength >= 1
                     && totalLength <= 1048576
                     && fragmentLength >= 1
                     && fragmentLength <= 32256) {
                     int expectedCount = (totalLength + 32256 - 1) / 32256;
                     int expectedLength = index == count - 1 ? totalLength - index * 32256 : 32256;
                     if (count == expectedCount && fragmentLength == expectedLength) {
                        new NetworkService.ChannelId(channelId);
                        byte[] fragment = new byte[fragmentLength];
                        input.get(fragment);
                        return new SteamAddonProtocol.Fragment(nonce, channelId, version, sequence, index, count, totalLength, fragment);
                     } else {
                        return null;
                     }
                  } else {
                     return null;
                  }
               }
            }
         } catch (RuntimeException var14) {
            return null;
         }
      } else {
         return null;
      }
   }

   private static void writeUtf8(DataOutputStream output, String value, int maximumBytes) throws IOException {
      byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
      if (bytes.length >= 1 && bytes.length <= maximumBytes && bytes.length <= 65535) {
         output.writeShort(bytes.length);
         output.write(bytes);
      } else {
         throw new IllegalArgumentException("Invalid addon UTF-8 field");
      }
   }

   private static String readUtf8(ByteBuffer input, int maximumBytes) {
      if (input.remaining() < 2) {
         throw new IllegalArgumentException("Truncated text");
      } else {
         int length = Short.toUnsignedInt(input.getShort());
         if (length >= 1 && length <= maximumBytes && length <= input.remaining()) {
            ByteBuffer slice = input.slice();
            slice.limit(length);

            String value;
            try {
               value = StandardCharsets.UTF_8
                  .newDecoder()
                  .onMalformedInput(CodingErrorAction.REPORT)
                  .onUnmappableCharacter(CodingErrorAction.REPORT)
                  .decode(slice)
                  .toString();
            } catch (CharacterCodingException var6) {
               throw new IllegalArgumentException("Invalid UTF-8");
            }

            input.position(input.position() + length);
            return value;
         } else {
            throw new IllegalArgumentException("Invalid text length");
         }
      }
   }

   private static <T> T enumValue(T[] values, byte encoded) {
      int index = Byte.toUnsignedInt(encoded);
      return index < values.length ? values[index] : null;
   }

   static final class ChannelOffer {
      private final NetworkService.ChannelDescriptor descriptor;

      private ChannelOffer(NetworkService.ChannelDescriptor descriptor) {
         this.descriptor = descriptor;
      }

      NetworkService.ChannelDescriptor descriptor() {
         return this.descriptor;
      }
   }

   static final class Fragment {
      private final byte[] bindingNonce;
      private final String channelId;
      private final int version;
      private final long sequence;
      private final int index;
      private final int count;
      private final int totalLength;
      private final byte[] payload;

      private Fragment(byte[] bindingNonce, String channelId, int version, long sequence, int index, int count, int totalLength, byte[] payload) {
         this.bindingNonce = (byte[])bindingNonce.clone();
         this.channelId = channelId;
         this.version = version;
         this.sequence = sequence;
         this.index = index;
         this.count = count;
         this.totalLength = totalLength;
         this.payload = (byte[])payload.clone();
      }

      byte[] bindingNonce() {
         return (byte[])this.bindingNonce.clone();
      }

      String channelId() {
         return this.channelId;
      }

      int version() {
         return this.version;
      }

      long sequence() {
         return this.sequence;
      }

      int index() {
         return this.index;
      }

      int count() {
         return this.count;
      }

      int totalLength() {
         return this.totalLength;
      }

      byte[] payload() {
         return (byte[])this.payload.clone();
      }

      @Override
      public String toString() {
         return "Fragment{channel=" + this.channelId + ", version=" + this.version + ", index=" + this.index + "/" + this.count + ", payload=redacted}";
      }
   }

   static final class Hello {
      private final byte[] nonce;
      private final List<SteamAddonProtocol.ChannelOffer> channels;

      private Hello(byte[] nonce, List<SteamAddonProtocol.ChannelOffer> channels) {
         this.nonce = (byte[])nonce.clone();
         this.channels = Collections.unmodifiableList(new ArrayList<>(channels));
      }

      byte[] nonce() {
         return (byte[])this.nonce.clone();
      }

      List<SteamAddonProtocol.ChannelOffer> channels() {
         return this.channels;
      }

      @Override
      public String toString() {
         return "Hello{channels=" + this.channels.size() + ", nonce=redacted}";
      }
   }
}
