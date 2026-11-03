package link.e4steam.steam;

import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

final class SteamNetworkingMessagesTransport implements AutoCloseable {
   private static final Logger LOGGER = LogManager.getLogger("e4steam");
   private static final int STEAM_IDENTITY_TYPE = 16;
   private static final int STEAM_IDENTITY_SIZE = 136;
   private static final int STEAM_IDENTITY_VALUE_SIZE = 8;
   private static final int RESULT_OK = 1;
   private static final int SEND_NO_NAGLE = 1;
   private static final int SEND_NO_DELAY = 4;
   private static final int SEND_RELIABLE = 8;
   private static final int SEND_AUTO_RESTART_BROKEN_SESSION = 32;
   private static final int SEND_UNRELIABLE_NO_DELAY = 5;
   private static final int SEND_RELIABLE_NO_NAGLE = 9;
   private static final int CONNECTION_STATE_CONNECTING = 1;
   private static final int CONNECTION_STATE_FINDING_ROUTE = 2;
   private static final long IDENTITY_TYPE_OFFSET = 0L;
   private static final long IDENTITY_SIZE_OFFSET = 4L;
   private static final long IDENTITY_VALUE_OFFSET = 8L;
   private static final long MESSAGE_DATA_OFFSET = 0L;
   private static final long MESSAGE_SIZE_OFFSET = 8L;
   private static final long MESSAGE_IDENTITY_OFFSET = 16L;
   private static final long STATUS_PENDING_UNRELIABLE_OFFSET = 36L;
   private static final long STATUS_PENDING_RELIABLE_OFFSET = 40L;
   private static final long STATUS_SENT_UNACKED_RELIABLE_OFFSET = 44L;
   private static final long REAL_TIME_STATUS_SIZE = 128L;
   private static final long CONNECTION_INFO_END_REASON_OFFSET = 176L;
   private static final long CONNECTION_INFO_END_DEBUG_OFFSET = 180L;
   private static final int CONNECTION_INFO_END_DEBUG_SIZE = 128;
   private final SteamNetworkingMessagesTransport.NativeAccess nativeAccess;
   private final SteamNetworkingMessagesTransport.SessionListener listener;
   private final Map<Long, Memory> identities = new HashMap<>();
   private final Memory realTimeStatus = new Memory(128L);
   private final SteamNetworkingMessagesTransport.SessionRequestCallback requestCallback = this::handleSessionRequest;
   private final SteamNetworkingMessagesTransport.SessionFailedCallback failedCallback = this::handleSessionFailed;
   private final SteamNetworkingMessagesTransport.DebugOutputCallback debugOutputCallback = this::handleDebugOutput;
   private static final int RECEIVE_BATCH_SIZE = 32;
   private final ArrayDeque<Pointer> pendingMessages = new ArrayDeque<>(32);
   private boolean closed;

   static SteamNetworkingMessagesTransport open(Path steamApiLibrary, SteamNetworkingMessagesTransport.SessionListener listener) throws IOException {
      return new SteamNetworkingMessagesTransport(new SteamNetworkingMessagesTransport.JnaNativeAccess(steamApiLibrary, false), listener);
   }

   static SteamNetworkingMessagesTransport openGameServer(Path steamApiLibrary, SteamNetworkingMessagesTransport.SessionListener listener) throws IOException {
      return new SteamNetworkingMessagesTransport(new SteamNetworkingMessagesTransport.JnaNativeAccess(steamApiLibrary, true), listener);
   }

   SteamNetworkingMessagesTransport(SteamNetworkingMessagesTransport.NativeAccess nativeAccess, SteamNetworkingMessagesTransport.SessionListener listener) {
      this.nativeAccess = Objects.requireNonNull(nativeAccess, "nativeAccess");
      this.listener = Objects.requireNonNull(listener, "listener");

      try {
         nativeAccess.setCallbacks(this.requestCallback, this.failedCallback);
         nativeAccess.setDebugOutput(4, this.debugOutputCallback);
         nativeAccess.initializeRelayNetworkAccess();
      } catch (Error | RuntimeException var6) {
         try {
            nativeAccess.setCallbacks(null, null);
            nativeAccess.setDebugOutput(0, null);
         } catch (Throwable var5) {
         }

         throw var6;
      }
   }

   boolean send(long remoteSteamId, ByteBuffer payload, boolean unreliable, int channel) throws IOException {
      return this.sendResult(remoteSteamId, payload, unreliable, channel) == 1;
   }

   int sendResult(long remoteSteamId, ByteBuffer payload, boolean unreliable, int channel) throws IOException {
      return this.sendResult(remoteSteamId, payload, unreliable, false, channel);
   }

