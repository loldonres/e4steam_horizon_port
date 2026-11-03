package link.e4steam.steam;

import com.codedisaster.steamworks.SteamAuthTicket;
import com.codedisaster.steamworks.SteamID;
import com.codedisaster.steamworks.SteamNativeHandle;
import com.codedisaster.steamworks.SteamResult;
import com.codedisaster.steamworks.SteamUser;
import com.codedisaster.steamworks.SteamUserCallback;
import com.codedisaster.steamworks.SteamUtils;
import com.codedisaster.steamworks.SteamUtilsCallback;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import link.e4steam.Agnos;
import link.e4steam.E4steamClient;
import link.e4steam.api.ApiResult;
import link.e4steam.api.lobby.LobbyService;
import link.e4steam.lan.LanSettings;

public final class SteamRuntime implements SteamBridgeRuntime {
   private static final int APP_ID = 480;
   private static final int CHANNEL = 480;
   private static final int MAX_OUTBOUND_PACKETS = 2048;
   private static final int MAX_OUTBOUND_DATA_PACKETS = 1344;
   private static final int MAX_OUTBOUND_DATAGRAM_PACKETS = 320;
   private static final int MAX_OUTBOUND_ADDON_PACKETS = 128;
   private static final int MAX_OUTBOUND_OPEN_PACKETS = 64;
   private static final int MAX_OUTBOUND_STANDALONE_RESETS = 64;
   private static final int MAX_PACKETS_PER_TICK = 32;
   private static final int MAX_RECEIVED_DATA_BYTES_PER_TICK = 131072;
   private static final int MAX_STEAM_PENDING_RELIABLE_BYTES = 262144;
   private static final long MAX_STEAM_RELIABLE_QUEUE_MICROS = 250000L;
   private static final long FORGE_RELIABLE_DATA_BYTES_PER_SECOND = 4194304L;
   private static final int FORGE_RELIABLE_DATA_BURST_BYTES = 65536;
   private static final long OUTBOUND_SEND_RETRY_DELAY_MILLIS = 25L;
   private static final long OUTBOUND_SEND_RETRY_TIMEOUT_MILLIS = 30000L;
   private static final int MAX_RESET_RETRIES = 64;
   private static final int MAX_RESET_RETRY_ATTEMPTS = 10;
   private static final long MAX_RESET_RETRY_AGE_MILLIS = 30000L;
   private static final long RESET_RETRY_BASE_DELAY_MILLIS = 25L;
   private static final long RESET_RETRY_MAX_DELAY_MILLIS = 2000L;
   private static final int MAX_RESET_RETRIES_PER_TICK = 8;
   private static final int MAX_ACTIVE_CONNECTIONS = 64;
   private static final int MAX_PENDING_PEERS = 64;
   private static final long PENDING_PEER_TIMEOUT_MILLIS = 10000L;
   private static final long IDLE_SESSION_CLOSE_DELAY_MILLIS = 250L;
   private static final long IDLE_SESSION_RECHECK_MILLIS = 100L;
   private static final long IDLE_SESSION_MAX_DRAIN_MILLIS = 2000L;
   private static final int LOOPBACK_CONNECT_TIMEOUT_MILLIS = 100;
   private static final long LOOPBACK_FAILURE_BACKOFF_MILLIS = 2000L;
   private static final long BRIDGE_READY_FALLBACK_MILLIS = 2000L;
   private static final long CLIENT_RECONNECT_GRACE_MILLIS = 3000L;
   private static final long CLIENT_OPEN_ACK_FALLBACK_MILLIS = 10000L;
   private static final long KNOWN_PEER_ACCEPT_INTERVAL_MILLIS = 100L;
   private static final long KNOWN_PEER_REACCEPT_DELAY_MILLIS = Math.max(3000L, 2100L);
   private static final Duration START_TIMEOUT = Duration.ofSeconds(30L);
   private static final Duration STEAM_TASK_TIMEOUT = Duration.ofSeconds(10L);
   private static final long RUNTIME_IDLE_SHUTDOWN_MILLIS = 1000L;
   private static final SteamRuntime INSTANCE = new SteamRuntime(new SteamworksApi(), true);
   private final Object lifecycleLock = new Object();
   private final Object peerSessionLock = new Object();
   private final SteamLifecycle steamLifecycle;
   private final SteamOutboundQueue<SteamConnectionBridge> outbound = new SteamOutboundQueue<>(2048, 1344, 320, 128, 64, 64);
   private final SteamResetRetryQueue<SteamConnectionBridge> resetRetries = new SteamResetRetryQueue<>(64, 10, 30000L, 25L, 2000L);
   private final SteamBridgeRegistry<SteamConnectionBridge, SteamUdpBridge> bridgeRegistry = new SteamBridgeRegistry<>(64);
   private final ConcurrentHashMap<Long, Long> pendingPeers = new ConcurrentHashMap<>();
   private final ConcurrentHashMap<Long, Long> clientReconnectDeadlines = new ConcurrentHashMap<>();
   private final Set<SteamBridgeRegistry.Key> pendingCustomAccessEvaluations = ConcurrentHashMap.newKeySet();
   private final SteamPublicJoinTracker publicJoinTracker = new SteamPublicJoinTracker();
   private final SteamKnownPeerSessionGate knownPeerSessionGate = new SteamKnownPeerSessionGate(KNOWN_PEER_REACCEPT_DELAY_MILLIS);
   private final ConcurrentHashMap<Integer, SteamRuntime.AuthenticatedLoopbackPeer> authenticatedLoopbackPeers = new ConcurrentHashMap<>();
   private final ConcurrentHashMap<Long, long[]> idleSessionDeadlines = new ConcurrentHashMap<>();
   private final ConcurrentLinkedQueue<SteamRuntime.SteamTask<?>> steamTasks = new ConcurrentLinkedQueue<>();
   private final SteamReliableDataPacer<SteamConnectionBridge> reliableDataPacer = new SteamReliableDataPacer<>(4194304L, 65536);
   private SteamOutboundQueue.Packet<SteamConnectionBridge> retryOutboundPacket;
   private long retryOutboundNotBeforeMillis;
   private long retryOutboundDeadlineMillis;
   private volatile SteamRuntime.Status status = SteamRuntime.Status.NEW;
   private volatile Throwable failureCause;
   private volatile long localSteamId;
   private volatile Thread workerThread;
   private volatile SteamRuntime.WorkerGeneration generation;
   private volatile SteamNetworkingMessagesTransport transport;
   private volatile SteamNetworkingSocketsP2PTransport dedicatedTransport;
   private volatile SteamUser user;
   private volatile SteamUtils utils;
   private volatile SteamLobbyManager lobbyManager;
   private volatile SteamAddonLobbyDirectory addonLobbyDirectory;
   private volatile SteamRuntime.HostRegistration hostRegistration;
   private volatile long nextLoopbackConnectAttemptAtMillis;
   private long nextKnownPeerAcceptAtMillis;
   private boolean permanentlyShutdown;
   private int activityCount;
   private long nextWorkerGenerationId;
   private final AtomicBoolean launchStartRequested = new AtomicBoolean();
   private volatile SteamRuntime.Activity launchActivity;

   SteamRuntime(SteamApi api, boolean installShutdownHook) {
      this.steamLifecycle = new SteamLifecycle(api);
      if (installShutdownHook) {
         Thread shutdownHook = new Thread(this::shutdown, "e4steam-steam-shutdown");
         Runtime.getRuntime().addShutdownHook(shutdownHook);
      }
   }

   public static SteamRuntime get() {
      return INSTANCE;
   }

   public void startAtGameLaunchAsync() {
      if (Agnos.isClient() && this.launchStartRequested.compareAndSet(false, true)) {
         Thread starter = new Thread(() -> {
            SteamRuntime.Activity activity = null;

            try {
               activity = this.acquireActivity();
               this.awaitReady();
               synchronized (this.lifecycleLock) {
                  if (this.status != SteamRuntime.Status.RUNNING || !this.launchStartRequested.get()) {
                     throw new IOException("Steam stopped during automatic startup");
                  }

                  this.launchActivity = activity;
               }

               E4steamClient.LOGGER.info("Steam/Spacewar started with Minecraft");
            } catch (Throwable var5) {
               if (activity != null) {
                  activity.close();
               }

               this.launchStartRequested.set(false);
               E4steamClient.LOGGER.warn("Steam was not available at Minecraft startup; e4steam can retry later", var5);
            }
         }, "e4steam-steam-launch-start");
         starter.setDaemon(true);
         starter.start();
      }
   }

   public static void relaunchForOverlayIfNeeded() {
      SteamOverlayRelauncher.relaunchIfNeeded();
   }

   public static void preloadCompatibilityClasses() {
      List<String> names = new ArrayList<>();
      ClassLoader loader = SteamRuntime.class.getClassLoader();

      try (InputStream input = loader.getResourceAsStream("e4steam-retro-preload.txt")) {
         if (input != null) {
            String name;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
               while ((name = reader.readLine()) != null) {
                  name = name.trim();
                  if (!name.isEmpty()) {
                     names.add(name);
                  }
               }
            }
         }
      } catch (IOException var11) {
         E4steamClient.LOGGER.warn("Could not read the retro compatibility preload list", var11);
      }

      Collections.addAll(
         names,
         "com.codedisaster.steamworks.SteamAPICall",
         "com.codedisaster.steamworks.SteamException",
         "com.codedisaster.steamworks.SteamFriends",
         "com.codedisaster.steamworks.SteamFriends$FriendFlags",
         "com.codedisaster.steamworks.SteamFriends$FriendGameInfo",
         "com.codedisaster.steamworks.SteamFriends$FriendRelationship",
         "com.codedisaster.steamworks.SteamFriends$OverlayDialog",
         "com.codedisaster.steamworks.SteamFriends$OverlayToStoreFlag",
         "com.codedisaster.steamworks.SteamFriends$OverlayToUserDialog",
         "com.codedisaster.steamworks.SteamFriends$OverlayToWebPageMode",
         "com.codedisaster.steamworks.SteamFriends$PersonaChange",
         "com.codedisaster.steamworks.SteamFriends$PersonaState",
         "com.codedisaster.steamworks.SteamFriendsCallback",
         "com.codedisaster.steamworks.SteamFriendsCallbackAdapter",
         "com.codedisaster.steamworks.SteamFriendsNative",
         "com.codedisaster.steamworks.SteamID",
         "com.codedisaster.steamworks.SteamMatchmaking",
         "com.codedisaster.steamworks.SteamMatchmaking$ChatEntry",
         "com.codedisaster.steamworks.SteamMatchmaking$ChatEntryType",
         "com.codedisaster.steamworks.SteamMatchmaking$ChatMemberStateChange",
         "com.codedisaster.steamworks.SteamMatchmaking$ChatRoomEnterResponse",
         "com.codedisaster.steamworks.SteamMatchmaking$LobbyComparison",
         "com.codedisaster.steamworks.SteamMatchmaking$LobbyDistanceFilter",
         "com.codedisaster.steamworks.SteamMatchmaking$LobbyType",
         "com.codedisaster.steamworks.SteamMatchmakingCallback",
         "com.codedisaster.steamworks.SteamMatchmakingCallbackAdapter",
         "com.codedisaster.steamworks.SteamMatchmakingGameServerItem",
         "com.codedisaster.steamworks.SteamMatchmakingKeyValuePair",
         "com.codedisaster.steamworks.SteamMatchmakingNative",
         "com.codedisaster.steamworks.SteamMatchmakingPingResponse",
         "com.codedisaster.steamworks.SteamMatchmakingPlayersResponse",
         "com.codedisaster.steamworks.SteamMatchmakingRulesResponse",
         "com.codedisaster.steamworks.SteamMatchmakingServerListResponse",
         "com.codedisaster.steamworks.SteamMatchmakingServerListResponse$Response",
         "com.codedisaster.steamworks.SteamMatchmakingServerNetAdr",
         "com.codedisaster.steamworks.SteamMatchmakingServers",
         "com.codedisaster.steamworks.SteamMatchmakingServersNative"
      );

