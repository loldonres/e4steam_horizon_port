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

final class SteamNetworkingSocketsP2PTransport implements AutoCloseable {
   private static final Logger LOGGER = LogManager.getLogger("e4steam");
   private static final int VIRTUAL_PORT = 480;
   private static final int INVALID_HANDLE = 0;
   private static final int RESULT_OK = 1;
   private static final int RESULT_NO_CONNECTION = 3;
   private static final int SEND_NO_NAGLE = 1;
   private static final int SEND_NO_DELAY = 4;
   private static final int SEND_RELIABLE = 8;
   private static final int SEND_UNRELIABLE_NO_DELAY = 5;
   private static final int SEND_RELIABLE_NO_NAGLE = 9;
   private static final int STATE_CONNECTING = 1;
   private static final int STATE_FINDING_ROUTE = 2;
   private static final int STATE_CONNECTED = 3;
   private static final int STATE_CLOSED_BY_PEER = 4;
   private static final int STATE_PROBLEM_DETECTED_LOCALLY = 5;
   private static final int RECEIVE_BATCH_SIZE = 32;
   private static final long MESSAGE_DATA_OFFSET = 0L;
   private static final long MESSAGE_SIZE_OFFSET = 8L;
   private static final long MESSAGE_CONNECTION_OFFSET = 12L;
   private static final long MESSAGE_IDENTITY_OFFSET = 16L;
   private static final int CONNECTION_INFO_SIZE = 768;
   private static final long CONNECTION_INFO_STATE_OFFSET = 176L;
   private static final long CONNECTION_INFO_END_REASON_OFFSET = 180L;
   private static final long CONNECTION_INFO_END_DEBUG_OFFSET = 184L;
   private static final int CONNECTION_INFO_END_DEBUG_SIZE = 128;
   private static final int REAL_TIME_STATUS_SIZE = 128;
   private static final long STATUS_STATE_OFFSET = 0L;
   private static final long STATUS_PENDING_UNRELIABLE_OFFSET = 36L;
   private static final long STATUS_PENDING_RELIABLE_OFFSET = 40L;
   private static final long STATUS_SENT_UNACKED_RELIABLE_OFFSET = 44L;
   private final SteamNetworkingSocketsP2PTransport.FlatApi api;
   private final Pointer sockets;
   private final Pointer utils;
   private final SteamNetworkingSocketsP2PTransport.SessionListener listener;
   private final boolean gameServer;
   private final long clientRemoteSteamId;
   private final SteamNetworkingSocketsP2PTransport.ConnectionStatusCallback statusCallback = this::handleConnectionStatus;
   private final ArrayDeque<Pointer> pendingMessages = new ArrayDeque<>(32);
   private final Map<Long, Integer> connectionsByPeer = new HashMap<>();
   private final Map<Integer, Long> peersByConnection = new HashMap<>();
   private final Map<Long, Integer> pendingConnectionsByPeer = new HashMap<>();
   private final Memory connectionInfo = new Memory(768L);
   private final Memory realTimeStatus = new Memory(128L);
   private int listenSocket;
   private int pollGroup;
   private int clientConnection;
   private boolean closed;

   static SteamNetworkingSocketsP2PTransport openClient(Path steamApiLibrary, long remoteSteamId, SteamNetworkingSocketsP2PTransport.SessionListener listener) throws IOException {
      if (remoteSteamId == 0L) {
         throw new IOException("Dedicated Steam ID is invalid");
      } else {
         return new SteamNetworkingSocketsP2PTransport(steamApiLibrary, false, remoteSteamId, listener);
      }
   }

   static SteamNetworkingSocketsP2PTransport openGameServer(Path steamApiLibrary, SteamNetworkingSocketsP2PTransport.SessionListener listener) throws IOException {
      return new SteamNetworkingSocketsP2PTransport(steamApiLibrary, true, 0L, listener);
   }

