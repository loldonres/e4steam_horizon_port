package link.e4steam.steam;

import com.codedisaster.steamworks.SteamAuth;
import com.codedisaster.steamworks.SteamGameServer;
import com.codedisaster.steamworks.SteamGameServerAPI;
import com.codedisaster.steamworks.SteamGameServerCallback;
import com.codedisaster.steamworks.SteamID;
import com.codedisaster.steamworks.SteamNativeHandle;
import com.codedisaster.steamworks.SteamResult;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Set;
import java.util.Map.Entry;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor.AbortPolicy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import link.e4steam.E4steamConstants;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class SteamGameServerRuntimeBackend implements SteamRuntimeBackend {
   private static final Logger LOGGER = LogManager.getLogger("e4steam");
   private static final long START_TIMEOUT_MILLIS = 30000L;
   private static final long AUTH_TIMEOUT_MILLIS = 15000L;
   private static final int MAX_AUTH_TICKET_BYTES = 4096;
   private final SteamRuntimeBackend.StateListener stateListener;
   private final ThreadPoolExecutor control = new ThreadPoolExecutor(
      1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(32), daemonFactory("e4steam-gameserver-control"), new AbortPolicy()
   );
   private final AtomicReference<SteamRuntimeBackend.State> state = new AtomicReference<>(SteamRuntimeBackend.State.OFF);
   private final AtomicBoolean startRequested = new AtomicBoolean();
   private final AtomicBoolean stopRequested = new AtomicBoolean();
   private final ConcurrentHashMap<Long, SteamGameServerRuntimeBackend.PendingAuth> pendingAuth = new ConcurrentHashMap<>();
   private final Set<Long> authenticatedPeers = ConcurrentHashMap.newKeySet();
   private final CompletableFuture<SteamRuntimeBackend.RuntimeReady> readiness = new CompletableFuture<>();
   private volatile String failureCategory = "";
   private volatile SteamRuntimeBackend.Config config;
   private volatile SteamProcessGuard.Lease processLease;
   private volatile SteamNativeLibraryLoader nativeLoader;
   private volatile SteamGameServer gameServer;
   private volatile SteamNetworkingSocketsP2PTransport transport;
   private volatile Thread callbackThread;
   private volatile long startupDeadline;
   private volatile SteamGameServerRuntimeBackend.PeerListener peerListener = SteamGameServerRuntimeBackend.PeerListener.REJECT_ALL;

   public SteamGameServerRuntimeBackend(SteamRuntimeBackend.StateListener stateListener) {
      this.stateListener = stateListener == null ? (state, category) -> {} : stateListener;
   }

   @Override
   public SteamRuntimeBackend.RuntimeKind kind() {
      return SteamRuntimeBackend.RuntimeKind.DEDICATED_GAME_SERVER;
   }

   @Override
   public CompletionStage<SteamRuntimeBackend.RuntimeReady> start(SteamRuntimeBackend.Config requested) {
      if (requested == null) {
         throw new NullPointerException("config");
      } else if (!this.startRequested.compareAndSet(false, true)) {
         return this.readiness.thenApply(value -> (SteamRuntimeBackend.RuntimeReady)value);
      } else {
         this.config = requested;
         this.transition(SteamRuntimeBackend.State.CONFIG_VALIDATED, "");
         if (!this.submitControl(this::startInternal)) {
            this.fail("CONTROL_QUEUE_FULL", null);
         }

         return this.readiness.thenApply(value -> (SteamRuntimeBackend.RuntimeReady)value);
      }
   }

   @Override
   public SteamRuntimeBackend.Snapshot snapshot() {
      SteamProcessGuard.Lease lease = this.processLease;
      return new SteamRuntimeBackend.Snapshot(this.state.get(), lease == null ? 0L : lease.generation(), this.failureCategory);
   }

   @Override
   public CompletionStage<Void> stop(SteamRuntimeBackend.ShutdownReason reason) {
      CompletableFuture<Void> stopped = new CompletableFuture<>();
      if (this.stopRequested.compareAndSet(false, true)) {
         if (!this.submitControl(() -> {
            this.stopInternal(false);
            stopped.complete(null);
         })) {
            this.stopInternal(false);
            stopped.complete(null);
         }
      } else {
         stopped.complete(null);
      }

      return stopped.thenApply(value -> null);
   }

   public void peerListener(SteamGameServerRuntimeBackend.PeerListener listener) {
      this.peerListener = listener == null ? SteamGameServerRuntimeBackend.PeerListener.REJECT_ALL : listener;
   }

   public CompletionStage<Boolean> authenticate(long remoteSteamId, byte[] authTicket, long expectedGeneration) {
      if (remoteSteamId != 0L && authTicket != null && authTicket.length != 0 && authTicket.length <= 4096) {
         SteamProcessGuard.Lease lease = this.processLease;
         SteamGameServer server = this.gameServer;
         if (this.state.get() == SteamRuntimeBackend.State.TRANSPORT_READY && lease != null && lease.generation() == expectedGeneration && server != null) {
            if (this.pendingAuth.size() < this.config.maxPeers() * 2
               && this.authenticatedPeers.size() < this.config.maxPeers()
               && !this.authenticatedPeers.contains(remoteSteamId)) {
               SteamGameServerRuntimeBackend.PendingAuth pending = new SteamGameServerRuntimeBackend.PendingAuth(
                  expectedGeneration, System.currentTimeMillis() + 15000L
               );
               if (this.pendingAuth.putIfAbsent(remoteSteamId, pending) != null) {
                  return CompletableFuture.completedFuture(false);
               } else {
                  ByteBuffer direct = ByteBuffer.allocateDirect(authTicket.length);
                  direct.put(authTicket).flip();
                  Arrays.fill(authTicket, (byte)0);

                  try {
                     SteamAuth.BeginAuthSessionResult result = server.beginAuthSession(direct, SteamID.createFromNativeHandle(remoteSteamId));
                     zero(direct);
                     if (result != SteamAuth.BeginAuthSessionResult.OK) {
                        this.pendingAuth.remove(remoteSteamId, pending);
                        pending.result.complete(false);
                     }
                  } catch (LinkageError | Exception var11) {
                     zero(direct);
                     this.pendingAuth.remove(remoteSteamId, pending);
                     pending.result.complete(false);
                  }

                  return pending.result.thenApply(value -> (Boolean)value);
               }
            } else {
               return CompletableFuture.completedFuture(false);
            }
         } else {
            return CompletableFuture.completedFuture(false);
         }
      } else {
         return CompletableFuture.completedFuture(false);
      }
   }

   public void endAuthentication(long remoteSteamId) {
      SteamGameServerRuntimeBackend.PendingAuth pending = this.pendingAuth.remove(remoteSteamId);
      if (pending != null) {
         pending.result.complete(false);
      }

      boolean authenticated = this.authenticatedPeers.remove(remoteSteamId);
      if (pending != null || authenticated) {
         SteamGameServer server = this.gameServer;
         if (server != null) {
            try {
               server.endAuthSession(SteamID.createFromNativeHandle(remoteSteamId));
            } catch (RuntimeException var7) {
            }
         }
      }
   }

   public boolean isAuthenticated(long remoteSteamId, long expectedGeneration) {
      SteamProcessGuard.Lease lease = this.processLease;
      return lease != null && lease.generation() == expectedGeneration && this.authenticatedPeers.contains(remoteSteamId);
   }

   public boolean send(long remoteSteamId, ByteBuffer payload, boolean unreliable, int channel) throws IOException {
      SteamNetworkingSocketsP2PTransport active = this.requireTransport();
      return active.send(remoteSteamId, payload, unreliable);
   }

   public int availablePacketSize(int channel) throws IOException {
      return this.requireTransport().availablePacketSize();
   }

   public SteamGameServerRuntimeBackend.ReceivedPacket receive(ByteBuffer target, int channel) throws IOException {
      SteamNetworkingSocketsP2PTransport.Received received = this.requireTransport().receive(target);
      return new SteamGameServerRuntimeBackend.ReceivedPacket(received.remoteSteamId(), received.size());
   }

   public void closePeer(long remoteSteamId) {
      this.endAuthentication(remoteSteamId);
      SteamNetworkingSocketsP2PTransport active = this.transport;
      if (active != null) {
         active.closePeer(remoteSteamId);
      }
   }

   private void startInternal() {
      try {
         SteamRuntimeBackend.Config requested = this.config;
         if (!requested.anonymousLogin()) {
            throw new IOException("GSLT is not enabled for the App ID 480 backend");
         }

         ensureAppIdFile(requested.appId());
         this.processLease = SteamProcessGuard.acquire(SteamProcessGuard.Context.GAME_SERVER);
         this.nativeLoader = new SteamNativeLibraryLoader();
         if (!SteamGameServerAPI.loadLibraries(this.nativeLoader)) {
            throw new IOException("GameServer native binding could not be loaded");
         }

         this.transition(SteamRuntimeBackend.State.NATIVES_READY, "");
         this.transition(SteamRuntimeBackend.State.STEAM_INITIALIZING, "");
         if (!SteamGameServerAPI.init(
            0, (short)requested.gamePort(), (short)requested.queryPort(), SteamGameServerAPI.ServerMode.Authentication, E4steamConstants.modVersion()
         )) {
            throw new IOException("Steam GameServer initialization was rejected");
         }

         SteamGameServer created = new SteamGameServer(new SteamGameServerRuntimeBackend.Callback());
         this.gameServer = created;
         created.setProduct("e4steam");
         created.setGameDescription("e4steam Minecraft");
         created.setModDir("e4steam");
         created.setDedicatedServer(true);
         created.setMaxPlayerCount(requested.maxPeers());
         created.setBotPlayerCount(0);
         created.setServerName(requested.serverName());
         created.setMapName("minecraft");
         created.setPasswordProtected(false);
         created.setGameTags("private,e4steam");
         created.setAdvertiseServerActive(false);
         this.startupDeadline = System.currentTimeMillis() + 30000L;
         this.startCallbackThread();
         this.transition(SteamRuntimeBackend.State.STEAM_LOGGING_ON, "");
         created.logOnAnonymous();
      } catch (Throwable var3) {
         this.fail(category(var3, "GAMESERVER_START_FAILED"), var3);
      }
   }

   private void onSteamConnected() {
      if (!this.stopRequested.get() && this.state.get() == SteamRuntimeBackend.State.STEAM_LOGGING_ON) {
         try {
            SteamNativeLibraryLoader loader = this.nativeLoader;
            if (loader == null) {
               throw new IOException("Native loader is unavailable");
            }

            SteamNetworkingSocketsP2PTransport created = SteamNetworkingSocketsP2PTransport.openGameServer(
               loader.steamApiPath(), new SteamGameServerRuntimeBackend.TransportListener()
            );
            this.transport = created;
            SteamProcessGuard.Lease lease = this.processLease;
            long serverSteamId = SteamNativeHandle.getNativeHandle(SteamGameServerAPI.getSteamID());
            if (lease == null || serverSteamId == 0L) {
               throw new IOException("Steam GameServer identity is unavailable");
            }

            this.transition(SteamRuntimeBackend.State.TRANSPORT_READY, "");
            this.readiness.complete(new SteamRuntimeBackend.RuntimeReady(lease.generation(), serverSteamId));
         } catch (Throwable var6) {
            this.fail(category(var6, "TRANSPORT_START_FAILED"), var6);
         }
      }
   }

   private void startCallbackThread() {
      Thread thread = new Thread(() -> {
         while (!this.stopRequested.get()) {
            try {
               SteamGameServerAPI.runCallbacks();
               SteamNetworkingSocketsP2PTransport activeTransport = this.transport;
               if (activeTransport != null) {
                  activeTransport.runCallbacks();
               }

               this.expirePendingAuth();
               if (this.state.get() == SteamRuntimeBackend.State.STEAM_LOGGING_ON && System.currentTimeMillis() >= this.startupDeadline) {
                  this.submitControl(() -> this.fail("STEAM_LOGON_TIMEOUT", null));
                  return;
               }

               Thread.sleep(15L);
            } catch (InterruptedException var2) {
               Thread.currentThread().interrupt();
               return;
            } catch (Throwable var3) {
               this.submitControl(() -> this.fail("STEAM_CALLBACK_FAILED", var3));
               return;
            }
         }
      }, "e4steam-gameserver-callbacks");
      thread.setDaemon(true);
      this.callbackThread = thread;
      thread.start();
   }

   private void expirePendingAuth() {
      long now = System.currentTimeMillis();

      for (Entry<Long, SteamGameServerRuntimeBackend.PendingAuth> entry : this.pendingAuth.entrySet()) {
         SteamGameServerRuntimeBackend.PendingAuth pending = entry.getValue();
         if (now >= pending.deadline && this.pendingAuth.remove(entry.getKey(), pending)) {
            pending.result.complete(false);
            SteamGameServer server = this.gameServer;
            if (server != null) {
               try {
                  server.endAuthSession(SteamID.createFromNativeHandle(entry.getKey()));
               } catch (RuntimeException var8) {
               }
            }
         }
      }
   }

   private void validated(long steamId, SteamAuth.AuthSessionResponse response) {
      SteamGameServerRuntimeBackend.PendingAuth pending = this.pendingAuth.remove(steamId);
      if (pending != null) {
         SteamProcessGuard.Lease lease = this.processLease;
         boolean valid = response == SteamAuth.AuthSessionResponse.OK
            && lease != null
            && lease.generation() == pending.generation
            && this.state.get() == SteamRuntimeBackend.State.TRANSPORT_READY
            && this.authenticatedPeers.size() < this.config.maxPeers();
         if (valid) {
            this.authenticatedPeers.add(steamId);
         } else {
            SteamGameServer server = this.gameServer;
            if (server != null) {
               try {
                  server.endAuthSession(SteamID.createFromNativeHandle(steamId));
               } catch (RuntimeException var9) {
               }
            }
         }

         pending.result.complete(valid);
      }
   }

   private SteamNetworkingSocketsP2PTransport requireTransport() throws IOException {
      SteamNetworkingSocketsP2PTransport active = this.transport;
      if (active != null && this.state.get() == SteamRuntimeBackend.State.TRANSPORT_READY) {
         return active;
      } else {
         throw new IOException("Dedicated Steam transport is not ready");
      }
   }

   private void fail(String category, Throwable failure) {
      this.failureCategory = category == null ? "GAMESERVER_FAILED" : category;
      if (!this.readiness.isDone()) {
         this.readiness.completeExceptionally(new IOException(this.failureCategory));
      }

      this.stopRequested.set(true);
      this.stopInternal(true);
      this.transition(SteamRuntimeBackend.State.FAILED, this.failureCategory);
      if (failure != null) {
         LOGGER.warn("Dedicated Steam backend failed [{}]", this.failureCategory);
      }
   }

   private void stopInternal(boolean failed) {
      SteamRuntimeBackend.State current = this.state.get();
      if (!failed && current != SteamRuntimeBackend.State.OFF && current != SteamRuntimeBackend.State.STOPPED) {
         this.transition(SteamRuntimeBackend.State.DRAINING, "");
      }

      this.stopRequested.set(true);
      Thread callbacks = this.callbackThread;
      this.callbackThread = null;
      if (callbacks != null && callbacks != Thread.currentThread()) {
         callbacks.interrupt();
      }

      SteamNetworkingSocketsP2PTransport activeTransport = this.transport;
      this.transport = null;
      if (activeTransport != null) {
         try {
            activeTransport.close();
         } catch (RuntimeException var15) {
         }
      }

      SteamGameServer server = this.gameServer;
      this.gameServer = null;

      for (Long steamId : new ArrayList<>(this.authenticatedPeers)) {
         if (server != null) {
            try {
               server.endAuthSession(SteamID.createFromNativeHandle(steamId));
            } catch (RuntimeException var14) {
            }
         }
      }

      this.authenticatedPeers.clear();

      for (Entry<Long, SteamGameServerRuntimeBackend.PendingAuth> entry : this.pendingAuth.entrySet()) {
         entry.getValue().result.complete(false);
         if (server != null) {
            try {
               server.endAuthSession(SteamID.createFromNativeHandle(entry.getKey()));
            } catch (RuntimeException var13) {
            }
         }
      }

      this.pendingAuth.clear();
      if (server != null) {
         try {
            server.setAdvertiseServerActive(false);
         } catch (RuntimeException var12) {
         }

         try {
            server.logOff();
         } catch (RuntimeException var11) {
         }

         try {
            server.dispose();
         } catch (RuntimeException var10) {
         }
      }

      if (this.processLease != null) {
         try {
            SteamGameServerAPI.shutdown();
         } catch (RuntimeException var9) {
         }
      }

      SteamProcessGuard.Lease lease = this.processLease;
      this.processLease = null;
      if (lease != null) {
         lease.close();
      }

      if (!failed) {
         this.transition(SteamRuntimeBackend.State.STOPPED, "");
      }

      this.control.shutdownNow();
   }

   private void transition(SteamRuntimeBackend.State next, String category) {
      this.state.set(next);

      try {
         this.stateListener.onState(next, category == null ? "" : category);
      } catch (RuntimeException var4) {
      }
   }

   private boolean submitControl(Runnable action) {
      try {
         this.control.execute(action);
         return true;
      } catch (RejectedExecutionException var3) {
         return false;
      }
   }

   private static void ensureAppIdFile(int appId) throws IOException {
      Path path = Paths.get(System.getProperty("user.dir", "."), "steam_appid.txt").toAbsolutePath().normalize();
      if (Files.exists(path)) {
         String current = new String(Files.readAllBytes(path), StandardCharsets.US_ASCII).trim();
         if (!Integer.toString(appId).equals(current)) {
            throw new IOException("Existing steam_appid.txt contains another App ID");
         }
      } else {
         Files.write(
            path,
            (Integer.toString(appId) + System.lineSeparator()).getBytes(StandardCharsets.US_ASCII),
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE
         );
      }
   }

   private static void zero(ByteBuffer buffer) {
      if (buffer != null) {
         buffer.clear();

         while (buffer.hasRemaining()) {
            buffer.put((byte)0);
         }

         buffer.clear();
      }
   }

   private static String category(Throwable failure, String fallback) {
      if (failure instanceof UnsatisfiedLinkError || failure instanceof LinkageError) {
         return "NATIVE_BINDING_FAILED";
      } else if (failure instanceof SecurityException) {
         return "FILESYSTEM_POLICY_REJECTED";
      } else {
         return failure instanceof IOException ? fallback : "GAMESERVER_INTERNAL_FAILURE";
      }
   }

   private static ThreadFactory daemonFactory(String name) {
      return runnable -> {
         Thread thread = new Thread(runnable, name);
         thread.setDaemon(true);
         return thread;
      };
   }

   private final class Callback implements SteamGameServerCallback {
      @Override
      public void onValidateAuthTicketResponse(SteamID steamID, SteamAuth.AuthSessionResponse response, SteamID ownerSteamID) {
         long value = SteamNativeHandle.getNativeHandle(steamID);
         SteamGameServerRuntimeBackend.this.submitControl(() -> SteamGameServerRuntimeBackend.this.validated(value, response));
      }

      @Override
      public void onSteamServersConnected() {
         SteamGameServerRuntimeBackend.this.submitControl(SteamGameServerRuntimeBackend.this::onSteamConnected);
      }

      @Override
      public void onSteamServerConnectFailure(SteamResult result, boolean retrying) {
         if (!retrying) {
            SteamGameServerRuntimeBackend.this.submitControl(() -> SteamGameServerRuntimeBackend.this.fail("STEAM_LOGON_FAILED", null));
         }
      }

      @Override
      public void onSteamServersDisconnected(SteamResult result) {
         SteamGameServerRuntimeBackend.this.submitControl(() -> SteamGameServerRuntimeBackend.this.fail("STEAM_DISCONNECTED", null));
      }

      @Override
      public void onClientApprove(SteamID steamID, SteamID ownerSteamID) {
      }

      @Override
      public void onClientDeny(SteamID steamID, SteamGameServer.DenyReason reason, String optionalText) {
      }

      @Override
      public void onClientKick(SteamID steamID, SteamGameServer.DenyReason reason) {
      }

      @Override
      public void onClientGroupStatus(SteamID steamID, SteamID group, boolean member, boolean officer) {
      }

      @Override
      public void onAssociateWithClanResult(SteamResult result) {
      }

      @Override
      public void onComputeNewPlayerCompatibilityResult(
         SteamResult result, int playersThatDontLikeCandidate, int playersThatCandidateDoesntLike, int clanPlayersThatDontLikeCandidate, SteamID candidate
      ) {
      }
   }

   public interface PeerListener {
      SteamGameServerRuntimeBackend.PeerListener REJECT_ALL = new SteamGameServerRuntimeBackend.PeerListener() {
         @Override
         public boolean onSessionRequest(long remoteSteamId) {
            return false;
         }

         @Override
         public void onSessionFailed(long remoteSteamId, int reason, String detail) {
         }
      };

      boolean onSessionRequest(long var1);

      void onSessionFailed(long var1, int var3, String var4);
   }

   private static final class PendingAuth {
      private final long generation;
      private final long deadline;
      private final CompletableFuture<Boolean> result = new CompletableFuture<>();

      private PendingAuth(long generation, long deadline) {
         this.generation = generation;
         this.deadline = deadline;
      }
   }

   public static final class ReceivedPacket {
      private final long remoteSteamId;
      private final int size;

      private ReceivedPacket(long remoteSteamId, int size) {
         this.remoteSteamId = remoteSteamId;
         this.size = size;
      }

      public long remoteSteamId() {
         return this.remoteSteamId;
      }

      public int size() {
         return this.size;
      }
   }

   private final class TransportListener implements SteamNetworkingSocketsP2PTransport.SessionListener {
      @Override
      public void onSessionRequest(long remoteSteamId) {
         boolean allowed;
         try {
            allowed = SteamGameServerRuntimeBackend.this.state.get() == SteamRuntimeBackend.State.TRANSPORT_READY
               && SteamGameServerRuntimeBackend.this.peerListener.onSessionRequest(remoteSteamId);
         } catch (RuntimeException var6) {
            allowed = false;
         }

         SteamNetworkingSocketsP2PTransport active = SteamGameServerRuntimeBackend.this.transport;
         boolean accepted = active != null && allowed && active.accept(remoteSteamId);
         SteamGameServerRuntimeBackend.LOGGER
            .info(accepted ? "Accepted an incoming dedicated Steam transport session" : "Rejected an incoming dedicated Steam transport session");
         if (!accepted && active != null) {
            active.closePeer(remoteSteamId);
         }
      }

      @Override
      public void onSessionFailed(long remoteSteamId, int reason, String detail) {
         SteamGameServerRuntimeBackend.this.endAuthentication(remoteSteamId);

         try {
            SteamGameServerRuntimeBackend.this.peerListener.onSessionFailed(remoteSteamId, reason, "steam-session-failed");
         } catch (RuntimeException var6) {
         }
      }
   }
}