   int sendResult(long remoteSteamId, ByteBuffer payload, boolean unreliable, boolean autoRestartBrokenSession, int channel) throws IOException {
      this.ensureOpen();
      if (unreliable && autoRestartBrokenSession) {
         throw new IOException("A datagram cannot restart a broken reliable Steam session");
      } else if (!payload.isDirect()) {
         throw new IOException("Steam Networking Messages requires a direct send buffer");
      } else {
         int size = payload.remaining();
         Pointer data = Native.getDirectBufferPointer(payload);
         if (data == null) {
            throw new IOException("Could not access the direct Steam send buffer");
         } else {
            data = data.share(payload.position());
            int flags = unreliable ? 5 : 9;
            if (autoRestartBrokenSession) {
               flags |= 32;
            }

            return this.nativeAccess.send(this.identity(remoteSteamId), data, size, flags, channel);
         }
      }
   }

   int availablePacketSize(int channel) throws IOException {
      this.ensureOpen();
      if (this.pendingMessages.isEmpty()) {
         Pointer[] messages = new Pointer[32];
         int count = this.nativeAccess.receive(channel, messages, 32);
         if (count < 0 || count > 32) {
            throw new IOException("Steam returned an invalid received-message count: " + count);
         }

         if (count == 0) {
            return 0;
         }

         for (int index = 0; index < count; index++) {
            if (messages[index] == null) {
               while (!this.pendingMessages.isEmpty()) {
                  this.nativeAccess.releaseMessage(this.pendingMessages.removeFirst());
               }

               throw new IOException("Steam returned a null received message");
            }

            this.pendingMessages.addLast(messages[index]);
         }
      }

      Pointer pendingMessage = this.pendingMessages.peekFirst();
      int size = pendingMessage.getInt(8L);
      if (size == 0) {
         this.nativeAccess.releaseMessage(this.pendingMessages.removeFirst());
      }

      return size;
   }

   SteamNetworkingMessagesTransport.Received receive(ByteBuffer target, int channel) throws IOException {
      this.ensureOpen();
      Pointer message = this.pendingMessages.pollFirst();
      if (message == null) {
         throw new IOException("No Steam Networking Messages packet is available on channel " + channel);
      } else {
         SteamNetworkingMessagesTransport.Received var6;
         try {
            int size = message.getInt(8L);
            if (size < 0) {
               throw new IOException("Steam returned a negative message size: " + size);
            }

            if (size > target.remaining()) {
               throw new BufferOverflowException();
            }

            Pointer data = message.getPointer(0L);
            if (size > 0 && data == null) {
               throw new IOException("Steam returned a null message payload");
            }

            if (size > 0) {
               target.put(data.getByteBuffer(0L, size));
            }

            var6 = new SteamNetworkingMessagesTransport.Received(readSteamId(message.share(16L)), size);
         } finally {
            this.nativeAccess.releaseMessage(message);
         }

         return var6;
      }
   }

   void discardPendingMessage() throws IOException {
      this.ensureOpen();
      Pointer message = this.pendingMessages.pollFirst();
      if (message != null) {
         this.nativeAccess.releaseMessage(message);
      }
   }

   boolean accept(long remoteSteamId) {
      this.ensureOpenUnchecked();
      return this.nativeAccess.accept(this.identity(remoteSteamId));
   }

   void closePeer(long remoteSteamId) {
      if (!this.closed) {
         Memory identity = this.identities.remove(remoteSteamId);
         this.nativeAccess.closeSession(identity == null ? newIdentity(remoteSteamId) : identity);
      }
   }

   boolean hasQueuedPackets(long remoteSteamId) {
      if (this.closed) {
         return false;
      } else {
         this.realTimeStatus.clear();
         int state = this.nativeAccess.getSessionConnectionInfo(this.identity(remoteSteamId), this.realTimeStatus);
         return state == 1 || state == 2 || this.realTimeStatus.getInt(36L) > 0 || this.realTimeStatus.getInt(40L) > 0 || this.realTimeStatus.getInt(44L) > 0;
      }
   }

   @Override
   public void close() {
      if (!this.closed) {
         this.closed = true;
         this.nativeAccess.setCallbacks(null, null);
         this.nativeAccess.setDebugOutput(0, null);

         Pointer message;
         while ((message = this.pendingMessages.pollFirst()) != null) {
            this.nativeAccess.releaseMessage(message);
         }

         for (Memory identity : this.identities.values()) {
            this.nativeAccess.closeSession(identity);
         }

         this.identities.clear();
      }
   }