   private SteamNetworkingSocketsP2PTransport(
      Path steamApiLibrary, boolean gameServer, long clientRemoteSteamId, SteamNetworkingSocketsP2PTransport.SessionListener listener
   ) throws IOException {
      Objects.requireNonNull(steamApiLibrary, "steamApiLibrary");
      this.gameServer = gameServer;
      this.clientRemoteSteamId = clientRemoteSteamId;
      this.listener = Objects.requireNonNull(listener, "listener");

      try {
         this.api = (SteamNetworkingSocketsP2PTransport.FlatApi)Native.loadLibrary(
            steamApiLibrary.toAbsolutePath().normalize().toString(), SteamNetworkingSocketsP2PTransport.FlatApi.class
         );
         this.sockets = gameServer
            ? this.api.SteamAPI_SteamGameServerNetworkingSockets_SteamAPI_v012()
            : this.api.SteamAPI_SteamNetworkingSockets_SteamAPI_v012();
         this.utils = this.api.SteamAPI_SteamNetworkingUtils_SteamAPI_v004();
      } catch (RuntimeException | UnsatisfiedLinkError var11) {
         throw new IOException("Could not bind Steam Networking Sockets", var11);
      }

      if (this.sockets != null && this.utils != null) {
         boolean initialized = false;

         try {
            this.api.SteamAPI_ISteamNetworkingUtils_SetGlobalCallback_SteamNetConnectionStatusChanged(this.utils, this.statusCallback);
            this.api.SteamAPI_ISteamNetworkingUtils_InitRelayNetworkAccess(this.utils);
            if (gameServer) {
               this.pollGroup = this.api.SteamAPI_ISteamNetworkingSockets_CreatePollGroup(this.sockets);
               if (this.pollGroup == 0) {
                  throw new IOException("Steam could not create a dedicated P2P poll group");
               }

               this.listenSocket = this.api.SteamAPI_ISteamNetworkingSockets_CreateListenSocketP2P(this.sockets, 480, 0, null);
               if (this.listenSocket == 0) {
                  throw new IOException("Steam could not create a dedicated P2P listen socket");
               }

               LOGGER.info("Dedicated Steam P2P listen socket is ready");
            } else {
               Memory identity = SteamNetworkingMessagesTransport.newIdentity(clientRemoteSteamId);
               this.clientConnection = this.api.SteamAPI_ISteamNetworkingSockets_ConnectP2P(this.sockets, identity, 480, 0, null);
               if (this.clientConnection == 0) {
                  throw new IOException("Steam rejected the dedicated P2P connection request");
               }

               this.connectionsByPeer.put(clientRemoteSteamId, this.clientConnection);
               this.peersByConnection.put(this.clientConnection, clientRemoteSteamId);
               LOGGER.info("Started a dedicated Steam P2P connection");
            }

            initialized = true;
         } finally {
            if (!initialized) {
               this.close();
            }
         }
      } else {
         throw new IOException("Steam Networking Sockets is unavailable after Steam initialization");
      }
   }

   synchronized boolean accept(long remoteSteamId) {
      if (!this.closed && this.gameServer) {
         Integer connection = this.pendingConnectionsByPeer.remove(remoteSteamId);
         if (connection == null) {
            return this.connectionsByPeer.containsKey(remoteSteamId);
         } else {
            int result = this.api.SteamAPI_ISteamNetworkingSockets_AcceptConnection(this.sockets, connection);
            if (result != 1) {
               this.closeConnection(connection, "e4steam rejected an invalid pending connection");
               return false;
            } else if (this.api.SteamAPI_ISteamNetworkingSockets_SetConnectionPollGroup(this.sockets, connection, this.pollGroup) == 0) {
               this.closeConnection(connection, "e4steam could not assign the connection poll group");
               return false;
            } else {
               Integer previous = this.connectionsByPeer.put(remoteSteamId, connection);
               this.peersByConnection.put(connection, remoteSteamId);
               if (previous != null && previous != connection) {
                  this.peersByConnection.remove(previous);
                  this.closeConnection(previous, "e4steam replaced a stale connection");
               }

               return true;
            }
         }
      } else {
         return false;
      }
   }

   synchronized int sendResult(long remoteSteamId, ByteBuffer payload, boolean unreliable) throws IOException {
      this.ensureOpen();
      Integer connection = this.connectionsByPeer.get(remoteSteamId);
      if (connection == null) {
         return 3;
      } else if (!payload.isDirect()) {
         throw new IOException("Steam Networking Sockets requires a direct send buffer");
      } else {
         Pointer data = Native.getDirectBufferPointer(payload);
         if (data == null) {
            throw new IOException("Could not access the Steam send buffer");
         } else {
            data = data.share(payload.position());
            int flags = unreliable ? 5 : 9;
            return this.api.SteamAPI_ISteamNetworkingSockets_SendMessageToConnection(this.sockets, connection, data, payload.remaining(), flags, null);
         }
      }
   }

