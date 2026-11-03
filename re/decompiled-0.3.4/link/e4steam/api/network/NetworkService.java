package link.e4steam.api.network;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.regex.Pattern;
import link.e4steam.api.ApiResult;
import link.e4steam.api.ApiValidation;
import link.e4steam.api.Registration;
import link.e4steam.api.identity.IdentityService;
import link.e4steam.api.session.SessionService;

public interface NetworkService {
   ApiResult<NetworkService.ChannelHandle> register(NetworkService.ChannelDescriptor var1, NetworkService.MessageHandler var2);

   boolean registrationsFrozen();

   public static final class ChannelDescriptor {
      private final NetworkService.ChannelId id;
      private final int minimumVersion;
      private final int maximumVersion;
      private final NetworkService.Requirement requirement;
      private final NetworkService.Direction direction;
      private final NetworkService.Delivery delivery;
      private final int maximumMessageBytes;
      private final int bytesPerSecond;
      private final int queueMessages;
      private final String schemaId;

      public ChannelDescriptor(
         NetworkService.ChannelId id,
         int minimumVersion,
         int maximumVersion,
         NetworkService.Requirement requirement,
         NetworkService.Direction direction,
         NetworkService.Delivery delivery,
         int maximumMessageBytes,
         int bytesPerSecond,
         int queueMessages,
         String schemaId
      ) {
         this.id = Objects.requireNonNull(id, "id");
         if (minimumVersion < 1 || maximumVersion < minimumVersion || maximumVersion > 65535) {
            throw new IllegalArgumentException("invalid protocol range");
         } else if (maximumMessageBytes < 1 || maximumMessageBytes > 1048576) {
            throw new IllegalArgumentException("invalid maximumMessageBytes");
         } else if (bytesPerSecond < 1024 || bytesPerSecond > 16777216) {
            throw new IllegalArgumentException("invalid bytesPerSecond");
         } else if (queueMessages >= 1 && queueMessages <= 1024) {
            this.minimumVersion = minimumVersion;
            this.maximumVersion = maximumVersion;
            this.requirement = Objects.requireNonNull(requirement, "requirement");
            this.direction = Objects.requireNonNull(direction, "direction");
            this.delivery = Objects.requireNonNull(delivery, "delivery");
            this.maximumMessageBytes = maximumMessageBytes;
            this.bytesPerSecond = bytesPerSecond;
            this.queueMessages = queueMessages;
            this.schemaId = ApiValidation.text(schemaId, "schemaId", 96);
            ApiValidation.rejectSensitiveName(this.schemaId, "schemaId");
         } else {
            throw new IllegalArgumentException("invalid queueMessages");
         }
      }

      public NetworkService.ChannelId id() {
         return this.id;
      }

      public int minimumVersion() {
         return this.minimumVersion;
      }

      public int maximumVersion() {
         return this.maximumVersion;
      }

      public NetworkService.Requirement requirement() {
         return this.requirement;
      }

      public NetworkService.Direction direction() {
         return this.direction;
      }

      public NetworkService.Delivery delivery() {
         return this.delivery;
      }

      public int maximumMessageBytes() {
         return this.maximumMessageBytes;
      }

      public int bytesPerSecond() {
         return this.bytesPerSecond;
      }

      public int queueMessages() {
         return this.queueMessages;
      }

      public String schemaId() {
         return this.schemaId;
      }
   }

   public interface ChannelHandle extends Registration {
      NetworkService.ChannelDescriptor descriptor();

      NetworkService.ChannelState state();

      CompletionStage<ApiResult<NetworkService.SendStatus>> send(SessionService.SessionId var1, IdentityService.PeerId var2, byte[] var3);
   }

   public static final class ChannelId {
      private static final Pattern FORMAT = Pattern.compile("^[a-z][a-z0-9_.-]{0,31}:[a-z][a-z0-9_./-]{0,63}$");
      private final String value;

      public ChannelId(String value) {
         this.value = ApiValidation.identifier(value, "channelId", FORMAT);
      }

      public String value() {
         return this.value;
      }

      @Override
      public boolean equals(Object other) {
         return this == other || other instanceof NetworkService.ChannelId && this.value.equals(((NetworkService.ChannelId)other).value);
      }

      @Override
      public int hashCode() {
         return this.value.hashCode();
      }

      @Override
      public String toString() {
         return this.value;
      }
   }

   public static enum ChannelState {
      REGISTERED,
      NEGOTIATING,
      AVAILABLE,
      UNAVAILABLE,
      CLOSED;
   }

   public static enum Delivery {
      RELIABLE_ORDERED,
      RELIABLE_UNORDERED,
      UNRELIABLE;
   }

   public static enum Direction {
      CLIENT_TO_HOST,
      HOST_TO_CLIENT,
      BIDIRECTIONAL;
   }