   private void handleSessionRequest(Pointer request) {
      if (!this.closed && request != null) {
         long remoteSteamId = readSteamId(request);
         if (remoteSteamId != 0L) {
            try {
               this.listener.onSessionRequest(remoteSteamId);
            } catch (Throwable var5) {
               LOGGER.error("Steam session-request callback failed", var5);
            }
         }
      }
   }

   private void handleSessionFailed(Pointer failure) {
      if (!this.closed && failure != null) {
         long remoteSteamId = readSteamId(failure);
         if (remoteSteamId != 0L) {
            int endReason = failure.getInt(176L);
            String detail = readFixedString(failure, 180L, 128);

            try {
               this.listener.onSessionFailed(remoteSteamId, endReason, detail);
            } catch (Throwable var7) {
               LOGGER.error("Steam session-failure callback failed", var7);
            }
         }
      }
   }

   private void handleDebugOutput(int type, String message) {
      if (message != null && !message.trim().isEmpty()) {
         String clean = message.trim();
         if (type <= 2) {
            LOGGER.warn("Steam Networking Sockets: {}", clean);
         } else {
            LOGGER.debug("Steam Networking Sockets: {}", clean);
         }
      }
   }

   private Memory identity(long remoteSteamId) {
      return this.identities.computeIfAbsent(remoteSteamId, SteamNetworkingMessagesTransport::newIdentity);
   }

   static Memory newIdentity(long remoteSteamId) {
      Memory identity = new Memory(136L);
      identity.clear();
      identity.setInt(0L, 16);
      identity.setInt(4L, 8);
      identity.setLong(8L, remoteSteamId);
      return identity;
   }

   static long readSteamId(Pointer identity) {
      return identity != null && identity.getInt(0L) == 16 && identity.getInt(4L) == 8 ? identity.getLong(8L) : 0L;
   }

   private static String readFixedString(Pointer source, long offset, int maximumSize) {
      byte[] bytes = source.getByteArray(offset, maximumSize);
      int length = 0;

      while (length < bytes.length && bytes[length] != 0) {
         length++;
      }

      return new String(bytes, 0, length, StandardCharsets.UTF_8);
   }

   private void ensureOpen() throws IOException {
      if (this.closed) {
         throw new IOException("Steam Networking Messages transport is closed");
      }
   }

   private void ensureOpenUnchecked() {
      if (this.closed) {
         throw new IllegalStateException("Steam Networking Messages transport is closed");
      }
   }

   interface DebugOutputCallback extends Callback {
      void invoke(int var1, String var2);
   }

   private interface FlatApi extends Library {
      Pointer SteamAPI_SteamNetworkingMessages_SteamAPI_v002();

      Pointer SteamAPI_SteamGameServerNetworkingMessages_SteamAPI_v002();

      Pointer SteamAPI_SteamNetworkingUtils_SteamAPI_v004();

      int SteamAPI_ISteamNetworkingMessages_SendMessageToUser(Pointer var1, Pointer var2, Pointer var3, int var4, int var5, int var6);

      int SteamAPI_ISteamNetworkingMessages_ReceiveMessagesOnChannel(Pointer var1, int var2, Pointer[] var3, int var4);

      byte SteamAPI_ISteamNetworkingMessages_AcceptSessionWithUser(Pointer var1, Pointer var2);

      byte SteamAPI_ISteamNetworkingMessages_CloseSessionWithUser(Pointer var1, Pointer var2);

      int SteamAPI_ISteamNetworkingMessages_GetSessionConnectionInfo(Pointer var1, Pointer var2, Pointer var3, Pointer var4);

      void SteamAPI_ISteamNetworkingUtils_InitRelayNetworkAccess(Pointer var1);

      void SteamAPI_ISteamNetworkingUtils_SetGlobalCallback_MessagesSessionRequest(Pointer var1, SteamNetworkingMessagesTransport.SessionRequestCallback var2);

      void SteamAPI_ISteamNetworkingUtils_SetGlobalCallback_MessagesSessionFailed(Pointer var1, SteamNetworkingMessagesTransport.SessionFailedCallback var2);

      void SteamAPI_ISteamNetworkingUtils_SetDebugOutputFunction(Pointer var1, int var2, SteamNetworkingMessagesTransport.DebugOutputCallback var3);

      void SteamAPI_SteamNetworkingMessage_t_Release(Pointer var1);
   }

   private static final class JnaNativeAccess implements SteamNetworkingMessagesTransport.NativeAccess {
      private final SteamNetworkingMessagesTransport.FlatApi api;
      private final Pointer messages;
      private final Pointer utils;