   synchronized boolean send(long remoteSteamId, ByteBuffer payload, boolean unreliable) throws IOException {
      return this.sendResult(remoteSteamId, payload, unreliable) == 1;
   }

   synchronized int availablePacketSize() throws IOException {
      this.ensureOpen();
      if (this.pendingMessages.isEmpty()) {
         Pointer[] messages = new Pointer[32];
         int count;
         if (this.gameServer) {
            count = this.api.SteamAPI_ISteamNetworkingSockets_ReceiveMessagesOnPollGroup(this.sockets, this.pollGroup, messages, 32);
         } else {
            count = this.api.SteamAPI_ISteamNetworkingSockets_ReceiveMessagesOnConnection(this.sockets, this.clientConnection, messages, 32);
         }

         if (count < 0 || count > 32) {
            throw new IOException("Steam returned an invalid P2P message count: " + count);
         }

         for (int index = 0; index < count; index++) {
            if (messages[index] == null) {
               this.releasePendingMessages();
               throw new IOException("Steam returned a null P2P message");
            }

            this.pendingMessages.addLast(messages[index]);
         }
      }

      Pointer message = this.pendingMessages.peekFirst();
      return message == null ? 0 : message.getInt(8L);
   }

   synchronized SteamNetworkingSocketsP2PTransport.Received receive(ByteBuffer target) throws IOException {
      this.ensureOpen();
      Pointer message = this.pendingMessages.pollFirst();
      if (message == null) {
         throw new IOException("No dedicated Steam packet is available");
      } else {
         SteamNetworkingSocketsP2PTransport.Received var12;
         try {
            int size = message.getInt(8L);
            if (size < 0) {
               throw new IOException("Steam returned a negative P2P message size");
            }

            if (size > target.remaining()) {
               throw new BufferOverflowException();
            }

            Pointer data = message.getPointer(0L);
            if (size > 0 && data == null) {
               throw new IOException("Steam returned a null P2P payload");
            }

            if (size > 0) {
               target.put(data.getByteBuffer(0L, size));
            }

            long remoteSteamId = SteamNetworkingMessagesTransport.readSteamId(message.share(16L));
            if (remoteSteamId == 0L) {
               int connection = message.getInt(12L);
               Long mapped = this.peersByConnection.get(connection);
               remoteSteamId = mapped == null ? this.clientRemoteSteamId : mapped;
            }

            var12 = new SteamNetworkingSocketsP2PTransport.Received(remoteSteamId, size);
         } finally {
            this.api.SteamAPI_SteamNetworkingMessage_t_Release(message);
         }

         return var12;
      }
   }

   synchronized void discardPendingMessage() {
      Pointer message = this.pendingMessages.pollFirst();
      if (message != null) {
         this.api.SteamAPI_SteamNetworkingMessage_t_Release(message);
      }
   }

   synchronized boolean hasQueuedPackets(long remoteSteamId) {
      if (this.closed) {
         return false;
      } else {
         Integer connection = this.connectionsByPeer.get(remoteSteamId);
         if (connection == null) {
            return false;
         } else {
            this.realTimeStatus.clear();
            int result = this.api.SteamAPI_ISteamNetworkingSockets_GetConnectionRealTimeStatus(this.sockets, connection, this.realTimeStatus, 0, null);
            if (result != 1) {
               return false;
            } else {
               int state = this.realTimeStatus.getInt(0L);
               return state == 1
                  || state == 2
                  || this.realTimeStatus.getInt(36L) > 0
                  || this.realTimeStatus.getInt(40L) > 0
                  || this.realTimeStatus.getInt(44L) > 0;
            }
         }
      }
   }

   synchronized void runCallbacks() {
      if (!this.closed) {
         this.api.SteamAPI_ISteamNetworkingSockets_RunCallbacks(this.sockets);
         if (!this.gameServer && this.clientConnection != 0) {
            this.connectionInfo.clear();
            if (this.api.SteamAPI_ISteamNetworkingSockets_GetConnectionInfo(this.sockets, this.clientConnection, this.connectionInfo) != 0) {
               int state = this.connectionInfo.getInt(176L);
               if (state == 4 || state == 5 || state < 0) {
                  int connection = this.clientConnection;
                  long remoteSteamId = SteamNetworkingMessagesTransport.readSteamId(this.connectionInfo);
                  if (remoteSteamId == 0L) {
                     remoteSteamId = this.clientRemoteSteamId;
                  }

                  int reason = this.connectionInfo.getInt(180L);
                  String detail = readFixedString(this.connectionInfo, 184L, 128);
                  this.forgetConnection(connection, remoteSteamId);

                  try {
                     this.listener.onSessionFailed(remoteSteamId, reason, detail);
                  } catch (Throwable var8) {
                  }

                  this.closeConnection(connection, "e4steam released a failed connection");
               }
            }
         }
      }
   }

