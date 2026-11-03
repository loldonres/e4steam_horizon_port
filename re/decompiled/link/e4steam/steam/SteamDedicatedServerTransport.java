package link.e4steam.steam;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
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
import link.e4steam.api.identity.IdentityService;
import link.e4steam.api.session.SessionService;
import link.e4steam.internal.dedicated.DedicatedAdmissionGate;
import link.e4steam.internal.dedicated.DedicatedServerController;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class SteamDedicatedServerTransport implements SteamBridgeRuntime, AutoCloseable {
   private static final Logger LOGGER = LogManager.getLogger("e4steam");
   private static final int CHANNEL = 480;
   private static final int MAX_PACKETS_PER_TICK = 64;
   private static final int LOOPBACK_CONNECT_TIMEOUT_MILLIS = 1000;
   private static final long ADMISSION_TIMEOUT_SECONDS = 16L;
   private static final long PENDING_PEER_TTL_MILLIS = 20000L;
   private static final long REJECT_CLOSE_DELAY_MILLIS = 100L;
   private final SteamGameServerRuntimeBackend backend;
   private final DedicatedServerController controller;
   private final DedicatedAdmissionGate admission;
   private final SteamBridgeRegistry<SteamConnectionBridge, AutoCloseable> bridges;
   private final SteamOutboundQueue<SteamConnectionBridge> outbound = new SteamOutboundQueue<>(2048, 1536, 0, 128, 256, 64);
   private final ConcurrentHashMap<Long, Long> pendingPeers = new ConcurrentHashMap<>();
   private final ConcurrentHashMap<Long, Long> peersAwaitingClose = new ConcurrentHashMap<>();
   private final Set<Long> activePeers = ConcurrentHashMap.newKeySet();
   private final ConcurrentHashMap<SteamConnectionBridge, AutoCloseable> ingress = new ConcurrentHashMap<>();
   private final ThreadPoolExecutor admissionExecutor;
   private final AtomicBoolean running = new AtomicBoolean();
   private volatile Thread packetThread;
   private volatile SteamOutboundQueue.Packet<SteamConnectionBridge> deferredPacket;
   private final SteamAddonNetworkRuntime.FrameSender addonSender = new SteamAddonNetworkRuntime.FrameSender() {
      @Override
      public boolean sendHello(SteamConnectionBridge bridge, byte[] packet) {
         return SteamDedicatedServerTransport.this.offerControl(bridge, packet, SteamOutboundQueue.Kind.ADDON_HELLO);
      }

      @Override
      public boolean sendData(SteamConnectionBridge bridge, byte[] packet, boolean reliable) {
         return SteamDedicatedServerTransport.this.running.get()
            && !bridge.isClosed()
            && SteamDedicatedServerTransport.this.outbound.offerAddonData(bridge.remoteSteamId(), bridge.connectionId(), packet, !reliable, bridge);
      }
   };

   public SteamDedicatedServerTransport(SteamGameServerRuntimeBackend backend, DedicatedServerController controller, int capacity) {
      this.backend = Objects.requireNonNull(backend, "backend");
      this.controller = Objects.requireNonNull(controller, "controller");
      this.bridges = new SteamBridgeRegistry<>(capacity);
      this.admissionExecutor = new ThreadPoolExecutor(
         1,
         Math.min(4, capacity),
         30L,
         TimeUnit.SECONDS,
         new ArrayBlockingQueue<>(Math.max(16, capacity * 2)),
         daemonFactory("e4steam-dedicated-admission"),
         new AbortPolicy()
      );
      this.admission = new DedicatedAdmissionGate(new DedicatedAdmissionGate.Authenticator() {
         @Override
         public CompletionStage<Boolean> authenticate(long steamId, byte[] ticket, long generation) {
            return SteamDedicatedServerTransport.this.backend.authenticate(steamId, ticket, generation);
         }
      }, new DedicatedAdmissionGate.CorePolicy() {
         @Override
         public boolean banned(long steamId) {
            return controller.isBanned(steamId);
         }

         @Override
         public boolean whitelistRequired() {
            return controller.whitelistRequired();
         }

         @Override
         public boolean whitelisted(long steamId) {
            return controller.isWhitelisted(steamId);
         }

         @Override
         public int players() {
            return SteamDedicatedServerTransport.this.activePeers.size();
         }

         @Override
         public int capacity() {
            return capacity;
         }

         @Override
         public CompletionStage<Boolean> addonPolicy(long steamId) {
            return CompletableFuture.completedFuture(true);
         }
      });
   }

   public void start() {
      if (this.running.compareAndSet(false, true)) {
         this.backend
            .peerListener(
               new SteamGameServerRuntimeBackend.PeerListener() {
                  @Override
                  public boolean onSessionRequest(long remoteSteamId) {
                     long now = System.currentTimeMillis();
                     SteamDedicatedServerTransport.this.expirePendingPeers(now);
                     if (remoteSteamId != 0L
                        && SteamDedicatedServerTransport.this.controller.accepting()
                        && !SteamDedicatedServerTransport.this.activePeers.contains(remoteSteamId)
                        && !SteamDedicatedServerTransport.this.peersAwaitingClose.containsKey(remoteSteamId)) {
                        Long existing = SteamDedicatedServerTransport.this.pendingPeers.get(remoteSteamId);
                        if (existing != null && existing > now) {
                           return true;
                        } else if (SteamDedicatedServerTransport.this.pendingPeers.size() >= SteamDedicatedServerTransport.this.controller.maxPeers() * 2) {
                           return false;
                        } else {
                           SteamDedicatedServerTransport.this.pendingPeers.put(remoteSteamId, now + 20000L);
                           return true;
                        }
                     } else {
                        return false;
                     }
                  }

                  @Override
                  public void onSessionFailed(long remoteSteamId, int reason, String detail) {
                     SteamDedicatedServerTransport.this.closeRemote(remoteSteamId);
                  }
               }
            );
         Thread thread = new Thread(this::runPackets, "e4steam-dedicated-packets");
         thread.setDaemon(true);
         this.packetThread = thread;
         thread.start();
      }
   }

   public Set<String> safeOpaquePeerIds() {
      long generation = this.controller.generation();
      if (generation <= 0L) {
         return Collections.emptySet();
      } else {
         TreeSet<String> peers = new TreeSet<>();

         for (Long steamId : this.activePeers) {
            if (steamId != null && steamId != 0L) {
               peers.add(SteamPeerPrivacy.opaquePeerId(generation, steamId));
            }
         }

         return Collections.unmodifiableSet(peers);
      }
   }

   public DedicatedServerController.DedicatedPeerIdentity safePeerIdentity(String opaquePeerId) {
      long generation = this.controller.generation();
      if (generation > 0L && opaquePeerId != null) {
         for (Long steamId : this.activePeers) {
            if (steamId != null && steamId != 0L) {
               long value = steamId;
               String opaque = SteamPeerPrivacy.opaquePeerId(generation, value);
               if (opaque.equals(opaquePeerId)) {
                  return new DedicatedServerController.DedicatedPeerIdentity(opaque, SteamMinecraftIdentity.uuid(value), SteamMinecraftIdentity.safeName(value));
               }
            }
         }

         return null;
      } else {
         return null;
      }
   }

   private void runPackets() {
      ByteBuffer buffer = ByteBuffer.allocateDirect(1048576);

      while (this.running.get()) {
         try {
            this.receive(buffer);
            this.expirePendingPeers(System.currentTimeMillis());
            this.closeRejectedPeers(System.currentTimeMillis());
            this.drainOutbound();
            SteamAddonNetworkRuntime.get().tick();
            Thread.sleep(2L);
         } catch (InterruptedException var3) {
            Thread.currentThread().interrupt();
            return;
         } catch (IOException var4) {
            if (this.running.get()) {
               LOGGER.warn("Dedicated Steam packet loop stopped", var4);
            }

            this.close();
            this.controller.transportFailed("DEDICATED_TRANSPORT_FAILED");
            return;
         }
      }
   }

   private void receive(ByteBuffer buffer) throws IOException {
      for (int count = 0; count < 64; count++) {
         int size = this.backend.availablePacketSize(480);
         if (size == 0) {
            return;
         }

         if (size < 0 || size > 1048576) {
            throw new IOException("Steam returned an invalid dedicated packet size");
         }

         buffer.clear();
         SteamGameServerRuntimeBackend.ReceivedPacket packet = this.backend.receive(buffer, 480);
         if (packet.size() > 0 && packet.size() <= SteamProtocol.MAX_PACKET_SIZE && packet.remoteSteamId() != 0L) {
            buffer.flip();
            buffer.limit(packet.size());
            SteamProtocol.Frame frame = SteamProtocol.decode(buffer);
            if (frame != null) {
               this.dispatch(packet.remoteSteamId(), frame);
            }
         }
      }
   }

   private void dispatch(long remoteSteamId, SteamProtocol.Frame frame) {
      SteamBridgeRegistry.Key key = new SteamBridgeRegistry.Key(remoteSteamId, frame.connectionId());
      switch (frame.type()) {
         case 2:
            SteamConnectionBridge bridge = this.bridges.get(key);
            if (bridge != null) {
               bridge.acceptSteamData(frame.payload());
            }

            return;
         case 3:
            SteamConnectionBridge bridge = this.bridges.get(key);
            if (bridge != null) {
               bridge.acceptRemoteFin();
            }

            return;
         case 4:
            SteamConnectionBridge bridge = this.bridges.get(key);
            if (bridge != null) {
               bridge.resetFromRemote();
            }

            return;
         case 5:
         case 6:
         case 7:
         case 9:
         default:
            return;
         case 8:
            LOGGER.info("Received a dedicated Steam authentication handshake");
            this.handleOpen(remoteSteamId, key, frame.payload());
            return;
         case 10:
         case 11:
            SteamConnectionBridge bridge = this.bridges.get(key);
            if (bridge != null && !bridge.isClosed()) {
               SteamAddonNetworkRuntime.get().accept(bridge, frame.type(), frame.payload());
            }
      }
   }

   private void handleOpen(long remoteSteamId, SteamBridgeRegistry.Key key, byte[] payload) {
      Long pendingDeadline = this.pendingPeers.get(remoteSteamId);
      if (this.running.get()
         && this.controller.accepting()
         && !this.bridges.contains(key)
         && !this.activePeers.contains(remoteSteamId)
         && pendingDeadline != null
         && pendingDeadline > System.currentTimeMillis()) {
         SteamProtocol.DedicatedOpen decoded = SteamProtocol.decodeDedicatedOpen(payload);
         if (decoded == null) {
            this.rejectOpen(remoteSteamId, key.connectionId());
         } else {
            byte[] ticket = decoded.takeTicket();
            byte[] nonce = decoded.nonce();
            long generation = decoded.generation();
            decoded.destroy();

            DedicatedAdmissionGate.Request request;
            try {
               request = new DedicatedAdmissionGate.Request(remoteSteamId, generation, 4, nonce, ticket);
            } catch (RuntimeException var14) {
               Arrays.fill(ticket, (byte)0);
               Arrays.fill(nonce, (byte)0);
               this.rejectOpen(remoteSteamId, key.connectionId());
               return;
            }

            Arrays.fill(ticket, (byte)0);
            Arrays.fill(nonce, (byte)0);

            try {
               this.admissionExecutor.execute(new SteamDedicatedServerTransport.AdmissionTask(remoteSteamId, key, request));
            } catch (RejectedExecutionException var13) {
               request.close();
               this.rejectOpen(remoteSteamId, key.connectionId());
            }
         }
      } else {
         this.rejectOpen(remoteSteamId, key.connectionId());
      }
   }

   private void submitAdmissionResult(long remoteSteamId, SteamBridgeRegistry.Key key, DedicatedAdmissionGate.Result result, Throwable failure) {
      try {
         this.admissionExecutor.execute(() -> {
            this.pendingPeers.remove(remoteSteamId);
            if (failure == null && result != null && result.allowed()) {
               this.openMinecraftBridge(key, result);
            } else {
               this.backend.endAuthentication(remoteSteamId);
               this.sendStandaloneReset(remoteSteamId, key.connectionId());
               this.schedulePeerClose(remoteSteamId);
            }
         });
      } catch (RejectedExecutionException var7) {
         this.pendingPeers.remove(remoteSteamId);
         this.backend.endAuthentication(remoteSteamId);
         this.sendStandaloneReset(remoteSteamId, key.connectionId());
         this.schedulePeerClose(remoteSteamId);
      }
   }

   private void openMinecraftBridge(SteamBridgeRegistry.Key key, DedicatedAdmissionGate.Result admitted) {
      Socket socket = new Socket();
      SteamConnectionBridge bridge = null;
      AutoCloseable ingressLease = null;
      boolean handedOff = false;

      try {
         if (!this.running.get()
            || !this.controller.accepting()
            || admitted.generation() != this.controller.generation()
            || !this.activePeers.add(admitted.internalSteamId())) {
            throw new IOException("Dedicated admission became stale");
         }

         socket.connect(new InetSocketAddress("127.0.0.1", this.controller.minecraftPort()), 1000);
         socket.setTcpNoDelay(true);
         socket.setKeepAlive(true);
         ingressLease = this.controller.registerAuthenticatedIngress(socket.getLocalPort(), admitted.internalSteamId(), admitted.generation());
         bridge = new SteamConnectionBridge(this, admitted.internalSteamId(), key.connectionId(), socket, true, null);
         SteamBridgeRegistry.Registration registration = this.bridges.register(key, bridge, () -> this.running.get() && this.controller.accepting());
         if (registration != SteamBridgeRegistry.Registration.REGISTERED) {
            throw new IOException("Dedicated bridge registration rejected");
         }

         this.ingress.put(bridge, ingressLease);
         ingressLease = null;
         SessionService.SessionId addonSession = new SessionService.SessionId(SteamPeerPrivacy.dedicatedSessionId(admitted.generation()), admitted.generation());
         IdentityService.PeerId addonPeer = new IdentityService.PeerId(SteamPeerPrivacy.opaquePeerId(admitted.generation(), admitted.internalSteamId()));
         SteamAddonNetworkRuntime.get().bridgeReady(bridge, addonSession, addonPeer, true, this.addonSender, true);
         if (!this.offerControl(
            bridge, SteamProtocol.encodeDedicatedOpenAck(bridge.connectionId(), admitted.generation()), SteamOutboundQueue.Kind.DEDICATED_OPEN_ACK
         )) {
            throw new IOException("Dedicated acknowledgement queue is full");
         }

         bridge.start();
         handedOff = true;
         this.controller.players(this.activePeers.size());
         LOGGER.info("Accepted an authenticated dedicated e4steam peer");
      } catch (RuntimeException | IOException var13) {
         this.activePeers.remove(admitted.internalSteamId());
         this.backend.endAuthentication(admitted.internalSteamId());
         this.sendStandaloneReset(admitted.internalSteamId(), key.connectionId());
         this.schedulePeerClose(admitted.internalSteamId());
         LOGGER.debug("Dedicated peer admission failed [{}]", safeFailure(var13));
      } finally {
         close(ingressLease);
         if (!handedOff) {
            if (bridge != null) {
               bridge.close(false);
            } else {
               close(socket);
            }
         }
      }
   }

   private void drainOutbound() throws IOException {
      for (int count = 0; count < 64; count++) {
         SteamOutboundQueue.Packet<SteamConnectionBridge> packet = this.deferredPacket;
         if (packet != null) {
            this.deferredPacket = null;
         } else {
            packet = this.outbound.poll();
         }

         if (packet == null) {
            return;
         }

         SteamConnectionBridge bridge = packet.bridge();
         if (bridge == null || packet.kind() == SteamOutboundQueue.Kind.RESET || !bridge.isClosed()) {
            long now = System.currentTimeMillis();
            if (bridge != null && packet.kind() != SteamOutboundQueue.Kind.RESET && !bridge.isOutboundReady(now)) {
               this.deferredPacket = packet;
               return;
            }

            if (bridge != null && packet.kind() == SteamOutboundQueue.Kind.DATA && !bridge.isOutboundDataReady(now)) {
               this.deferredPacket = packet;
               return;
            }

            ByteBuffer direct = ByteBuffer.allocateDirect(packet.payload().length);
            direct.put(packet.payload()).flip();
            boolean unreliable = packet.kind() == SteamOutboundQueue.Kind.ADDON_DATAGRAM;
            boolean sent = this.backend.send(packet.remoteSteamId(), direct, unreliable, 480);
            if (!sent) {
               if (!unreliable) {
                  if (bridge != null) {
                     bridge.close(false);
                  } else {
                     this.backend.closePeer(packet.remoteSteamId());
                  }
               }
            } else if (bridge != null && packet.kind() == SteamOutboundQueue.Kind.FIN) {
               bridge.markFinSubmitted();
            } else if (bridge != null && packet.kind() == SteamOutboundQueue.Kind.RESET) {
               bridge.markResetSubmitted();
            }
         }
      }
   }

   @Override
   public boolean sendData(SteamConnectionBridge bridge, byte[] payload) {
      return this.running.get()
         && !bridge.isClosed()
         && this.outbound.offerData(bridge.remoteSteamId(), bridge.connectionId(), SteamProtocol.encodeData(bridge.connectionId(), payload), bridge);
   }

   @Override
   public boolean sendFin(SteamConnectionBridge bridge) {
      return this.offerControl(bridge, SteamProtocol.encodeFin(bridge.connectionId()), SteamOutboundQueue.Kind.FIN);
   }

   @Override
   public boolean sendReset(SteamConnectionBridge bridge) {
      return this.offerControl(bridge, SteamProtocol.encodeReset(bridge.connectionId()), SteamOutboundQueue.Kind.RESET);
   }

   @Override
   public void closeUdpBridge(SteamConnectionBridge bridge) {
   }

   @Override
   public void unregister(SteamConnectionBridge bridge) {
      SteamAddonNetworkRuntime.get().bridgeClosed(bridge);
      SteamBridgeRegistry.Key key = new SteamBridgeRegistry.Key(bridge.remoteSteamId(), bridge.connectionId());
      this.outbound.purge(bridge);
      if (this.deferredPacket != null && this.deferredPacket.bridge() == bridge) {
         this.deferredPacket = null;
      }

      if (this.bridges.remove(key, bridge)) {
         close(this.ingress.remove(bridge));
         this.activePeers.remove(bridge.remoteSteamId());
         this.backend.endAuthentication(bridge.remoteSteamId());
         this.backend.closePeer(bridge.remoteSteamId());
         this.controller.players(this.activePeers.size());
      }
   }

   private boolean offerControl(SteamConnectionBridge bridge, byte[] payload, SteamOutboundQueue.Kind kind) {
      return this.running.get() && this.outbound.offerControl(bridge.remoteSteamId(), bridge.connectionId(), payload, kind, bridge);
   }

   private void sendStandaloneReset(long steamId, int connectionId) {
      if (this.running.get()) {
         this.outbound.offerControl(steamId, connectionId, SteamProtocol.encodeReset(connectionId), SteamOutboundQueue.Kind.RESET, null);
      }
   }

   private void rejectOpen(long steamId, int connectionId) {
      this.pendingPeers.remove(steamId);
      this.backend.endAuthentication(steamId);
      this.sendStandaloneReset(steamId, connectionId);
      this.schedulePeerClose(steamId);
   }

   private void expirePendingPeers(long now) {
      this.pendingPeers.forEach((steamId, deadline) -> {
         if (deadline != null && deadline <= now && this.pendingPeers.remove(steamId, deadline)) {
            this.backend.closePeer(steamId);
         }
      });
   }

   private void schedulePeerClose(long steamId) {
      if (steamId != 0L && this.running.get()) {
         this.peersAwaitingClose.put(steamId, System.currentTimeMillis() + 100L);
      }
   }

   private void closeRejectedPeers(long now) {
      this.peersAwaitingClose.forEach((steamId, deadline) -> {
         if (deadline != null && deadline <= now && this.peersAwaitingClose.remove(steamId, deadline)) {
            this.backend.closePeer(steamId);
         }
      });
   }

   private void closeRemote(long steamId) {
      this.pendingPeers.remove(steamId);
      this.peersAwaitingClose.remove(steamId);

      for (SteamConnectionBridge bridge : this.bridges.snapshot()) {
         if (bridge.remoteSteamId() == steamId) {
            bridge.close(false);
         }
      }

      this.activePeers.remove(steamId);
      this.backend.endAuthentication(steamId);
      this.controller.players(this.activePeers.size());
   }

   @Override
   public void close() {
      if (this.running.compareAndSet(true, false)) {
         this.backend.peerListener(null);
         Thread thread = this.packetThread;
         this.packetThread = null;
         if (thread != null && thread != Thread.currentThread()) {
            thread.interrupt();
         }

         for (SteamConnectionBridge bridge : new ArrayList<>(this.bridges.snapshot())) {
            bridge.close(false);
         }

         this.deferredPacket = null;

         for (AutoCloseable handle : this.ingress.values()) {
            close(handle);
         }

         this.ingress.clear();
         this.activePeers.clear();
         this.pendingPeers.clear();
         this.peersAwaitingClose.clear();
         this.outbound.clear();

         for (Runnable abandoned : this.admissionExecutor.shutdownNow()) {
            if (abandoned instanceof SteamDedicatedServerTransport.AdmissionTask) {
               ((SteamDedicatedServerTransport.AdmissionTask)abandoned).discard();
            }
         }

         this.controller.players(0);
      }
   }

   private static void close(AutoCloseable value) {
      if (value != null) {
         try {
            value.close();
         } catch (Exception var2) {
         }
      }
   }

   private static String safeFailure(Throwable failure) {
      return failure instanceof SecurityException ? "INGRESS_REJECTED" : "BRIDGE_REJECTED";
   }

   private static ThreadFactory daemonFactory(String base) {
      return runnable -> {
         Thread thread = new Thread(runnable, base);
         thread.setDaemon(true);
         return thread;
      };
   }

   private final class AdmissionTask implements Runnable {
      private final long remoteSteamId;
      private final SteamBridgeRegistry.Key key;
      private final DedicatedAdmissionGate.Request request;
      private final AtomicBoolean claimed = new AtomicBoolean();

      private AdmissionTask(long remoteSteamId, SteamBridgeRegistry.Key key, DedicatedAdmissionGate.Request request) {
         this.remoteSteamId = remoteSteamId;
         this.key = key;
         this.request = request;
      }

      @Override
      public void run() {
         if (this.claimed.compareAndSet(false, true)) {
            try {
               SteamDedicatedServerTransport.this.admission
                  .evaluate(this.request, SteamDedicatedServerTransport.this.controller.generation())
                  .toCompletableFuture()
                  .orTimeout(16L, TimeUnit.SECONDS)
                  .whenComplete((result, failure) -> SteamDedicatedServerTransport.this.submitAdmissionResult(this.remoteSteamId, this.key, result, failure));
            } catch (ThreadDeath | VirtualMachineError var6) {
               SteamDedicatedServerTransport.this.backend.endAuthentication(this.remoteSteamId);
               throw var6;
            } catch (Throwable var7) {
               SteamDedicatedServerTransport.this.submitAdmissionResult(this.remoteSteamId, this.key, null, var7);
            } finally {
               this.request.close();
            }
         }
      }

      private void discard() {
         if (this.claimed.compareAndSet(false, true)) {
            this.request.close();
         }
      }
   }
}