      private JnaNativeAccess(Path steamApiLibrary, boolean gameServer) throws IOException {
         Objects.requireNonNull(steamApiLibrary, "steamApiLibrary");

         try {
            this.api = (SteamNetworkingMessagesTransport.FlatApi)Native.loadLibrary(
               steamApiLibrary.toAbsolutePath().normalize().toString(), SteamNetworkingMessagesTransport.FlatApi.class
            );
            this.messages = gameServer
               ? this.api.SteamAPI_SteamGameServerNetworkingMessages_SteamAPI_v002()
               : this.api.SteamAPI_SteamNetworkingMessages_SteamAPI_v002();
            this.utils = this.api.SteamAPI_SteamNetworkingUtils_SteamAPI_v004();
         } catch (RuntimeException | UnsatisfiedLinkError var4) {
            throw new IOException("Could not bind Steam Networking Messages", var4);
         }

         if (this.messages == null || this.utils == null) {
            throw new IOException("Steam Networking Messages is unavailable after SteamAPI initialization");
         }
      }

      @Override
      public int send(Pointer identity, Pointer data, int size, int flags, int channel) {
         return this.api.SteamAPI_ISteamNetworkingMessages_SendMessageToUser(this.messages, identity, data, size, flags, channel);
      }

      @Override
      public int receive(int channel, Pointer[] output, int maxMessages) {
         return this.api.SteamAPI_ISteamNetworkingMessages_ReceiveMessagesOnChannel(this.messages, channel, output, maxMessages);
      }

      @Override
      public boolean accept(Pointer identity) {
         return this.api.SteamAPI_ISteamNetworkingMessages_AcceptSessionWithUser(this.messages, identity) != 0;
      }

      @Override
      public void closeSession(Pointer identity) {
         this.api.SteamAPI_ISteamNetworkingMessages_CloseSessionWithUser(this.messages, identity);
      }

      @Override
      public int getSessionConnectionInfo(Pointer identity, Pointer realTimeStatus) {
         return this.api.SteamAPI_ISteamNetworkingMessages_GetSessionConnectionInfo(this.messages, identity, null, realTimeStatus);
      }

      @Override
      public void initializeRelayNetworkAccess() {
         this.api.SteamAPI_ISteamNetworkingUtils_InitRelayNetworkAccess(this.utils);
      }

      @Override
      public void releaseMessage(Pointer message) {
         this.api.SteamAPI_SteamNetworkingMessage_t_Release(message);
      }

      @Override
      public void setCallbacks(SteamNetworkingMessagesTransport.SessionRequestCallback request, SteamNetworkingMessagesTransport.SessionFailedCallback failure) {
         this.api.SteamAPI_ISteamNetworkingUtils_SetGlobalCallback_MessagesSessionRequest(this.utils, request);
         this.api.SteamAPI_ISteamNetworkingUtils_SetGlobalCallback_MessagesSessionFailed(this.utils, failure);
      }

      @Override
      public void setDebugOutput(int detailLevel, SteamNetworkingMessagesTransport.DebugOutputCallback callback) {
         this.api.SteamAPI_ISteamNetworkingUtils_SetDebugOutputFunction(this.utils, detailLevel, callback);
      }
   }

   interface NativeAccess {
      int send(Pointer var1, Pointer var2, int var3, int var4, int var5);

      int receive(int var1, Pointer[] var2, int var3);

      boolean accept(Pointer var1);

      void closeSession(Pointer var1);

      int getSessionConnectionInfo(Pointer var1, Pointer var2);

      void initializeRelayNetworkAccess();

      void releaseMessage(Pointer var1);

      void setCallbacks(SteamNetworkingMessagesTransport.SessionRequestCallback var1, SteamNetworkingMessagesTransport.SessionFailedCallback var2);

      void setDebugOutput(int var1, SteamNetworkingMessagesTransport.DebugOutputCallback var2);
   }

   static final class Received {
      private final long remoteSteamId;
      private final int size;

      Received(long remoteSteamId, int size) {
         this.remoteSteamId = remoteSteamId;
         this.size = size;
      }

      long remoteSteamId() {
         return this.remoteSteamId;
      }

      int size() {
         return this.size;
      }
   }

   interface SessionFailedCallback extends Callback {
      void invoke(Pointer var1);
   }

   interface SessionListener {
      void onSessionRequest(long var1);

      void onSessionFailed(long var1, int var3, String var4);
   }

   interface SessionRequestCallback extends Callback {
      void invoke(Pointer var1);
   }
}