   synchronized void closePeer(long remoteSteamId) {
      Integer connection = this.connectionsByPeer.remove(remoteSteamId);
      Integer pending = this.pendingConnectionsByPeer.remove(remoteSteamId);
      if (connection != null) {
         this.peersByConnection.remove(connection);
         this.closeConnection(connection, "e4steam peer session closed");
      }

      if (pending != null && !pending.equals(connection)) {
         this.peersByConnection.remove(pending);
         this.closeConnection(pending, "e4steam pending session rejected");
      }

      if (!this.gameServer && remoteSteamId == this.clientRemoteSteamId) {
         this.clientConnection = 0;
      }
   }

   @Override
   public synchronized void close() {
      if (!this.closed) {
         this.closed = true;
         if (this.api != null && this.sockets != null && this.utils != null) {
            try {
               this.api.SteamAPI_ISteamNetworkingUtils_SetGlobalCallback_SteamNetConnectionStatusChanged(this.utils, null);
            } catch (Throwable var7) {
            }

            this.releasePendingMessages();

            for (Integer connection : this.peersByConnection.keySet().toArray(new Integer[0])) {
               this.closeConnection(connection, "e4steam transport stopped");
            }

            this.peersByConnection.clear();
            this.connectionsByPeer.clear();
            this.pendingConnectionsByPeer.clear();
            if (this.listenSocket != 0) {
               try {
                  this.api.SteamAPI_ISteamNetworkingSockets_CloseListenSocket(this.sockets, this.listenSocket);
               } catch (Throwable var6) {
               }

               this.listenSocket = 0;
            }

            if (this.pollGroup != 0) {
               try {
                  this.api.SteamAPI_ISteamNetworkingSockets_DestroyPollGroup(this.sockets, this.pollGroup);
               } catch (Throwable var5) {
               }

               this.pollGroup = 0;
            }

            this.clientConnection = 0;
         }
      }
   }

   private synchronized void handleConnectionStatus(Pointer callback) {
      if (!this.closed && callback != null) {
         int connection = callback.getInt(0L);
         if (connection != 0) {
            this.connectionInfo.clear();
            if (this.api.SteamAPI_ISteamNetworkingSockets_GetConnectionInfo(this.sockets, connection, this.connectionInfo) != 0) {
               long remoteSteamId = SteamNetworkingMessagesTransport.readSteamId(this.connectionInfo);
               if (remoteSteamId == 0L && !this.gameServer) {
                  remoteSteamId = this.clientRemoteSteamId;
               }

               int state = this.connectionInfo.getInt(176L);
               if (this.gameServer && state == 1 && remoteSteamId != 0L && !this.connectionsByPeer.containsKey(remoteSteamId)) {
                  this.pendingConnectionsByPeer.put(remoteSteamId, connection);
                  this.peersByConnection.put(connection, remoteSteamId);

                  try {
                     this.listener.onSessionRequest(remoteSteamId);
                  } catch (Throwable var9) {
                     this.pendingConnectionsByPeer.remove(remoteSteamId);
                     this.peersByConnection.remove(connection);
                     this.closeConnection(connection, "e4steam admission callback failed");
                  }
               } else if (state == 3) {
                  if (remoteSteamId != 0L) {
                     this.connectionsByPeer.put(remoteSteamId, connection);
                     this.peersByConnection.put(connection, remoteSteamId);
                  }

                  LOGGER.info(this.gameServer ? "Dedicated Steam P2P peer connected" : "Connected to the dedicated Steam P2P server");
               } else {
                  if (state == 4 || state == 5 || state < 0) {
                     int reason = this.connectionInfo.getInt(180L);
                     String detail = readFixedString(this.connectionInfo, 184L, 128);
                     this.forgetConnection(connection, remoteSteamId);

                     try {
                        this.listener.onSessionFailed(remoteSteamId, reason, detail);
                     } catch (Throwable var10) {
                     }

                     this.closeConnection(connection, "e4steam released a failed connection");
                  }
               }
            }
         }
      }
   }