      for (String name : names) {
         try {
            Class.forName(name, false, loader);
         } catch (LinkageError | ClassNotFoundException var6) {
            E4steamClient.LOGGER.warn("Could not preload Steam compatibility class {}", name, var6);
         }
      }
   }

   public SteamRuntime.Activity acquireActivity() {
      synchronized (this.lifecycleLock) {
         if (this.permanentlyShutdown) {
            throw new IllegalStateException("Steam runtime has been shut down");
         } else {
            this.activityCount++;
            SteamRuntime.WorkerGeneration current = this.generation;
            if (current != null) {
               current.idleSinceMillis = 0L;
            }

            return new SteamRuntime.Activity(this);
         }
      }
   }

   public void awaitReady() throws IOException {
      if (!Agnos.isClient()) {
         throw new IOException("This e4steam release supports integrated LAN worlds only");
      } else {
         SteamRuntime.WorkerGeneration target = this.ensureWorkerStarted();

         try {
            target.ready.get(START_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            synchronized (this.lifecycleLock) {
               if (this.generation != target || target.stopRequested.get() || this.status != SteamRuntime.Status.RUNNING) {
                  throw new IOException("Steam runtime stopped before it became usable (status: " + this.status + ")");
               }
            }
         } catch (TimeoutException var5) {
            throw new IOException("Timed out while initializing Steam", var5);
         } catch (InterruptedException var6) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while initializing Steam", var6);
         } catch (ExecutionException var7) {
            Throwable cause = (Throwable)(var7.getCause() == null ? var7 : var7.getCause());
            throw new IOException("Steam initialization failed: " + cause.getMessage(), cause);
         }

         if (!this.launchStartRequested.get()) {
            this.startAtGameLaunchAsync();
         }
      }
   }

   public String statusSummary() {
      String summary = this.status.name().toLowerCase();
      if (this.status == SteamRuntime.Status.RUNNING) {
         summary = summary + " (Steam client connected as " + this.steamId() + ")";
      }

      return summary;
   }

   public String safeStatusCode() {
      return this.status.name();
   }

   public String safeFailureCategory() {
      Throwable failure = this.failureCause;
      if (failure == null) {
         return "";
      } else {
         String category = failure.getClass().getSimpleName();
         return category.matches("[A-Za-z0-9_$.-]{1,64}") ? category : "SteamFailure";
      }
   }

   public SteamRuntime.SafeSessionView safeSessionView() {
      SteamRuntime.WorkerGeneration current = this.generation;
      SteamRuntime.HostRegistration hosting = this.hostRegistration;
      Collection<SteamConnectionBridge> bridges = this.bridgeRegistry.snapshot();
      if (current != null && (hosting != null || !bridges.isEmpty())) {
         long generationId = current.id;
         String role = hosting != null ? "INTEGRATED_HOST" : "GUEST";
         SteamConnectionBridge dedicatedBridge = null;
         if (hosting == null) {
            for (SteamConnectionBridge candidate : bridges) {
               if (candidate != null && !candidate.isClosed() && candidate.dedicatedSessionGeneration() > 0L) {
                  dedicatedBridge = candidate;
                  generationId = candidate.dedicatedSessionGeneration();
                  role = "DEDICATED_SERVER_CLIENT";
                  break;
               }
            }
         }
         String var15 = switch (this.status) {
            case STARTING -> "CREATING";
            case RUNNING -> "ACTIVE";
            case STOPPING -> "CLOSING";
            case FAILED -> "FAILED";
            case STOPPED -> "CLOSED";
            default -> "NONE";
         };
         LinkedHashMap<String, SteamRuntime.SafePeerIdentity> peers = new LinkedHashMap<>();

         for (SteamConnectionBridge bridge : bridges) {
            if (bridge != null && !bridge.isClosed() && (dedicatedBridge == null || bridge == dedicatedBridge)) {
               long remote = bridge.remoteSteamId();
               String opaque = opaquePeerId(generationId, remote);
               peers.putIfAbsent(
                  opaque, new SteamRuntime.SafePeerIdentity(opaque, SteamMinecraftIdentity.uuid(remote), SteamMinecraftIdentity.safeName(remote))
               );
            }
         }

         ArrayList<SteamRuntime.SafePeerIdentity> ordered = new ArrayList<>(peers.values());
         ordered.sort(Comparator.comparing(SteamRuntime.SafePeerIdentity::opaquePeerId));
         if (this.generation != current) {
            return SteamRuntime.SafeSessionView.inactive();
         } else {
            String sessionId = dedicatedBridge == null ? "session_" + Long.toUnsignedString(generationId, 36) : dedicatedSessionId(generationId);
            return new SteamRuntime.SafeSessionView(sessionId, generationId, role, var15, LanSettings.steamLobbyCapacity(), ordered);
         }
      } else {
         return SteamRuntime.SafeSessionView.inactive();
      }
   }

   public SteamRuntime.SafeMinecraftIdentity safeLocalMinecraftIdentity() {
      long id = this.localSteamId;
      return id == 0L ? null : new SteamRuntime.SafeMinecraftIdentity(SteamMinecraftIdentity.uuid(id), SteamMinecraftIdentity.safeName(id));
   }

   public SteamRuntime.SafePeerIdentity safeResolvePeer(String opaquePeerId) {
      if (opaquePeerId == null) {
         return null;
      } else {
         for (SteamRuntime.SafePeerIdentity peer : this.safeSessionView().peers()) {
            if (peer.opaquePeerId().equals(opaquePeerId)) {
               return peer;
            }
         }

         return null;
      }
   }

   String safeOpaquePeerId(long remoteSteamId) {
      SteamRuntime.SafeSessionView view = this.safeSessionView();
      return view.active() && remoteSteamId != 0L ? opaquePeerId(view.generation(), remoteSteamId) : null;
   }

   public boolean disconnectSafeSession(long expectedGeneration) {
      SteamRuntime.WorkerGeneration current = this.generation;
      if (expectedGeneration <= 0L) {
         return false;
      } else {
         for (SteamConnectionBridge bridge : this.bridgeRegistry.snapshot()) {
            if (bridge != null && !bridge.isClosed() && bridge.dedicatedSessionGeneration() == expectedGeneration) {
               bridge.close(true);
               return true;
            }
         }

         if (current != null && current.id == expectedGeneration) {
            SteamRuntime.HostRegistration hosting = this.hostRegistration;
            if (hosting != null) {
               hosting.owner().stop();
            } else {
               this.stopForDirectServerConnection();
            }

            return true;
         } else {
            return false;
         }
      }
   }

   public String steamId() {
      return this.localSteamId == 0L ? "unavailable" : Long.toUnsignedString(this.localSteamId);
   }

   public Throwable failureCause() {
      return this.failureCause;
   }

   private static String opaquePeerId(long generationId, long steamId) {
      return SteamPeerPrivacy.opaquePeerId(generationId, steamId);
   }

   public static String dedicatedSessionId(long generation) {
      return SteamPeerPrivacy.dedicatedSessionId(generation);
   }

   long steamIdValue() {
      return this.localSteamId;
   }

   public long authenticatedMinecraftPeer(SocketAddress remoteAddress) {
      int remotePort = SteamLoopbackAuthentication.loopbackPort(remoteAddress);
      if (remotePort < 0) {
         return 0L;
      } else {
         SteamRuntime.AuthenticatedLoopbackPeer peer = this.authenticatedLoopbackPeers.get(remotePort);
         if (peer != null && !peer.bridge().isClosed()) {
            return peer.remoteSteamId();
         } else {
            if (peer != null) {
               this.authenticatedLoopbackPeers.remove(remotePort, peer);
            }

            return 0L;
         }
      }
   }

   void startHosting(SteamSession owner, int localPort, int udpPort, byte[] token, SteamAccessMode accessMode) throws IOException {
      this.awaitReady();
      if (localPort < 1 || localPort > 65535) {
         throw new IOException("Invalid LAN port: " + localPort);
      } else if (accessMode == SteamAccessMode.LOCAL_ONLY) {
         throw new IOException("Local-only mode does not start Steam hosting");
      } else if (udpPort >= 0 && udpPort <= 65535) {
         VoiceChatUdpEndpoint udpEndpoint = VoiceChatUdpEndpoint.resolve(localPort, udpPort);
         SteamRuntime.HostRegistration replacement = new SteamRuntime.HostRegistration(owner, localPort, udpEndpoint, (byte[])token.clone(), accessMode);
         if (udpEndpoint.hostPort() > 0) {
            E4steamClient.LOGGER.info("Using UDP port {} for {}", udpEndpoint.hostPort(), udpEndpoint.source());
         }

         synchronized (this.lifecycleLock) {
            SteamRuntime.HostRegistration current = this.hostRegistration;
            if (current != null && current.owner() != owner) {
               throw new IOException("Another Steam hosting session is still stopping");
            } else {
               this.hostRegistration = replacement;
               this.nextLoopbackConnectAttemptAtMillis = 0L;
            }
         }
      } else {
         throw new IOException("Invalid UDP tunnel port: " + udpPort);
      }
   }

   void stopHosting(SteamSession owner) {
      boolean removed = false;
      synchronized (this.lifecycleLock) {
         SteamRuntime.HostRegistration current = this.hostRegistration;
         if (current != null && current.owner() == owner) {
            this.hostRegistration = null;
            removed = true;
         }
      }

      if (removed) {
         this.closeHostBridges(owner);
      }

      this.submitSteamTaskIfRunning(() -> {
         SteamLobbyManager currentx = this.lobbyManager;
         if (currentx != null) {
            currentx.stopHosting(owner);
         }

         return null;
      });
   }

   CompletableFuture<Long> createHostLobby(SteamSession owner, SteamAccessMode accessMode, SteamAddress address) throws IOException {
      this.awaitReady();
      CompletableFuture<CompletableFuture<Long>> scheduled = this.submitSteamTask(() -> {
         SteamLobbyManager current = this.lobbyManager;
         if (current == null) {
            throw new IOException("Steam social services are unavailable");
         } else {
            return current.createHostLobby(owner, accessMode, address);
         }
      });
      return scheduled.thenCompose(Function.identity());
   }

   CompletableFuture<Void> openHostInviteOverlay(SteamSession owner) throws IOException {
      this.awaitReady();
      return this.submitSteamTask(() -> {
         SteamLobbyManager current = this.lobbyManager;
         if (current == null) {
            throw new IOException("Steam social services are unavailable");
         } else {
            current.openHostInviteOverlay(owner);
            return null;
         }
      });
   }

   public void openFriendsOverlay() throws IOException {
      this.awaitReady();
      CompletableFuture<Void> task = this.submitSteamTask(() -> {
         SteamLobbyManager current = this.lobbyManager;
         if (current == null) {
            throw new IOException("Steam social services are unavailable");
         } else {
            current.openFriendsOverlay();
            return null;
         }
      });
      waitForSteamTask(task, STEAM_TASK_TIMEOUT, "opening the Steam friends overlay");
   }

   public void cancelClientConnection() {
      this.cancelGuestJoin();
      SteamClientBridge.cancelPending();
      SteamDedicatedClientBridge.cancelPending();

      for (SteamConnectionBridge bridge : this.bridgeRegistry.snapshot()) {
         if (!bridge.isHostSide()) {
            bridge.close(true);
         }
      }
   }

   void closeClientBridges(long remoteSteamId) {
      for (SteamConnectionBridge bridge : this.bridgeRegistry.snapshot()) {
         if (!bridge.isHostSide() && bridge.remoteSteamId() == remoteSteamId) {
            bridge.close(true);
         }
      }
   }

   public void clientMinecraftJoined(SocketAddress address) {
      if (address instanceof InetSocketAddress endpoint) {
         if (endpoint.getAddress() != null && endpoint.getAddress().isLoopbackAddress()) {
            for (SteamConnectionBridge bridge : this.bridgeRegistry.snapshot()) {
               if (!bridge.isHostSide() && !bridge.isClosed() && bridge.localPort() == endpoint.getPort()) {
                  this.submitSteamTaskIfRunning(() -> {
                     SteamLobbyManager current = this.lobbyManager;
                     if (current != null && !bridge.isClosed() && this.bridgeRegistry.snapshot().contains(bridge)) {
                        current.clientMinecraftJoined(bridge);
                     }

                     return null;
                  });
                  break;
               }
            }
         }
      }
   }

   public void cancelGuestJoin() {
      this.submitSteamTaskIfRunning(() -> {
         SteamLobbyManager current = this.lobbyManager;
         if (current != null) {
            current.cancelGuestJoin();
         }

         return null;
      });
   }

   public CompletableFuture<SteamRuntime.PublicHostLobbyTarget> publicHostLobbyTarget() {
      SteamRuntime.SafeSessionView before = this.safeSessionView();
      return before.active() && "INTEGRATED_HOST".equals(before.roleCode()) ? this.submitSteamTaskIfRunning(() -> {
         SteamLobbyManager current = this.lobbyManager;
         long lobbyId = current == null ? 0L : current.publicHostLobbyId();
         SteamRuntime.SafeSessionView after = this.safeSessionView();
         if (lobbyId != 0L && after.active() && after.generation() == before.generation()) {
            SteamRuntime.HostRegistration host = this.hostRegistration;
            return host == null ? null : new SteamRuntime.PublicHostLobbyTarget(lobbyId, after.generation(), host.owner().customAccessModeId());
         } else {
            return null;
         }
      }) : CompletableFuture.completedFuture(null);
   }

   public CompletableFuture<Boolean> joinPublicLobby(long lobbyId) {
      return lobbyId == 0L ? CompletableFuture.completedFuture(Boolean.FALSE) : this.submitSteamTaskIfRunning(() -> {
         SteamLobbyManager current = this.lobbyManager;
         return current != null && current.joinPublicLobby(lobbyId);
      });
   }

   public LobbyService addonLobbies(String owner) {
      return new LobbyService() {
         @Override
         public CompletionStage<ApiResult<LobbyService.LobbySnapshot>> create(LobbyService.CreateRequest request) {
            return SteamRuntime.lobbyResult(
               SteamRuntime.this.<CompletableFuture<LobbyService.LobbySnapshot>>submitSteamTaskIfRunning(
                     () -> SteamRuntime.this.addonDirectory().create(owner, request)
                  )
                  .thenCompose(value -> (CompletionStage<LobbyService.LobbySnapshot>)value)
            );
         }

         @Override
         public CompletionStage<ApiResult<LobbyService.LobbySnapshot>> updateMetadata(LobbyService.LobbyId id, LobbyService.Metadata metadata) {
            return SteamRuntime.lobbyResult(SteamRuntime.this.submitSteamTaskIfRunning(() -> SteamRuntime.this.addonDirectory().update(owner, id, metadata)));
         }

         @Override
         public CompletionStage<ApiResult<LobbyService.SearchPage>> search(LobbyService.SearchQuery query) {
            return SteamRuntime.lobbyResult(
               SteamRuntime.this.<CompletableFuture<LobbyService.SearchPage>>submitSteamTaskIfRunning(
                     () -> SteamRuntime.this.addonDirectory().search(owner, query)
                  )
                  .thenCompose(value -> (CompletionStage<LobbyService.SearchPage>)value)
            );
         }

         @Override
         public CompletionStage<ApiResult<Boolean>> leave(LobbyService.LobbyId id) {
            return SteamRuntime.lobbyResult(SteamRuntime.this.submitSteamTaskIfRunning(() -> SteamRuntime.this.addonDirectory().leave(owner, id)));
         }

         @Override
         public CompletionStage<ApiResult<LobbyService.LobbySnapshot>> join(LobbyService.LobbyId id) {
            CompletableFuture<ApiResult<LobbyService.LobbySnapshot>> unavailable = new CompletableFuture<>();
            unavailable.completeExceptionally(new UnsupportedOperationException("Use the directory connection service"));
            return unavailable;
         }
      };
   }

   private static <T> CompletableFuture<ApiResult<T>> lobbyResult(CompletableFuture<T> future) {
      return future.thenApply(ApiResult::success);
   }

   private SteamAddonLobbyDirectory addonDirectory() throws IOException {
      if (this.lobbyManager == null) {
         throw new IOException("Steam matchmaking unavailable");
      } else {
         if (this.addonLobbyDirectory == null) {
            this.addonLobbyDirectory = new SteamAddonLobbyDirectory();
         }

         return this.addonLobbyDirectory;
      }
   }

   public void releaseAddonLobbies(String owner) {
      this.submitSteamTaskIfRunning(() -> {
         if (this.addonLobbyDirectory != null) {
            this.addonLobbyDirectory.release(owner);
         }

         return null;
      });
   }

   public SteamRuntime.SafePublicJoinSnapshot publicJoinSnapshot(long lobbyId) {
      SteamPublicJoinTracker.Snapshot snapshot = this.publicJoinTracker.snapshot(lobbyId);
      return new SteamRuntime.SafePublicJoinSnapshot(snapshot.phase().name(), snapshot.detailCode());
   }

   void publicJoinBegin(long lobbyId) {
      this.publicJoinTracker.begin(lobbyId);
   }

   void publicJoinAuthenticating(long lobbyId) {
      this.publicJoinTracker.authenticating(lobbyId);
   }

   void publicJoinConnecting(long lobbyId) {
      this.publicJoinTracker.connecting(lobbyId);
   }

   void publicJoinActive(long lobbyId) {
      this.publicJoinTracker.active(lobbyId);
   }

   void publicJoinCancelled(long lobbyId) {
      this.publicJoinTracker.cancel(lobbyId);
   }

   void publicJoinFailed(long lobbyId, SteamPublicJoinTracker.Phase phase, String detailCode) {
      this.publicJoinTracker.fail(lobbyId, phase, detailCode);
   }

   public void stopForDirectServerConnection() {
      SteamClientBridge.cancelPending();
      SteamDedicatedClientBridge.cancelPending();
      synchronized (this.lifecycleLock) {
         this.hostRegistration = null;
      }

      for (SteamConnectionBridge bridge : this.bridgeRegistry.snapshot()) {
         bridge.close(false);
      }

      this.clearOutbound();
      this.pendingPeers.clear();
      this.knownPeerSessionGate.clear();
      this.idleSessionDeadlines.clear();
   }

   public CompletableFuture<Boolean> beginGuestConnect(String endpoint) {
      return this.submitSteamTaskIfRunning(() -> {
         SteamLobbyManager current = this.lobbyManager;
         return current != null && current.beginGuestConnect(endpoint);
      });
   }

   public CompletableFuture<Boolean> claimGuestInvite(String endpoint) {
      return this.submitSteamTaskIfRunning(() -> {
         SteamLobbyManager current = this.lobbyManager;
         return current != null && current.claimGuestInvite(endpoint);
      });
   }

   SteamRuntime.DedicatedClientAuth createDedicatedClientAuth() throws IOException {
      this.awaitReady();

      try {
         return waitForSteamTask(this.submitSteamTask(() -> {
            SteamUser current = this.user;
            if (current == null) {
               throw new IOException("Steam user API is unavailable");
            } else {
               ByteBuffer target = ByteBuffer.allocateDirect(4096);
               int[] size = new int[1];
               SteamAuthTicket handle = current.getAuthSessionTicket(target, size);
               if (handle != null && handle.isValid() && size[0] > 0 && size[0] <= 4096) {
                  target.flip();
                  target.limit(size[0]);
                  byte[] proof = new byte[size[0]];
                  target.get(proof);
                  zero(target);
                  byte[] nonce = new byte[32];
                  new SecureRandom().nextBytes(nonce);
                  return new SteamRuntime.DedicatedClientAuth(this, handle, proof, nonce);
               } else {
                  if (handle != null && handle.isValid()) {
                     current.cancelAuthTicket(handle);
                  }

                  zero(target);
                  throw new IOException("Steam did not issue a valid authentication proof");
               }
            }
         }), STEAM_TASK_TIMEOUT, "create dedicated Steam authentication proof");
      } catch (IOException var2) {
         throw var2;
      } catch (Exception var3) {
         throw new IOException("Could not create Steam authentication proof", var3);
      }
   }

   void prepareDedicatedConnection(long remoteSteamId) throws IOException {
      this.awaitReady();

      try {
         waitForSteamTask(
            this.submitSteamTask(
               () -> {
                  SteamNetworkingSocketsP2PTransport current = this.dedicatedTransport;
                  if (current != null) {
                     current.close();
                     this.dedicatedTransport = null;
                  }

                  this.dedicatedTransport = SteamNetworkingSocketsP2PTransport.openClient(
                     this.steamLifecycle.steamApiPath(), remoteSteamId, new SteamNetworkingSocketsP2PTransport.SessionListener() {
                        @Override
                        public void onSessionRequest(long ignored) {
                        }

                        @Override
                        public void onSessionFailed(long failedSteamId, int reason, String detail) {
                           String safeDetail = detail != null && !detail.trim().isEmpty() ? detail.trim() : "no Steam diagnostic";
                           E4steamClient.LOGGER.warn("Dedicated Steam P2P connection failed (reason {}): {}", reason, safeDetail);

                           for (SteamConnectionBridge bridge : SteamRuntime.this.bridgeRegistry.snapshot()) {
                              if (!bridge.isHostSide() && bridge.remoteSteamId() == failedSteamId && bridge.dedicatedSessionGeneration() > 0L) {
                                 bridge.close(false);
                              }
                           }
                        }
                     }
                  );
                  return null;
               }
            ),
            STEAM_TASK_TIMEOUT,
            "open dedicated Steam P2P connection"
         );
      } catch (IOException var4) {
         throw var4;
      } catch (Exception var5) {
         throw new IOException("Could not open the dedicated Steam P2P connection", var5);
      }
   }

   private void cancelDedicatedAuthTicket(SteamAuthTicket ticket) {
      if (ticket != null && ticket.isValid()) {
         this.submitSteamTaskIfRunning(() -> {
            SteamUser current = this.user;
            if (current != null) {
               current.cancelAuthTicket(ticket);
            }

            return null;
         });
      }
   }

   int nextConnectionId(long remoteSteamId) {
      return this.bridgeRegistry.nextConnectionId(remoteSteamId, ThreadLocalRandom.current());
   }

   SteamConnectionBridge registerClientBridge(long remoteSteamId, int connectionId, Socket socket, AutoCloseable activity) throws IOException {
      this.verifyRunning();
      if (remoteSteamId == 0L) {
         throw new IOException("Invalid host Steam ID: " + Long.toUnsignedString(remoteSteamId));
      } else {
         SteamConnectionBridge bridge = new SteamConnectionBridge(this, remoteSteamId, connectionId, socket, null, activity);
         bridge.waitForPeerReadyUntil(System.currentTimeMillis() + 10000L);
         SteamBridgeRegistry.Key key = new SteamBridgeRegistry.Key(remoteSteamId, connectionId);
         SteamBridgeRegistry.Registration result = this.registerBridge(key, bridge);
         if (result != SteamBridgeRegistry.Registration.REGISTERED) {
            throw new IOException(switch (result) {
               case CAPACITY -> "Too many active Steam bridges";
               case COLLISION -> "Steam connection identifier collision";
               case UNAVAILABLE -> "Steam runtime stopped while opening the bridge";
               default -> "Could not register the Steam bridge";
            });
         } else {
            long reconnectDeadline = this.clientReconnectDeadlines.getOrDefault(remoteSteamId, 0L);
            boolean olderClientBridgeExists = this.bridgeRegistry
               .any(candidate -> candidate != bridge && !candidate.isHostSide() && candidate.remoteSteamId() == remoteSteamId);
            if (olderClientBridgeExists) {
               reconnectDeadline = Math.max(reconnectDeadline, System.currentTimeMillis() + 3000L);
            }

            this.clientReconnectDeadlines.remove(remoteSteamId);
            if (reconnectDeadline > System.currentTimeMillis()) {
               bridge.delayOutboundUntil(reconnectDeadline);
               E4steamClient.LOGGER.info("Delaying Steam reconnect to {} until the previous peer session is closed", Long.toUnsignedString(remoteSteamId));
            }

            this.submitSteamTaskIfRunning(() -> {
               SteamLobbyManager current = this.lobbyManager;
               if (current != null && !bridge.isClosed()) {
                  current.clientBridgeOpened(bridge);
               }

               return null;
            });
            return bridge;
         }
      }
   }

   boolean sendOpen(SteamConnectionBridge bridge, byte[] token) {
      return this.enqueueControl(
         bridge.remoteSteamId(), bridge.connectionId(), SteamProtocol.encodeOpen(bridge.connectionId(), token), SteamOutboundQueue.Kind.OPEN, bridge
      );
   }

   boolean sendDedicatedOpen(SteamConnectionBridge bridge, SteamDedicatedAddress address, SteamRuntime.DedicatedClientAuth authentication) {
      byte[] proof = authentication.takeProof();
      byte[] nonce = authentication.nonce();

      boolean var6;
      try {
         var6 = this.enqueueControl(
            bridge.remoteSteamId(),
            bridge.connectionId(),
            SteamProtocol.encodeDedicatedOpen(bridge.connectionId(), address.generation(), nonce, proof),
            SteamOutboundQueue.Kind.DEDICATED_OPEN,
            bridge
         );
      } finally {
         Arrays.fill(proof, (byte)0);
         Arrays.fill(nonce, (byte)0);
      }

      return var6;
   }

   private boolean sendOpenAck(SteamConnectionBridge bridge, VoiceChatUdpEndpoint endpoint) {
      return this.enqueueControl(
         bridge.remoteSteamId(),
         bridge.connectionId(),
         SteamProtocol.encodeOpenAck(bridge.connectionId(), endpoint.clientPortMode(), endpoint.hostPort()),
         SteamOutboundQueue.Kind.OPEN_ACK,
         bridge
      );
   }

   private boolean sendBridgeReady(SteamConnectionBridge bridge) {
      return this.enqueueControl(
         bridge.remoteSteamId(), bridge.connectionId(), SteamProtocol.encodeBridgeReady(bridge.connectionId()), SteamOutboundQueue.Kind.BRIDGE_READY, bridge
      );
   }

   boolean sendAddonHello(SteamConnectionBridge bridge, byte[] packet) {
      return this.enqueueControl(bridge.remoteSteamId(), bridge.connectionId(), packet, SteamOutboundQueue.Kind.ADDON_HELLO, bridge);
   }

   boolean sendAddonFrame(SteamConnectionBridge bridge, byte[] packet, boolean reliable) {
      if (!reliable) {
      }

      return this.status == SteamRuntime.Status.RUNNING && !this.isWorkerStopping() && !bridge.isClosed()
         ? this.outbound.offerAddonData(bridge.remoteSteamId(), bridge.connectionId(), packet, !reliable, bridge)
         : false;
   }

   @Override
   public boolean sendData(SteamConnectionBridge bridge, byte[] payload) {
      return this.enqueueData(bridge, SteamProtocol.encodeData(bridge.connectionId(), payload));
   }

   void sendDatagram(SteamUdpBridge bridge, byte[] payload) {
      SteamConnectionBridge owner = bridge.owner();
      byte[] packet = SteamProtocol.encodeDatagram(owner.connectionId(), payload);
      if (this.status == SteamRuntime.Status.RUNNING && !this.isWorkerStopping() && !bridge.isClosed() && !owner.isClosed()) {
         this.outbound.offerDatagram(owner.remoteSteamId(), owner.connectionId(), packet, owner);
      }
   }

   private void startClientUdpBridge(SteamConnectionBridge owner, VoiceChatUdpEndpoint endpoint) {
      this.startUdpBridge(owner, endpoint.clientPort(owner.localPort()), false);
   }

   private void startHostUdpBridge(SteamConnectionBridge owner, VoiceChatUdpEndpoint endpoint) {
      this.startUdpBridge(owner, endpoint.hostPort(), true);
   }

   private void startUdpBridge(SteamConnectionBridge owner, int port, boolean hostSide) {
      if (port != 0 && !owner.isClosed()) {
         if (port >= 1 && port <= 65535) {
            SteamBridgeRegistry.Key key = new SteamBridgeRegistry.Key(owner.remoteSteamId(), owner.connectionId());
            if (!this.bridgeRegistry.containsUdp(key)) {
               SteamUdpBridge bridge = null;

               try {
                  bridge = hostSide ? SteamUdpBridge.host(this, owner, port) : SteamUdpBridge.client(this, owner, port);
                  SteamUdpBridge previous = this.bridgeRegistry.putUdpIfAbsent(key, bridge);
                  if (previous != null || owner.isClosed()) {
                     bridge.close();
                     return;
                  }

                  bridge.start();
                  E4steamClient.LOGGER
                     .info(
                        "Opened {} UDP tunnel on port {} for Steam user {}",
                        new Object[]{hostSide ? "host" : "client", port, Long.toUnsignedString(owner.remoteSteamId())}
                     );
               } catch (IOException var7) {
                  if (bridge != null) {
                     bridge.close();
                  }

                  E4steamClient.LOGGER.warn("Could not open the optional UDP tunnel on port {}; Minecraft TCP will continue", port, var7);
               }
            }
         } else {
            E4steamClient.LOGGER.warn("UDP tunneling is disabled because port {} is invalid", port);
         }
      }
   }

   @Override
   public void closeUdpBridge(SteamConnectionBridge owner) {
      SteamUdpBridge udp = this.bridgeRegistry.removeUdp(new SteamBridgeRegistry.Key(owner.remoteSteamId(), owner.connectionId()));
      if (udp != null) {
         udp.close();
      }
   }

   @Override
   public boolean sendFin(SteamConnectionBridge bridge) {
      return this.enqueueControl(
         bridge.remoteSteamId(), bridge.connectionId(), SteamProtocol.encodeFin(bridge.connectionId()), SteamOutboundQueue.Kind.FIN, bridge
      );
   }

   @Override
   public boolean sendReset(SteamConnectionBridge bridge) {
      return this.enqueueControl(
         bridge.remoteSteamId(), bridge.connectionId(), SteamProtocol.encodeReset(bridge.connectionId()), SteamOutboundQueue.Kind.RESET, bridge
      );
   }

   private void sendStandaloneReset(long remoteSteamId, int connectionId) {
      this.enqueueControl(remoteSteamId, connectionId, SteamProtocol.encodeReset(connectionId), SteamOutboundQueue.Kind.RESET, null);
      synchronized (this.peerSessionLock) {
         if (!this.hasBridgeForRemote(remoteSteamId)) {
            this.idleSessionDeadlines.put(remoteSteamId, this.newIdleSessionDeadline());
         }
      }
   }

   @Override
   public void unregister(SteamConnectionBridge bridge) {
      SteamAddonHooks.bridgeClosed(bridge);
      if (bridge.isHostSide()) {
         SteamRuntime.AuthenticatedLoopbackPeer peer = this.authenticatedLoopbackPeers.get(bridge.localPort());
         if (peer != null && peer.bridge() == bridge) {
            this.authenticatedLoopbackPeers.remove(bridge.localPort(), peer);
         }
      }

      this.closeUdpBridge(bridge);
      this.purgeOutbound(bridge);
      boolean removed = false;
      boolean anotherBridgeExists = false;
      synchronized (this.peerSessionLock) {
         if (this.bridgeRegistry.remove(new SteamBridgeRegistry.Key(bridge.remoteSteamId(), bridge.connectionId()), bridge)) {
            removed = true;
            anotherBridgeExists = bridge.isHostSide() ? this.hasBridgeForRemote(bridge.remoteSteamId()) : this.hasClientBridgeForRemote(bridge.remoteSteamId());
            if (!this.hasBridgeForRemote(bridge.remoteSteamId())) {
               long now = System.currentTimeMillis();
               this.idleSessionDeadlines.put(bridge.remoteSteamId(), this.newIdleSessionDeadline());
               this.knownPeerSessionGate.defer(bridge.remoteSteamId(), now);
            }
         }
      }

      if (removed && !bridge.isHostSide()) {
         if (!anotherBridgeExists) {
            this.clientReconnectDeadlines.put(bridge.remoteSteamId(), System.currentTimeMillis() + 3000L);
         }

         boolean finalAnotherBridgeExists = anotherBridgeExists;
         this.submitSteamTaskIfRunning(() -> {
            SteamLobbyManager current = this.lobbyManager;
            if (current != null) {
               current.clientBridgeClosed(bridge, finalAnotherBridgeExists);
            }

            return null;
         });
      }

      bridge.releaseActivity();
   }

   public void shutdown() {
      SteamRuntime.WorkerGeneration target;
      synchronized (this.lifecycleLock) {
         if (this.permanentlyShutdown) {
            return;
         }

         this.permanentlyShutdown = true;
         target = this.generation;
         if (target != null) {
            target.stopRequested.set(true);
            this.status = SteamRuntime.Status.STOPPING;
         }
      }

      SteamClientBridge.cancelPending();
      SteamDedicatedClientBridge.cancelPending();
      this.hostRegistration = null;

      for (SteamConnectionBridge bridge : this.bridgeRegistry.snapshot()) {
         bridge.close(false);
      }

      this.clearOutbound();
      this.pendingPeers.clear();
      this.knownPeerSessionGate.clear();
      this.idleSessionDeadlines.clear();
      Thread worker = target == null ? null : target.worker;
      if (worker != null) {
         worker.interrupt();
         if (worker != Thread.currentThread()) {
            try {
               worker.join(2000L);
            } catch (InterruptedException var4) {
               Thread.currentThread().interrupt();
            }
         }
      } else {
         this.status = SteamRuntime.Status.STOPPED;
      }
   }

   private SteamRuntime.WorkerGeneration ensureWorkerStarted() throws IOException {
      synchronized (this.lifecycleLock) {
         long deadline = System.currentTimeMillis() + START_TIMEOUT.toMillis();

         while (this.generation != null && this.generation.stopRequested.get()) {
            if (this.permanentlyShutdown) {
               throw new IOException("Steam runtime has been shut down");
            }

            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0L) {
               throw new IOException("Timed out while waiting for the previous Steam runtime to stop");
            }

            try {
               this.lifecycleLock.wait(Math.min(remaining, 250L));
            } catch (InterruptedException var8) {
               Thread.currentThread().interrupt();
               throw new IOException("Interrupted while waiting for Steam to restart", var8);
            }
         }

         if (this.permanentlyShutdown) {
            throw new IOException("Steam runtime has been shut down");
         } else if (this.generation != null) {
            return this.generation;
         } else {
            this.failureCause = null;
            this.localSteamId = 0L;
            this.status = SteamRuntime.Status.STARTING;
            SteamRuntime.WorkerGeneration created = new SteamRuntime.WorkerGeneration(++this.nextWorkerGenerationId);
            Thread worker = new Thread(() -> this.runWorker(created), "e4steam-steam-runtime");
            worker.setDaemon(true);
            created.worker = worker;
            this.generation = created;
            this.workerThread = worker;
            worker.start();
            return created;
         }
      }
   }

   private void runWorker(SteamRuntime.WorkerGeneration currentGeneration) {
      Throwable workerFailure = null;

      try {
         this.initializeSteam();
         synchronized (this.lifecycleLock) {
            if (this.generation != currentGeneration || currentGeneration.stopRequested.get()) {
               throw new IOException("Steam runtime was stopped during initialization");
            }

            this.status = SteamRuntime.Status.RUNNING;
         }

         currentGeneration.ready.complete(null);
         E4steamClient.LOGGER.info("Steam Networking Messages initialized as {} using App ID {}", this.steamId(), 480);
         ByteBuffer sendBuffer = ByteBuffer.allocateDirect(SteamProtocol.MAX_PACKET_SIZE);
         ByteBuffer receiveBuffer = ByteBuffer.allocateDirect(1048576);

         while (!currentGeneration.stopRequested.get()) {
            long nowMillis = System.currentTimeMillis();
            if (!this.steamLifecycle.isHealthy(nowMillis)) {
               throw new IOException("Steam disconnected while e4steam was active");
            }

            this.steamLifecycle.runCallbacks();
            SteamNetworkingSocketsP2PTransport activeDedicatedTransport = this.dedicatedTransport;
            if (activeDedicatedTransport != null) {
               activeDedicatedTransport.runCallbacks();
            }

            this.drainSteamTasks();
            this.acceptKnownPeerSessions(nowMillis);
            this.drainOutbound(sendBuffer, currentGeneration.id);
            this.receivePackets(receiveBuffer);
            this.receiveDedicatedPackets(receiveBuffer);
            SteamAddonHooks.tick();
            this.cleanupPeerSessions();
            this.cleanupGracefulBridgeClosures(nowMillis);
            if (this.addonLobbyDirectory != null) {
               this.addonLobbyDirectory.tick(nowMillis);
            }

            SteamLobbyManager currentSocial = this.lobbyManager;
            if (currentSocial != null) {
               currentSocial.cleanup(nowMillis);
            }

            if (this.shouldStopForIdle(currentGeneration, nowMillis)) {
               break;
            }

            try {
               Thread.sleep(10L);
            } catch (InterruptedException var66) {
            }
         }
      } catch (Throwable var69) {
         workerFailure = var69;
         this.failureCause = var69;
         synchronized (this.lifecycleLock) {
            this.status = SteamRuntime.Status.FAILED;
         }

         currentGeneration.ready.completeExceptionally(var69);
         E4steamClient.LOGGER.error("Steam runtime failed", var69);
      } finally {
         SteamRuntime.HostRegistration failedHost;
         synchronized (this.lifecycleLock) {
            failedHost = this.hostRegistration;
            this.hostRegistration = null;
         }

         ArrayList<SteamConnectionBridge> failedBridges = new ArrayList<>(this.bridgeRegistry.snapshot());

         for (SteamConnectionBridge bridge : failedBridges) {
            bridge.close(false);
         }

         for (SteamConnectionBridge bridge : failedBridges) {
            this.unregister(bridge);
         }

         this.bridgeRegistry.clear();
         this.authenticatedLoopbackPeers.clear();
         this.clearOutbound();
         this.pendingPeers.clear();
         this.knownPeerSessionGate.clear();
         this.idleSessionDeadlines.clear();
         if (workerFailure != null && failedHost != null) {
            failedHost.owner().runtimeFailed(workerFailure);
         }

         SteamLobbyManager currentSocialx = this.lobbyManager;
         this.lobbyManager = null;
         SteamAddonLobbyDirectory directory = this.addonLobbyDirectory;
         this.addonLobbyDirectory = null;
         if (directory != null) {
            directory.close();
         }

         if (currentSocialx != null) {
            try {
               currentSocialx.close();
            } catch (Throwable var63) {
            }
         }

         SteamNetworkingSocketsP2PTransport currentDedicatedTransport = this.dedicatedTransport;
         this.dedicatedTransport = null;
         if (currentDedicatedTransport != null) {
            try {
               currentDedicatedTransport.close();
            } catch (Throwable var62) {
            }
         }

         SteamNetworkingMessagesTransport currentTransport = this.transport;
         this.transport = null;
         if (currentTransport != null) {
            try {
               currentTransport.close();
            } catch (Throwable var61) {
            }
         }

         SteamUser currentUser = this.user;
         this.user = null;
         if (currentUser != null) {
            try {
               currentUser.dispose();
            } catch (Throwable var60) {
            }
         }

         SteamUtils currentUtils = this.utils;
         this.utils = null;
         if (currentUtils != null) {
            try {
               currentUtils.dispose();
            } catch (Throwable var59) {
            }
         }

         try {
            this.steamLifecycle.close();
         } catch (Throwable var58) {
         }

         this.failPendingSteamTasks((Throwable)(workerFailure == null ? new IOException("Steam runtime stopped") : workerFailure));
         this.localSteamId = 0L;
         SteamRuntime.Activity failedLaunchActivity = null;
         synchronized (this.lifecycleLock) {
            if (this.generation == currentGeneration) {
               this.generation = null;
               this.workerThread = null;
            }

            if (workerFailure != null) {
               failedLaunchActivity = this.launchActivity;
               this.launchActivity = null;
               this.launchStartRequested.set(false);
            }

            if (workerFailure == null) {
               this.status = SteamRuntime.Status.STOPPED;
            }

            this.lifecycleLock.notifyAll();
         }

         if (failedLaunchActivity != null) {
            failedLaunchActivity.close();
         }
      }
   }

   private boolean shouldStopForIdle(SteamRuntime.WorkerGeneration currentGeneration, long nowMillis) {
      synchronized (this.lifecycleLock) {
         if (!this.permanentlyShutdown && this.generation == currentGeneration) {
            SteamLobbyManager currentSocial = this.lobbyManager;
            boolean keepAlive = this.activityCount > 0
               || this.hostRegistration != null
               || !this.bridgeRegistry.isEmpty()
               || !this.outbound.isEmpty()
               || !this.resetRetries.isEmpty()
               || !this.idleSessionDeadlines.isEmpty()
               || !this.steamTasks.isEmpty()
               || this.addonLobbyDirectory != null && this.addonLobbyDirectory.keepsRuntimeAlive()
               || currentSocial != null && currentSocial.keepsRuntimeAlive();
            if (keepAlive) {
               currentGeneration.idleSinceMillis = 0L;
               return false;
            } else if (currentGeneration.idleSinceMillis == 0L) {
               currentGeneration.idleSinceMillis = nowMillis;
               return false;
            } else if (nowMillis - currentGeneration.idleSinceMillis < 1000L) {
               return false;
            } else {
               this.status = SteamRuntime.Status.STOPPING;
               currentGeneration.stopRequested.set(true);
               return true;
            }
         } else {
            currentGeneration.stopRequested.set(true);
            this.status = SteamRuntime.Status.STOPPING;
            return true;
         }
      }
   }

   private <T> CompletableFuture<T> submitSteamTask(Callable<T> action) throws IOException {
      SteamRuntime.SteamTask<T> task = new SteamRuntime.SteamTask<>(action);
      synchronized (this.lifecycleLock) {
         SteamRuntime.WorkerGeneration current = this.generation;
         if (current == null || current.stopRequested.get() || this.status != SteamRuntime.Status.RUNNING || this.permanentlyShutdown) {
            throw new IOException("Steam runtime is not available for this operation");
         }

         this.steamTasks.add(task);
         current.idleSinceMillis = 0L;
         current.worker.interrupt();
      }

      return task.result;
   }

   private <T> CompletableFuture<T> submitSteamTaskIfRunning(Callable<T> action) {
      try {
         return this.submitSteamTask(action);
      } catch (IOException var4) {
         CompletableFuture<T> failed = new CompletableFuture<>();
         failed.completeExceptionally(var4);
         return failed;
      }
   }

   private void drainSteamTasks() {
      for (int handled = 0; handled < 256; handled++) {
         SteamRuntime.SteamTask<?> task = this.steamTasks.poll();
         if (task == null) {
            return;
         }

         task.run();
      }
   }

   private void failPendingSteamTasks(Throwable cause) {
      SteamRuntime.SteamTask<?> task;
      while ((task = this.steamTasks.poll()) != null) {
         task.fail(cause);
      }
   }

   private static <T> T waitForSteamTask(CompletableFuture<T> task, Duration timeout, String operation) throws IOException {
      try {
         return task.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      } catch (TimeoutException var5) {
         throw new IOException("Timed out while " + operation, var5);
      } catch (InterruptedException var6) {
         Thread.currentThread().interrupt();
         throw new IOException("Interrupted while " + operation, var6);
      } catch (ExecutionException var7) {
         Throwable cause = (Throwable)(var7.getCause() == null ? var7 : var7.getCause());
         if (cause instanceof IOException) {
            throw (IOException)cause;
         } else {
            throw new IOException("Steam failed while " + operation + ": " + cause.getMessage(), cause);
         }
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

   private void releaseActivity() {
      synchronized (this.lifecycleLock) {
         if (this.activityCount > 0) {
            this.activityCount--;
         }

         SteamRuntime.WorkerGeneration current = this.generation;
         if (current != null) {
            current.worker.interrupt();
         }
      }
   }

   boolean isOverlayEnabledOnWorker() {
      SteamUtils current = this.utils;
      return current != null && current.isOverlayEnabled();
   }

   private boolean isWorkerStopping() {
      SteamRuntime.WorkerGeneration current = this.generation;
      return this.permanentlyShutdown || current == null || current.stopRequested.get();
   }

   private void initializeSteam() throws Exception {
      this.ensureAppIdFile();
      this.steamLifecycle.start();
      SteamUtils createdUtils = new SteamUtils(new SteamUtilsCallback() {});
      int initializedAppId = createdUtils.getAppID();
      if (initializedAppId != 480) {
         createdUtils.dispose();
         throw new IOException("Steam initialized the Minecraft process with App ID " + initializedAppId + " instead of the required App ID 480");
      } else {
         this.utils = createdUtils;
         SteamUser createdUser = new SteamUser(new SteamUserCallback() {});
         SteamID id = createdUser.getSteamID();
         if (id != null && id.isValid()) {
            this.localSteamId = SteamNativeHandle.getNativeHandle(id);
            this.user = createdUser;
            this.transport = SteamNetworkingMessagesTransport.open(
               this.steamLifecycle.steamApiPath(),
               new SteamNetworkingMessagesTransport.SessionListener() {
                  @Override
                  public void onSessionRequest(long remoteId) {
                     E4steamClient.LOGGER.debug("Steam Networking Messages session requested by {}", Long.toUnsignedString(remoteId));
                     SteamNetworkingMessagesTransport current = SteamRuntime.this.transport;
                     if (current != null) {
                        synchronized (SteamRuntime.this.peerSessionLock) {
                           SteamLobbyManager currentSocial = SteamRuntime.this.lobbyManager;
                           boolean bridgeExists = SteamRuntime.this.hasBridgeForRemote(remoteId);
                           boolean hosting = SteamRuntime.this.hostRegistration != null;
                           boolean knownSocialPeer = currentSocial != null && currentSocial.mayAcceptPeer(remoteId);
                           if (!SteamRuntime.shouldAcceptSessionRequest(bridgeExists, hosting, knownSocialPeer)) {
                              current.closePeer(remoteId);
                           } else if (!bridgeExists && SteamRuntime.this.pendingPeers.size() >= 64) {
                              current.closePeer(remoteId);
                           } else if (!current.accept(remoteId)) {
                              current.closePeer(remoteId);
                           } else {
                              SteamRuntime.this.knownPeerSessionGate.observeNewSession(remoteId);
                              if (!bridgeExists) {
                                 SteamRuntime.this.pendingPeers.put(remoteId, System.currentTimeMillis() + 10000L);
                              }
                           }
                        }
                     }
                  }

                  @Override
                  public void onSessionFailed(long remoteId, int endReason, String detail) {
                     if (detail.trim().isEmpty()) {
                        E4steamClient.LOGGER.warn("Steam Networking Messages session with {} failed (reason {})", Long.toUnsignedString(remoteId), endReason);
                     } else {
                        E4steamClient.LOGGER
                           .warn(
                              "Steam Networking Messages session with {} failed (reason {}): {}",
                              new Object[]{Long.toUnsignedString(remoteId), endReason, detail}
                           );
                     }

                     ArrayList<SteamConnectionBridge> failedBridges = new ArrayList<>();
                     synchronized (SteamRuntime.this.peerSessionLock) {
                        SteamRuntime.this.pendingPeers.remove(remoteId);
                        SteamRuntime.this.idleSessionDeadlines.remove(remoteId);
                        SteamRuntime.this.knownPeerSessionGate.defer(remoteId, System.currentTimeMillis());

                        for (SteamConnectionBridge bridge : SteamRuntime.this.bridgeRegistry.snapshot()) {
                           if (bridge.remoteSteamId() == remoteId) {
                              failedBridges.add(bridge);
                           }
                        }
                     }

                     SteamNetworkingMessagesTransport current = SteamRuntime.this.transport;
                     if (current != null) {
                        current.closePeer(remoteId);
                     }

                     for (SteamConnectionBridge bridgex : failedBridges) {
                        bridgex.close(false);
                     }
                  }
               }
            );
            this.lobbyManager = new SteamLobbyManager(this);
         } else {
            createdUser.dispose();
            throw new IOException("Steam returned an invalid user ID");
         }
      }
   }

   static boolean shouldAcceptSessionRequest(boolean bridgeExists, boolean hosting, boolean knownSocialPeer) {
      return bridgeExists || hosting || knownSocialPeer;
   }

   static boolean autoRestartsBrokenSession(SteamOutboundQueue.Kind kind, boolean platformRequiresRestart) {
      return kind != SteamOutboundQueue.Kind.DEDICATED_OPEN && kind != SteamOutboundQueue.Kind.DEDICATED_OPEN_ACK
         ? platformRequiresRestart && (kind == SteamOutboundQueue.Kind.OPEN || kind == SteamOutboundQueue.Kind.OPEN_ACK)
         : true;
   }

   private void ensureAppIdFile() throws IOException {
      Path appIdFile = Paths.get(System.getProperty("user.dir"), "steam_appid.txt").toAbsolutePath().normalize();
      if (Files.exists(appIdFile)) {
         String value = new String(Files.readAllBytes(appIdFile), StandardCharsets.US_ASCII).trim();
         if (!Integer.toString(480).equals(value)) {
            throw new IOException("Refusing to overwrite " + appIdFile + "; expected App ID 480 but found '" + value + "'");
         }
      } else {
         Files.write(
            appIdFile,
            (Integer.toString(480) + System.lineSeparator()).getBytes(StandardCharsets.US_ASCII),
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE
         );
         E4steamClient.LOGGER.info("Created {} for Steam App ID {}", appIdFile, 480);
      }
   }

   private void verifyRunning() throws IOException {
      if (this.status != SteamRuntime.Status.RUNNING || this.transport == null || this.isWorkerStopping()) {
         throw new IOException("Steam runtime is not running (status: " + this.status + ")");
      }
   }

   private boolean enqueueData(SteamConnectionBridge bridge, byte[] packet) {
      return this.status == SteamRuntime.Status.RUNNING && !this.isWorkerStopping() && !bridge.isClosed()
         ? this.outbound.offerData(bridge.remoteSteamId(), bridge.connectionId(), packet, bridge)
         : false;
   }

   private boolean enqueueControl(long remoteSteamId, int connectionId, byte[] packet, SteamOutboundQueue.Kind kind, SteamConnectionBridge bridge) {
      if (this.status != SteamRuntime.Status.RUNNING || this.isWorkerStopping()) {
         return false;
      } else {
         return bridge != null && kind != SteamOutboundQueue.Kind.RESET && bridge.isClosed()
            ? false
            : this.outbound.offerControl(remoteSteamId, connectionId, packet, kind, bridge);
      }
   }

   private void drainOutbound(ByteBuffer buffer, long workerGeneration) throws Exception {
      SteamNetworkingMessagesTransport current = Objects.requireNonNull(this.transport);
      int resetWork = this.drainResetRetries(current, buffer, workerGeneration, 8);

      for (int sent = resetWork; sent < 32; sent++) {
         long now = System.currentTimeMillis();
         SteamOutboundQueue.Packet<SteamConnectionBridge> packet = this.retryOutboundPacket;
         if (packet != null && now < this.retryOutboundNotBeforeMillis) {
            return;
         }

         if (packet == null) {
            packet = this.outbound.poll();
         }

         if (packet == null) {
            return;
         }

         SteamBridgeRegistry.Key key = new SteamBridgeRegistry.Key(packet.remoteSteamId(), packet.connectionId());
         SteamConnectionBridge currentBridge = this.bridgeRegistry.get(key);
         if (!this.isPacketCurrent(packet, currentBridge)) {
            this.clearRetriedPacket(packet);
         } else if (packet == this.retryOutboundPacket && now >= this.retryOutboundDeadlineMillis) {
            E4steamClient.LOGGER
               .warn(
                  "Steam outbound stalled for 30s: kind={}, side={}, ready={}, dataReady={}",
                  new Object[]{
                     packet.kind(),
                     packet.bridge() == null ? "none" : (packet.bridge().isHostSide() ? "host" : "guest"),
                     packet.bridge() == null || packet.bridge().isOutboundReady(now),
                     packet.bridge() == null || packet.bridge().isOutboundDataReady(now)
                  }
               );
            this.clearRetriedPacket(packet);
            if (packet.bridge() != null) {
               packet.bridge().close(true);
            }
         } else {
            SteamConnectionBridge queuedBridge = packet.bridge();
            if (packet.kind() != SteamOutboundQueue.Kind.RESET && queuedBridge != null && !queuedBridge.isOutboundReady(now)) {
               queuedBridge.diagnosticWait("bridge-delay");
               if (this.retryOutboundPacket != packet) {
                  this.retryOutboundPacket = packet;
                  this.retryOutboundDeadlineMillis = now + 30000L;
               }

               this.retryOutboundNotBeforeMillis = now + 10L;
               return;
            }

            if (packet.kind() == SteamOutboundQueue.Kind.DATA && queuedBridge != null && !queuedBridge.isOutboundDataReady(now)) {
               queuedBridge.diagnosticWait("peer-or-addon-handshake");
               if (this.retryOutboundPacket != packet) {
                  this.retryOutboundPacket = packet;
                  this.retryOutboundDeadlineMillis = now + 30000L;
               }

               this.retryOutboundNotBeforeMillis = now + 10L;
               return;
            }

            if (packet.kind() == SteamOutboundQueue.Kind.DATA && current.isReliableQueueBackpressured(packet.remoteSteamId(), 262144, 250000L)) {
               if (queuedBridge != null) {
                  queuedBridge.diagnosticWait("steam-native-backpressure");
               }

               if (this.retryOutboundPacket != packet) {
                  this.retryOutboundPacket = packet;
                  this.retryOutboundDeadlineMillis = now + 30000L;
               }

               this.retryOutboundNotBeforeMillis = now + 10L;
               return;
            }

            if (packet.kind() == SteamOutboundQueue.Kind.DATA
               && queuedBridge != null
               && Agnos.paceMinecraftTrafficForLoader()
               && !this.reliableDataPacer.tryAcquire(queuedBridge, packet.payload().length, System.nanoTime())) {
               queuedBridge.diagnosticWait("forge-pacer");
               if (this.retryOutboundPacket != packet) {
                  this.retryOutboundPacket = packet;
                  this.retryOutboundDeadlineMillis = now + 30000L;
               }

               this.retryOutboundNotBeforeMillis = now + 5L;
               return;
            }

            buffer.clear();
            buffer.put(packet.payload()).flip();
            boolean restartBrokenSession = autoRestartsBrokenSession(packet.kind(), Agnos.autoRestartBrokenSteamSessionForHandshake());
            int result = this.sendTransportResult(
               current,
               packet.bridge(),
               packet.remoteSteamId(),
               buffer,
               packet.kind() == SteamOutboundQueue.Kind.DATAGRAM || packet.kind() == SteamOutboundQueue.Kind.ADDON_DATAGRAM,
               restartBrokenSession
            );
            boolean accepted = result == 1;
            SteamConnectionBridge packetBridge = packet.bridge();
            if (accepted) {
               if (packet.kind() == SteamOutboundQueue.Kind.DATA && packetBridge != null) {
                  packetBridge.diagnosticSent(packet.payload().length);
               }

               this.clearRetriedPacket(packet);
               if (packet.kind() == SteamOutboundQueue.Kind.DEDICATED_OPEN) {
                  E4steamClient.LOGGER.info("Submitted dedicated Steam authentication handshake");
               } else if (restartBrokenSession) {
                  E4steamClient.LOGGER
                     .info(
                        "Submitted Forge Steam {} frame for bridge {}:{}",
                        new Object[]{packet.kind(), Long.toUnsignedString(packet.remoteSteamId()), Integer.toUnsignedString(packet.connectionId())}
                     );
               }

               if (packet.kind() == SteamOutboundQueue.Kind.DATA && packetBridge != null && packetBridge.markFirstOutboundData()) {
                  E4steamClient.LOGGER
                     .info(
                        "Sent first Minecraft DATA frame ({} bytes) to Steam user {}", packet.payload().length, Long.toUnsignedString(packet.remoteSteamId())
                     );
               }

               if (packet.kind() == SteamOutboundQueue.Kind.RESET && packetBridge != null) {
                  packetBridge.markResetSubmitted();
               } else if (packet.kind() == SteamOutboundQueue.Kind.FIN && packetBridge != null) {
                  packetBridge.markFinSubmitted();
               }
            } else if (packet.kind() != SteamOutboundQueue.Kind.DATAGRAM && packet.kind() != SteamOutboundQueue.Kind.ADDON_DATAGRAM) {
               SteamResult failure = steamResult(result);
               if (isRetryableSendFailure(failure)) {
                  if (packet.kind() == SteamOutboundQueue.Kind.RESET) {
                     SteamResetRetryQueue.Offer<SteamConnectionBridge> admitted = this.resetRetries
                        .offerAfterTemporaryFailure(packet.remoteSteamId(), packet.connectionId(), packet.payload(), packetBridge, workerGeneration, now);
                     this.clearRetriedPacket(packet);
                     if (admitted.status() == SteamResetRetryQueue.OfferStatus.FULL) {
                        E4steamClient.LOGGER
                           .warn("RESET retry capacity exhausted for Steam user {}; closing the stale bridge", Long.toUnsignedString(packet.remoteSteamId()));
                        if (packetBridge != null) {
                           packetBridge.markResetSubmitted();
                        }
                     }
                     continue;
                  }

                  if (this.retryOutboundPacket != packet) {
                     this.retryOutboundPacket = packet;
                     this.retryOutboundDeadlineMillis = now + 30000L;
                     if (packetBridge != null) {
                        packetBridge.diagnosticWait("native-send-result-" + result);
                     }

                     E4steamClient.LOGGER
                        .debug(
                           "Steam applied outbound backpressure for {}; preserving and retrying {}",
                           Long.toUnsignedString(packet.remoteSteamId()),
                           packet.kind()
                        );
                  }

                  if (now < this.retryOutboundDeadlineMillis) {
                     this.retryOutboundNotBeforeMillis = now + 25L;
                     return;
                  }

                  this.clearRetriedPacket(packet);
               }

               E4steamClient.LOGGER
                  .warn(
                     "Steam Networking Messages send to {} failed for {}: {} ({})",
                     new Object[]{Long.toUnsignedString(packet.remoteSteamId()), packet.kind(), failure, result}
                  );
               if (packetBridge != null) {
                  if (packet.kind() == SteamOutboundQueue.Kind.RESET) {
                     packetBridge.markResetSubmitted();
                  } else {
                     packetBridge.close(false);
                  }
               }
            }
         }
      }
   }

   private int sendTransportResult(
      SteamNetworkingMessagesTransport messages,
      SteamConnectionBridge bridge,
      long remoteSteamId,
      ByteBuffer payload,
      boolean unreliable,
      boolean restartBrokenSession
   ) throws IOException {
      if (bridge != null && bridge.dedicatedSessionGeneration() > 0L) {
         SteamNetworkingSocketsP2PTransport dedicated = this.dedicatedTransport;
         return dedicated == null ? 3 : dedicated.sendResult(remoteSteamId, payload, unreliable);
      } else {
         return messages.sendResult(remoteSteamId, payload, unreliable, restartBrokenSession, 480);
      }
   }

   private int drainResetRetries(SteamNetworkingMessagesTransport current, ByteBuffer buffer, long workerGeneration, int limit) throws Exception {
      int handled = 0;

      while (handled < limit) {
         long now = System.currentTimeMillis();
         SteamResetRetryQueue.Entry<SteamConnectionBridge> entry = this.resetRetries.poll(workerGeneration, now);
         if (entry == null) {
            return handled;
         }

         handled++;
         if (entry.state() != SteamResetRetryQueue.State.AWAITING_SEND) {
            this.finishResetRetry(entry, entry.state(), null, 0);
         } else {
            SteamBridgeRegistry.Key key = new SteamBridgeRegistry.Key(entry.remoteSteamId(), entry.connectionId());
            SteamConnectionBridge currentBridge = this.bridgeRegistry.get(key);
            boolean currentConnection = entry.bridge() == null ? currentBridge == null : currentBridge == entry.bridge();
            if (!currentConnection) {
               SteamResetRetryQueue.State state = this.resetRetries.cancelStaleConnection(entry);
               this.finishResetRetry(entry, state, null, 0);
            } else {
               buffer.clear();
               entry.putPayload(buffer);
               buffer.flip();
               int result = this.sendTransportResult(current, entry.bridge(), entry.remoteSteamId(), buffer, false, false);
               SteamResult failure = result == 1 ? null : steamResult(result);
               SteamResetRetryQueue.SendOutcome outcome = result == 1
                  ? SteamResetRetryQueue.SendOutcome.SUCCESS
                  : (isRetryableSendFailure(failure) ? SteamResetRetryQueue.SendOutcome.TEMPORARY_FAILURE : SteamResetRetryQueue.SendOutcome.PERMANENT_FAILURE);
               SteamResetRetryQueue.State state = this.resetRetries.complete(entry, outcome, now);
               if (state != SteamResetRetryQueue.State.RETRY_SCHEDULED) {
                  this.finishResetRetry(entry, state, failure, result);
               }
            }
         }
      }

      return handled;
   }

   private void finishResetRetry(SteamResetRetryQueue.Entry<SteamConnectionBridge> entry, SteamResetRetryQueue.State state, SteamResult failure, int resultCode) {
      if (state == SteamResetRetryQueue.State.EXHAUSTED) {
         E4steamClient.LOGGER
            .warn("RESET retry budget exhausted for Steam user {} after {} attempts", Long.toUnsignedString(entry.remoteSteamId()), entry.attempts());
      } else if (state == SteamResetRetryQueue.State.PERMANENT_FAILURE) {
         E4steamClient.LOGGER
            .warn("Steam permanently rejected RESET for user {}: {} ({})", new Object[]{Long.toUnsignedString(entry.remoteSteamId()), failure, resultCode});
      }

      SteamConnectionBridge bridge = entry.bridge();
      if (bridge != null) {
         bridge.markResetSubmitted();
      }
   }

   private void acceptKnownPeerSessions(long now) {
      if (now >= this.nextKnownPeerAcceptAtMillis) {
         this.nextKnownPeerAcceptAtMillis = now + 100L;
         SteamLobbyManager currentSocial = this.lobbyManager;
         SteamNetworkingMessagesTransport currentTransport = this.transport;
         if (currentSocial != null && currentTransport != null) {
            currentSocial.forEachKnownSessionPeer(
               remoteSteamId -> {
                  synchronized (this.peerSessionLock) {
                     if (this.knownPeerSessionGate
                        .mayProactivelyAccept(
                           remoteSteamId,
                           this.hasBridgeForRemote(remoteSteamId),
                           this.pendingPeers.containsKey(remoteSteamId),
                           this.pendingPeers.size() < 64,
                           now
                        )) {
                        if (currentTransport.accept(remoteSteamId)) {
                           this.knownPeerSessionGate.observeNewSession(remoteSteamId);
                           this.pendingPeers.put(remoteSteamId, now + 10000L);
                           E4steamClient.LOGGER.debug("Accepted Steam session for known lobby peer {}", Long.toUnsignedString(remoteSteamId));
                        }
                     }
                  }
               }
            );
         }
      }
   }

   private void clearOutbound() {
      this.outbound.clear();
      this.reliableDataPacer.clear();
      this.retryOutboundPacket = null;
      this.retryOutboundNotBeforeMillis = 0L;
      this.retryOutboundDeadlineMillis = 0L;

      for (SteamResetRetryQueue.Entry<SteamConnectionBridge> entry : new ArrayList<>(this.resetRetries.cancelAll())) {
         SteamConnectionBridge bridge = entry.bridge();
         if (bridge != null) {
            bridge.markResetSubmitted();
         }
      }
   }

   private void purgeOutbound(SteamConnectionBridge bridge) {
      this.outbound.purge(bridge);
      this.reliableDataPacer.remove(bridge);
      this.resetRetries.purge(bridge);
      SteamOutboundQueue.Packet<SteamConnectionBridge> retry = this.retryOutboundPacket;
      if (retry != null && retry.bridge() == bridge) {
         this.clearRetriedPacket(retry);
      }
   }

   private void clearRetriedPacket(SteamOutboundQueue.Packet<SteamConnectionBridge> packet) {
      if (this.retryOutboundPacket == packet) {
         this.retryOutboundPacket = null;
         this.retryOutboundNotBeforeMillis = 0L;
         this.retryOutboundDeadlineMillis = 0L;
      }
   }

   static boolean isRetryableSendFailure(SteamResult result) {
      return result == SteamResult.LimitExceeded
         || result == SteamResult.Busy
         || result == SteamResult.NoConnection
         || result == SteamResult.ServiceUnavailable;
   }

   static SteamResult steamResult(int resultCode) {
      if (resultCode < 0) {
         return SteamResult.UnknownErrorCode_NotImplementedByAPI;
      } else {
         SteamResult result = SteamResult.byValue(resultCode);
         return result == null ? SteamResult.UnknownErrorCode_NotImplementedByAPI : result;
      }
   }

   private boolean isPacketCurrent(SteamOutboundQueue.Packet<SteamConnectionBridge> packet, SteamConnectionBridge currentBridge) {
      SteamConnectionBridge packetBridge = packet.bridge();
      if (packetBridge == null) {
         return packet.kind() == SteamOutboundQueue.Kind.RESET && currentBridge == null;
      } else {
         return currentBridge != packetBridge ? false : packet.kind() == SteamOutboundQueue.Kind.RESET || !packetBridge.isClosed();
      }
   }

   private void receivePackets(ByteBuffer buffer) throws Exception {
      SteamNetworkingMessagesTransport current = Objects.requireNonNull(this.transport);
      int receivedDataBytes = 0;

      for (int received = 0; received < 32; received++) {
         int size = current.availablePacketSize(480);
         if (size == 0) {
            return;
         }

         if (size < 0) {
            throw new IOException("Steam reported an invalid P2P packet size: " + size);
         }

         if (size > 1048576) {
            current.discardPendingMessage();
            E4steamClient.LOGGER.debug("Discarded an oversized foreign Steam packet ({} bytes)", size);
         } else {
            buffer.clear();
            SteamNetworkingMessagesTransport.Received packet = current.receive(buffer, 480);
            int read = packet.size();
            if (read > 0 && read <= SteamProtocol.MAX_PACKET_SIZE && packet.remoteSteamId() != 0L) {
               buffer.position(0);
               buffer.limit(read);
               SteamProtocol.Frame frame = SteamProtocol.decode(buffer);
               if (frame != null) {
                  this.dispatchFrame(packet.remoteSteamId(), frame);
                  if (frame.type() == 2) {
                     receivedDataBytes += frame.payload().length;
                     if (receivedDataBytes >= 131072) {
                        return;
                     }
                  }
               }
            }
         }
      }
   }

   private void receiveDedicatedPackets(ByteBuffer buffer) throws Exception {
      SteamNetworkingSocketsP2PTransport current = this.dedicatedTransport;
      if (current != null) {
         for (int received = 0; received < 32; received++) {
            int size = current.availablePacketSize();
            if (size == 0) {
               return;
            }

            if (size >= 0 && size <= 1048576) {
               buffer.clear();
               SteamNetworkingSocketsP2PTransport.Received packet = current.receive(buffer);
               int read = packet.size();
               if (read > 0 && read <= SteamProtocol.MAX_PACKET_SIZE && packet.remoteSteamId() != 0L) {
                  buffer.position(0);
                  buffer.limit(read);
                  SteamProtocol.Frame frame = SteamProtocol.decode(buffer);
                  if (frame != null) {
                     this.dispatchFrame(packet.remoteSteamId(), frame);
                  }
               }
            } else {
               current.discardPendingMessage();
            }
         }
      }
   }

   private void dispatchFrame(long remoteSteamId, SteamProtocol.Frame frame) {
      SteamBridgeRegistry.Key key = new SteamBridgeRegistry.Key(remoteSteamId, frame.connectionId());
      boolean handshakeDiagnostics = Agnos.autoRestartBrokenSteamSessionForHandshake();
      if (handshakeDiagnostics && (frame.type() == 1 || frame.type() == 6)) {
         E4steamClient.LOGGER
            .info(
               "Received Steam {} frame for bridge {}:{}",
               new Object[]{frame.type() == 1 ? "OPEN" : "OPEN_ACK", Long.toUnsignedString(remoteSteamId), Integer.toUnsignedString(frame.connectionId())}
            );
      }

      switch (frame.type()) {
         case 1:
            this.handleOpen(remoteSteamId, key, frame.payload());
            break;
         case 2:
            SteamConnectionBridge bridgexxx = this.bridgeRegistry.get(key);
            if (bridgexxx != null) {
               if (bridgexxx.markFirstInboundData()) {
                  E4steamClient.LOGGER
                     .info("Received first Minecraft DATA frame ({} bytes) from Steam user {}", frame.payload().length, Long.toUnsignedString(remoteSteamId));
               }

               bridgexxx.acceptSteamData(frame.payload());
            } else if (handshakeDiagnostics) {
               E4steamClient.LOGGER
                  .warn(
                     "Ignored DATA for unknown Steam bridge {}:{} ({} bytes)",
                     new Object[]{Long.toUnsignedString(remoteSteamId), Integer.toUnsignedString(frame.connectionId()), frame.payload().length}
                  );
            }
            break;
         case 3:
            SteamConnectionBridge bridgexx = this.bridgeRegistry.get(key);
            if (bridgexx != null) {
               bridgexx.acceptRemoteFin();
            }
            break;
         case 4:
            SteamConnectionBridge bridgex = this.bridgeRegistry.get(key);
            if (bridgex != null) {
               bridgex.resetFromRemote();
            }
            break;
         case 5:
            SteamUdpBridge bridge = this.bridgeRegistry.getUdp(key);
            if (bridge != null) {
               bridge.acceptSteamDatagram(frame.payload());
            }
            break;
         case 6:
            this.handleOpenAck(key, frame.payload());
            break;
         case 7:
            this.handleBridgeReady(key);
         case 8:
         default:
            break;
         case 9:
            this.handleDedicatedOpenAck(key, frame.payload());
            break;
         case 10:
         case 11:
            SteamConnectionBridge bridgexxxx = this.bridgeRegistry.get(key);
            if (bridgexxxx != null && !bridgexxxx.isClosed()) {
               SteamAddonHooks.accept(bridgexxxx, frame.type(), frame.payload());
            }
      }
   }

   private void handleOpenAck(SteamBridgeRegistry.Key key, byte[] payload) {
      SteamConnectionBridge bridge = this.bridgeRegistry.get(key);
      if (bridge != null && !bridge.isHostSide() && !bridge.isClosed()) {
         ByteBuffer buffer = ByteBuffer.wrap(payload);
         byte clientPortMode = buffer.get();
         int hostPort = Short.toUnsignedInt(buffer.getShort());

         try {
            this.startClientUdpBridge(bridge, VoiceChatUdpEndpoint.fromHandshake(hostPort, clientPortMode));
         } catch (IllegalArgumentException var8) {
            bridge.close(true);
            return;
         }

         if (!this.sendBridgeReady(bridge)) {
            E4steamClient.LOGGER
               .debug(
                  "Could not queue Steam bridge-ready confirmation for {}:{}; host fallback will be used",
                  Long.toUnsignedString(key.remoteSteamId()),
                  Integer.toUnsignedString(key.connectionId())
               );
         }

         bridge.markPeerReady();
      }
   }

   private void handleDedicatedOpenAck(SteamBridgeRegistry.Key key, byte[] payload) {
      SteamConnectionBridge bridge = this.bridgeRegistry.get(key);
      if (bridge != null && !bridge.isHostSide() && !bridge.isClosed() && payload.length == 8) {
         long generation = ByteBuffer.wrap(payload).getLong();
         if (generation > 0L && bridge.dedicatedSessionGeneration() == generation) {
            SteamAddonHooks.bridgeReady(bridge);
            bridge.markPeerReady();
            bridge.start();
         } else {
            bridge.close(true);
         }
      }
   }

   private void handleBridgeReady(SteamBridgeRegistry.Key key) {
      SteamConnectionBridge bridge = this.bridgeRegistry.get(key);
      if (bridge != null && bridge.isHostSide() && !bridge.isClosed()) {
         bridge.markPeerReady();
         if (Agnos.autoRestartBrokenSteamSessionForHandshake()) {
            E4steamClient.LOGGER
               .info("Steam bridge is ready for Minecraft data {}:{}", Long.toUnsignedString(key.remoteSteamId()), Integer.toUnsignedString(key.connectionId()));
         }
      }
   }

   private void handleOpen(long remoteSteamId, SteamBridgeRegistry.Key key, byte[] token) {
      SteamRuntime.HostRegistration registration = this.hostRegistration;
      SteamLobbyManager currentSocial = this.lobbyManager;
      boolean peerAllowed = registration != null && currentSocial != null && currentSocial.allows(registration.owner(), remoteSteamId);
      SteamInvitationAuthorizer.Decision authorization = SteamInvitationAuthorizer.authorize(
         registration == null ? null : registration.token(), token, peerAllowed
      );
      if (authorization != SteamInvitationAuthorizer.Decision.ALLOWED) {
         this.sendStandaloneReset(remoteSteamId, key.connectionId());
      } else if (!this.bridgeRegistry.contains(key)) {
         if (!this.hasAvailableHostSlot(registration) || System.currentTimeMillis() < this.nextLoopbackConnectAttemptAtMillis) {
            this.sendStandaloneReset(remoteSteamId, key.connectionId());
         } else if (registration.owner().accessMode() != SteamAccessMode.CUSTOM) {
            this.completeAuthorizedOpen(remoteSteamId, key, registration);
         } else if (!SteamCustomAccessGate.isRegistered(registration.owner().customAccessModeId())) {
            this.sendStandaloneReset(remoteSteamId, key.connectionId());
         } else if (this.pendingCustomAccessEvaluations.add(key)) {
            SteamCustomAccessGate.evaluate(registration.owner().customAccessModeId(), remoteSteamId).whenComplete((allowed, failure) -> {
               CompletableFuture<Void> resumed = this.submitSteamTaskIfRunning(() -> {
                  try {
                     if (failure == null && Boolean.TRUE.equals(allowed) && this.hostRegistration == registration) {
                        this.completeAuthorizedOpen(remoteSteamId, key, registration);
                     } else {
                        this.sendStandaloneReset(remoteSteamId, key.connectionId());
                     }
                  } finally {
                     this.pendingCustomAccessEvaluations.remove(key);
                  }

                  return null;
               });
               resumed.whenComplete((ignored, submitFailure) -> {
                  if (submitFailure != null) {
                     this.pendingCustomAccessEvaluations.remove(key);
                  }
               });
            });
         }
      }
   }

   private boolean hasAvailableHostSlot(SteamRuntime.HostRegistration registration) {
      if (registration != null && this.hostRegistration == registration) {
         long activeHostConnections = 0L;

         for (SteamConnectionBridge bridge : this.bridgeRegistry.snapshot()) {
            if (bridge.isHostedBy(registration.owner()) && !bridge.isClosed()) {
               activeHostConnections++;
            }
         }

         return activeHostConnections < LanSettings.steamGuestLimit();
      } else {
         return false;
      }
   }

   private void completeAuthorizedOpen(long remoteSteamId, SteamBridgeRegistry.Key key, SteamRuntime.HostRegistration registration) {
      if (registration != null && this.hostRegistration == registration && !this.bridgeRegistry.contains(key)) {
         if (this.hasAvailableHostSlot(registration) && System.currentTimeMillis() >= this.nextLoopbackConnectAttemptAtMillis) {
            synchronized (this.peerSessionLock) {
               this.pendingPeers.remove(remoteSteamId);
               this.idleSessionDeadlines.remove(remoteSteamId);
            }

            Socket socket = new Socket();
            boolean handedOff = false;

            try {
               socket.connect(new InetSocketAddress("127.0.0.1", registration.localPort()), 100);
               socket.setTcpNoDelay(true);
               socket.setKeepAlive(true);
               this.nextLoopbackConnectAttemptAtMillis = 0L;
               if (this.hostRegistration != registration || this.status != SteamRuntime.Status.RUNNING || this.isWorkerStopping()) {
                  this.sendStandaloneReset(remoteSteamId, key.connectionId());
                  return;
               }

               SteamConnectionBridge bridge = new SteamConnectionBridge(this, remoteSteamId, key.connectionId(), socket, registration.owner(), null);
               SteamBridgeRegistry.Registration result = this.registerBridge(key, bridge);
               if (result != SteamBridgeRegistry.Registration.REGISTERED) {
                  if (result != SteamBridgeRegistry.Registration.COLLISION) {
                     this.sendStandaloneReset(remoteSteamId, key.connectionId());
                  }

                  return;
               }

               SteamRuntime.AuthenticatedLoopbackPeer authenticatedPeer = new SteamRuntime.AuthenticatedLoopbackPeer(remoteSteamId, bridge);
               SteamRuntime.AuthenticatedLoopbackPeer previousPeer = this.authenticatedLoopbackPeers
                  .compute(
                     bridge.localPort(),
                     (port, existing) -> (SteamRuntime.AuthenticatedLoopbackPeer)(existing != null && !existing.bridge().isClosed()
                        ? existing
                        : authenticatedPeer)
                  );
               if (previousPeer == null || previousPeer.bridge() == bridge) {
                  handedOff = true;
                  if (this.hostRegistration != registration) {
                     bridge.close(true);
                     return;
                  }

                  VoiceChatUdpEndpoint udpEndpoint = VoiceChatUdpEndpoint.resolve(registration.localPort(), registration.udpEndpoint().hostPort());
                  this.startHostUdpBridge(bridge, udpEndpoint);
                  bridge.waitForPeerReadyUntil(System.currentTimeMillis() + 2000L);
                  if (!this.sendOpenAck(bridge, udpEndpoint)) {
                     bridge.close(true);
                     return;
                  }

                  SteamAddonHooks.bridgeReady(bridge);
                  bridge.start();
                  E4steamClient.LOGGER.info("Accepted Steam bridge from {}", Long.toUnsignedString(remoteSteamId));
                  return;
               }

               bridge.close(true);
               E4steamClient.LOGGER.warn("Refused Steam bridge because localhost port {} is already authenticated", bridge.localPort());
            } catch (IOException var28) {
               this.nextLoopbackConnectAttemptAtMillis = System.currentTimeMillis() + 2000L;
               this.sendStandaloneReset(remoteSteamId, key.connectionId());
               E4steamClient.LOGGER.warn("Could not connect a Steam guest to the local LAN server", var28);
               return;
            } finally {
               if (!handedOff) {
                  try {
                     socket.close();
                  } catch (IOException var26) {
                  }
               }
            }
         } else {
            this.sendStandaloneReset(remoteSteamId, key.connectionId());
         }
      } else {
         if (this.hostRegistration != registration) {
            this.sendStandaloneReset(remoteSteamId, key.connectionId());
         }
      }
   }

   private void closeHostBridges(SteamSession owner) {
      for (SteamConnectionBridge bridge : this.bridgeRegistry.snapshot()) {
         if (bridge.isHostedBy(owner)) {
            bridge.close(true);
         }
      }
   }

   void closeRemoteBridges(long remoteSteamId) {
      for (SteamConnectionBridge bridge : this.bridgeRegistry.snapshot()) {
         if (bridge.remoteSteamId() == remoteSteamId) {
            bridge.close(true);
         }
      }
   }

   private SteamBridgeRegistry.Registration registerBridge(SteamBridgeRegistry.Key key, SteamConnectionBridge bridge) {
      synchronized (this.peerSessionLock) {
         SteamBridgeRegistry.Registration result = this.bridgeRegistry
            .register(key, bridge, () -> this.status == SteamRuntime.Status.RUNNING && !this.isWorkerStopping());
         if (result != SteamBridgeRegistry.Registration.REGISTERED) {
            return result;
         } else {
            this.pendingPeers.remove(key.remoteSteamId());
            this.knownPeerSessionGate.observeNewSession(key.remoteSteamId());
            this.idleSessionDeadlines.remove(key.remoteSteamId());
            return SteamBridgeRegistry.Registration.REGISTERED;
         }
      }
   }

   private boolean hasBridgeForRemote(long remoteSteamId) {
      for (SteamConnectionBridge bridge : this.bridgeRegistry.snapshot()) {
         if (bridge.remoteSteamId() == remoteSteamId) {
            return true;
         }
      }

      return false;
   }

   boolean hasClientBridgeForRemote(long remoteSteamId) {
      for (SteamConnectionBridge bridge : this.bridgeRegistry.snapshot()) {
         if (!bridge.isHostSide() && bridge.remoteSteamId() == remoteSteamId) {
            return true;
         }
      }

      return false;
   }

   private void closeSteamSessionIfIdle(long remoteSteamId) {
      synchronized (this.peerSessionLock) {
         if (!this.hasBridgeForRemote(remoteSteamId)) {
            this.pendingPeers.remove(remoteSteamId);
            this.idleSessionDeadlines.remove(remoteSteamId);
            this.closeSteamSession(remoteSteamId);
         }
      }
   }

   private void cleanupPeerSessions() {
      long now = System.currentTimeMillis();
      this.pendingPeers.forEach((remoteSteamId, deadline) -> {
         if (deadline <= now) {
            synchronized (this.peerSessionLock) {
               if (!this.hasBridgeForRemote(remoteSteamId) && this.pendingPeers.remove(remoteSteamId, deadline)) {
                  this.closeSteamSession(remoteSteamId);
               }
            }
         }
      });
      this.idleSessionDeadlines.forEach((remoteSteamId, deadline) -> {
         if (deadline[0] <= now) {
            synchronized (this.peerSessionLock) {
               if (this.idleSessionDeadlines.get(remoteSteamId) != deadline) {
                  return;
               }

               if (this.hasBridgeForRemote(remoteSteamId)) {
                  this.idleSessionDeadlines.remove(remoteSteamId);
               } else if (now < deadline[1] && this.hasQueuedSteamPackets(remoteSteamId)) {
                  this.idleSessionDeadlines.put(remoteSteamId, new long[]{now + 100L, deadline[1]});
               } else {
                  this.idleSessionDeadlines.remove(remoteSteamId);
                  this.closeSteamSession(remoteSteamId);
               }
            }
         }
      });
   }

   private void cleanupGracefulBridgeClosures(long nowMillis) {
      for (SteamConnectionBridge bridge : this.bridgeRegistry.snapshot()) {
         if (bridge.diagnosticDue(nowMillis)) {
            SteamNetworkingMessagesTransport current = this.transport;
            bridge.diagnosticSnapshot(nowMillis, current == null ? "unavailable" : current.diagnosticState(bridge.remoteSteamId()));
         }

         bridge.closeIfGracefulCloseTimedOut(nowMillis);
      }
   }

   private long[] newIdleSessionDeadline() {
      long now = System.currentTimeMillis();
      return new long[]{now + 250L, now + 2000L};
   }

   private boolean hasQueuedSteamPackets(long remoteSteamId) {
      SteamNetworkingMessagesTransport current = this.transport;
      return current == null ? false : current.hasQueuedPackets(remoteSteamId);
   }

   private void closeSteamSession(long remoteSteamId) {
      this.knownPeerSessionGate.defer(remoteSteamId, System.currentTimeMillis());
      SteamNetworkingMessagesTransport current = this.transport;
      if (current != null) {
         current.closePeer(remoteSteamId);
      }
   }

   public static final class Activity implements AutoCloseable {
      private final SteamRuntime runtime;
      private final AtomicBoolean closed = new AtomicBoolean();

      private Activity(SteamRuntime runtime) {
         this.runtime = runtime;
      }

      @Override
      public void close() {
         if (this.closed.compareAndSet(false, true)) {
            this.runtime.releaseActivity();
         }
      }
   }

   private static final class AuthenticatedLoopbackPeer {
      private final long remoteSteamId;
      private final SteamConnectionBridge bridge;

      private AuthenticatedLoopbackPeer(long remoteSteamId, SteamConnectionBridge bridge) {
         this.remoteSteamId = remoteSteamId;
         this.bridge = bridge;
      }

      private long remoteSteamId() {
         return this.remoteSteamId;
      }

      private SteamConnectionBridge bridge() {
         return this.bridge;
      }
   }

   static final class DedicatedClientAuth implements AutoCloseable {
      private final SteamRuntime runtime;
      private final SteamAuthTicket handle;
      private final AtomicBoolean closed = new AtomicBoolean();
      private byte[] proof;
      private final byte[] nonce;

      private DedicatedClientAuth(SteamRuntime runtime, SteamAuthTicket handle, byte[] proof, byte[] nonce) {
         this.runtime = runtime;
         this.handle = handle;
         this.proof = proof;
         this.nonce = nonce;
      }

      synchronized byte[] takeProof() {
         byte[] current = this.proof;
         this.proof = null;
         return current == null ? new byte[0] : current;
      }

      byte[] nonce() {
         return (byte[])this.nonce.clone();
      }

      @Override
      public synchronized void close() {
         if (this.closed.compareAndSet(false, true)) {
            if (this.proof != null) {
               Arrays.fill(this.proof, (byte)0);
               this.proof = null;
            }

            Arrays.fill(this.nonce, (byte)0);
            this.runtime.cancelDedicatedAuthTicket(this.handle);
         }
      }

      @Override
      public String toString() {
         return "DedicatedClientAuth{credentials=redacted}";
      }
   }

   private static final class HostRegistration {
      private final SteamSession owner;
      private final int localPort;
      private final VoiceChatUdpEndpoint udpEndpoint;
      private final byte[] token;
      private final SteamAccessMode accessMode;

      private HostRegistration(SteamSession owner, int localPort, VoiceChatUdpEndpoint udpEndpoint, byte[] token, SteamAccessMode accessMode) {
         this.owner = owner;
         this.localPort = localPort;
         this.udpEndpoint = udpEndpoint;
         this.token = token;
         this.accessMode = accessMode;
      }

      private SteamSession owner() {
         return this.owner;
      }

      private int localPort() {
         return this.localPort;
      }

      private VoiceChatUdpEndpoint udpEndpoint() {
         return this.udpEndpoint;
      }

      private byte[] token() {
         return this.token;
      }

      private SteamAccessMode accessMode() {
         return this.accessMode;
      }
   }

   public static final class PublicHostLobbyTarget {
      private final long lobbyId;
      private final long generation;
      private final String accessMode;

      private PublicHostLobbyTarget(long lobbyId, long generation, String accessMode) {
         this.lobbyId = lobbyId;
         this.generation = generation;
         this.accessMode = accessMode;
      }

      public String accessMode() {
         return this.accessMode;
      }

      public long lobbyId() {
         return this.lobbyId;
      }

      public long generation() {
         return this.generation;
      }

      @Override
      public String toString() {
         return "PublicHostLobbyTarget{generation=" + this.generation + ", lobby=opaque}";
      }
   }

   public static final class SafeMinecraftIdentity {
      private final UUID minecraftUuid;
      private final String minecraftName;

      private SafeMinecraftIdentity(UUID minecraftUuid, String minecraftName) {
         this.minecraftUuid = minecraftUuid;
         this.minecraftName = minecraftName;
      }

      public UUID minecraftUuid() {
         return this.minecraftUuid;
      }

      public String minecraftName() {
         return this.minecraftName;
      }

      @Override
      public String toString() {
         return "SafeMinecraftIdentity{uuid=" + this.minecraftUuid + "}";
      }
   }

   public static final class SafePeerIdentity {
      private final String opaquePeerId;
      private final UUID minecraftUuid;
      private final String minecraftName;

      private SafePeerIdentity(String opaquePeerId, UUID minecraftUuid, String minecraftName) {
         this.opaquePeerId = opaquePeerId;
         this.minecraftUuid = minecraftUuid;
         this.minecraftName = minecraftName;
      }

      public String opaquePeerId() {
         return this.opaquePeerId;
      }

      public UUID minecraftUuid() {
         return this.minecraftUuid;
      }

      public String minecraftName() {
         return this.minecraftName;
      }

      @Override
      public String toString() {
         return "SafePeerIdentity{opaque}";
      }
   }

   public static final class SafePublicJoinSnapshot {
      private final String stateCode;
      private final String detailCode;

      private SafePublicJoinSnapshot(String stateCode, String detailCode) {
         this.stateCode = stateCode == null ? "IDLE" : stateCode;
         this.detailCode = detailCode == null ? "" : detailCode;
      }

      public String stateCode() {
         return this.stateCode;
      }

      public String detailCode() {
         return this.detailCode;
      }

      @Override
      public String toString() {
         return "SafePublicJoinSnapshot{state=" + this.stateCode + ", target=opaque}";
      }
   }

   public static final class SafeSessionView {
      private final String sessionId;
      private final long generation;
      private final String roleCode;
      private final String stateCode;
      private final int capacity;
      private final List<SteamRuntime.SafePeerIdentity> peers;

      private SafeSessionView(String sessionId, long generation, String roleCode, String stateCode, int capacity, List<SteamRuntime.SafePeerIdentity> peers) {
         this.sessionId = sessionId;
         this.generation = generation;
         this.roleCode = roleCode;
         this.stateCode = stateCode;
         this.capacity = capacity;
         this.peers = Collections.unmodifiableList(new ArrayList<>(peers));
      }

      private static SteamRuntime.SafeSessionView inactive() {
         return new SteamRuntime.SafeSessionView("", 0L, "NONE", "NONE", 0, Collections.emptyList());
      }

      public boolean active() {
         return this.generation > 0L;
      }

      public String sessionId() {
         return this.sessionId;
      }

      public long generation() {
         return this.generation;
      }

      public String roleCode() {
         return this.roleCode;
      }

      public String stateCode() {
         return this.stateCode;
      }

      public int capacity() {
         return this.capacity;
      }

      public List<SteamRuntime.SafePeerIdentity> peers() {
         return this.peers;
      }

      @Override
      public String toString() {
         return "SafeSessionView{generation="
            + this.generation
            + ", role="
            + this.roleCode
            + ", state="
            + this.stateCode
            + ", peers="
            + this.peers.size()
            + "}";
      }
   }

   private static enum Status {
      NEW,
      STARTING,
      RUNNING,
      STOPPING,
      FAILED,
      STOPPED;
   }

   private static final class SteamTask<T> {
      private final Callable<T> action;
      private final CompletableFuture<T> result = new CompletableFuture<>();

      private SteamTask(Callable<T> action) {
         this.action = action;
      }

      private void run() {
         try {
            this.result.complete(this.action.call());
         } catch (Throwable var2) {
            this.result.completeExceptionally(var2);
         }
      }

      private void fail(Throwable throwable) {
         this.result.completeExceptionally(throwable);
      }
   }

   private static final class WorkerGeneration {
      private final long id;
      private final CompletableFuture<Void> ready = new CompletableFuture<>();
      private final AtomicBoolean stopRequested = new AtomicBoolean();
      private volatile Thread worker;
      private long idleSinceMillis;

      private WorkerGeneration(long id) {
         this.id = id;
      }
   }
}