   public static final class MessageContext {
      private final SessionService.SessionId sessionId;
      private final IdentityService.PeerId peerId;
      private final int negotiatedVersion;

      public MessageContext(SessionService.SessionId sessionId, IdentityService.PeerId peerId, int negotiatedVersion) {
         this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
         this.peerId = Objects.requireNonNull(peerId, "peerId");
         if (negotiatedVersion < 1) {
            throw new IllegalArgumentException("invalid negotiatedVersion");
         } else {
            this.negotiatedVersion = negotiatedVersion;
         }
      }

      public SessionService.SessionId sessionId() {
         return this.sessionId;
      }

      public IdentityService.PeerId peerId() {
         return this.peerId;
      }

      public int negotiatedVersion() {
         return this.negotiatedVersion;
      }
   }

   public interface MessageHandler {
      CompletionStage<ApiResult<Boolean>> onMessage(NetworkService.MessageContext var1, byte[] var2);
   }

   public static final class MessageReader {
      private static final Charset UTF8 = Charset.forName("UTF-8");
      private final byte[] input;
      private int position;

      public MessageReader(byte[] input, int maximumBytes) {
         this.input = ApiValidation.bytes(input, maximumBytes, "input");
      }

      public int readVarInt() {
         int value = 0;

         for (int index = 0; index < 5; index++) {
            int next = this.readUnsignedByte();
            if (index == 4 && (next & 248) != 0) {
               throw new IllegalArgumentException("varint overflow");
            }

            value |= (next & 127) << index * 7;
            if ((next & 128) == 0) {
               if (index > 0 && next == 0) {
                  throw new IllegalArgumentException("non-canonical varint");
               }

               return value;
            }
         }

         throw new IllegalArgumentException("varint overflow");
      }

      public byte[] readBytes(int maximumBytes) {
         int size = this.readVarInt();
         if (size >= 0 && size <= maximumBytes && size <= this.remaining()) {
            byte[] value = new byte[size];
            System.arraycopy(this.input, this.position, value, 0, size);
            this.position += size;
            return value;
         } else {
            throw new IllegalArgumentException("invalid byte length");
         }
      }

      public String readUtf8(int maximumChars, int maximumBytes) {
         byte[] bytes = this.readBytes(maximumBytes);

         try {
            String value = UTF8.newDecoder()
               .onMalformedInput(CodingErrorAction.REPORT)
               .onUnmappableCharacter(CodingErrorAction.REPORT)
               .decode(ByteBuffer.wrap(bytes))
               .toString();
            return ApiValidation.text(value, "utf8", maximumChars);
         } catch (CharacterCodingException var5) {
            throw new IllegalArgumentException("invalid UTF-8");
         }
      }

      public int remaining() {
         return this.input.length - this.position;
      }

      private int readUnsignedByte() {
         if (this.position >= this.input.length) {
            throw new IllegalArgumentException("truncated message");
         } else {
            return this.input[this.position++] & 0xFF;
         }
      }
   }

   public static final class MessageWriter {
      private static final Charset UTF8 = Charset.forName("UTF-8");
      private final ByteArrayOutputStream output = new ByteArrayOutputStream();
      private final int limit;

      public MessageWriter(int limit) {
         if (limit >= 1 && limit <= 1048576) {
            this.limit = limit;
         } else {
            throw new IllegalArgumentException("invalid limit");
         }
      }

      public NetworkService.MessageWriter writeVarInt(int value) {
         if (value < 0) {
            throw new IllegalArgumentException("value must be non-negative");
         } else {
            int current = value;

            do {
               this.ensure(1);
               int bits = current & 127;
               current >>>= 7;
               this.output.write(current == 0 ? bits : bits | 128);
            } while (current != 0);

            return this;
         }
      }

      public NetworkService.MessageWriter writeUtf8(String value, int maximumChars) {
         String checked = ApiValidation.text(value, "value", maximumChars);
         byte[] bytes = checked.getBytes(UTF8);
         this.writeVarInt(bytes.length);
         this.ensure(bytes.length);
         this.output.write(bytes, 0, bytes.length);
         return this;
      }

      public NetworkService.MessageWriter writeBytes(byte[] value, int maximumBytes) {
         byte[] copy = ApiValidation.bytes(value, maximumBytes, "value");
         this.writeVarInt(copy.length);
         this.ensure(copy.length);
         this.output.write(copy, 0, copy.length);
         return this;
      }

      public byte[] toByteArray() {
         return this.output.toByteArray();
      }

      private void ensure(int size) {
         if (size < 0 || this.output.size() > this.limit - size) {
            throw new IllegalStateException("message size limit exceeded");
         }
      }
   }

   public static enum Requirement {
      REQUIRED,
      OPTIONAL;
   }

   public static enum SendStatus {
      ACCEPTED,
      QUEUE_FULL,
      RATE_LIMITED,
      UNAVAILABLE,
      STALE_SESSION,
      CLOSED;
   }
}