   private void forgetConnection(int connection, long remoteSteamId) {
      this.peersByConnection.remove(connection);
      if (remoteSteamId != 0L) {
         this.connectionsByPeer.remove(remoteSteamId, connection);
         this.pendingConnectionsByPeer.remove(remoteSteamId, connection);
      }

      if (!this.gameServer && this.clientConnection == connection) {
         this.clientConnection = 0;
      }
   }

   private void closeConnection(int connection, String detail) {
      if (connection != 0) {
         try {
            this.api.SteamAPI_ISteamNetworkingSockets_CloseConnection(this.sockets, connection, 0, detail, (byte)0);
         } catch (Throwable var4) {
         }
      }
   }

   private void releasePendingMessages() {
      Pointer message;
      while ((message = this.pendingMessages.pollFirst()) != null) {
         try {
            this.api.SteamAPI_SteamNetworkingMessage_t_Release(message);
         } catch (Throwable var3) {
         }
      }
   }

   private void ensureOpen() throws IOException {
      if (this.closed) {
         throw new IOException("Dedicated Steam P2P transport is closed");
      }
   }

   private static String readFixedString(Pointer source, long offset, int maximumSize) {
      byte[] bytes = source.getByteArray(offset, maximumSize);
      int length = 0;

      while (length < bytes.length && bytes[length] != 0) {
         length++;
      }

      return new String(bytes, 0, length, StandardCharsets.UTF_8);
   }

   interface ConnectionStatusCallback extends Callback {
      void invoke(Pointer var1);
   }

   private interface FlatApi extends Library {
      Pointer SteamAPI_SteamNetworkingSockets_SteamAPI_v012();

      Pointer SteamAPI_SteamGameServerNetworkingSockets_SteamAPI_v012();

      Pointer SteamAPI_SteamNetworkingUtils_SteamAPI_v004();

      int SteamAPI_ISteamNetworkingSockets_CreateListenSocketP2P(Pointer var1, int var2, int var3, Pointer var4);

      int SteamAPI_ISteamNetworkingSockets_ConnectP2P(Pointer var1, Pointer var2, int var3, int var4, Pointer var5);

      int SteamAPI_ISteamNetworkingSockets_AcceptConnection(Pointer var1, int var2);

      byte SteamAPI_ISteamNetworkingSockets_CloseConnection(Pointer var1, int var2, int var3, String var4, byte var5);

      byte SteamAPI_ISteamNetworkingSockets_CloseListenSocket(Pointer var1, int var2);

      int SteamAPI_ISteamNetworkingSockets_CreatePollGroup(Pointer var1);

      byte SteamAPI_ISteamNetworkingSockets_DestroyPollGroup(Pointer var1, int var2);

      byte SteamAPI_ISteamNetworkingSockets_SetConnectionPollGroup(Pointer var1, int var2, int var3);

      int SteamAPI_ISteamNetworkingSockets_SendMessageToConnection(Pointer var1, int var2, Pointer var3, int var4, int var5, Pointer var6);

      int SteamAPI_ISteamNetworkingSockets_ReceiveMessagesOnConnection(Pointer var1, int var2, Pointer[] var3, int var4);

      int SteamAPI_ISteamNetworkingSockets_ReceiveMessagesOnPollGroup(Pointer var1, int var2, Pointer[] var3, int var4);

      byte SteamAPI_ISteamNetworkingSockets_GetConnectionInfo(Pointer var1, int var2, Pointer var3);

      int SteamAPI_ISteamNetworkingSockets_GetConnectionRealTimeStatus(Pointer var1, int var2, Pointer var3, int var4, Pointer var5);

      void SteamAPI_ISteamNetworkingSockets_RunCallbacks(Pointer var1);

      void SteamAPI_ISteamNetworkingUtils_InitRelayNetworkAccess(Pointer var1);

      void SteamAPI_ISteamNetworkingUtils_SetGlobalCallback_SteamNetConnectionStatusChanged(
         Pointer var1, SteamNetworkingSocketsP2PTransport.ConnectionStatusCallback var2
      );

      void SteamAPI_SteamNetworkingMessage_t_Release(Pointer var1);
   }

   static final class Received {
      private final long remoteSteamId;
      private final int size;

      private Received(long remoteSteamId, int size) {
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

   interface SessionListener {
      void onSessionRequest(long var1);

      void onSessionFailed(long var1, int var3, String var4);
   }
}
