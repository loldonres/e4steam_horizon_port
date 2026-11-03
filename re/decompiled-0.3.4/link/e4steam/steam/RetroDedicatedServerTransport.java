package link.e4steam.steam;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Set;
import java.util.Map.Entry;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ThreadPoolExecutor.AbortPolicy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class RetroDedicatedServerTransport implements SteamBridgeRuntime, AutoCloseable {
   private static final Logger LOGGER = LogManager.getLogger("e4steam");
   private static final int CHANNEL = 480;
   private static final int MAX_PACKETS_PER_TICK = 64;
   private static final int LOOPBACK_CONNECT_TIMEOUT_MILLIS = 1000;
   private static final long AUTH_TIMEOUT_SECONDS = 16L;
   private static final long PENDING_TTL_MILLIS = 20000L;
   private static final long PROOF_TTL_MILLIS = 120000L;
   private static final long REJECT_CLOSE_DELAY_MILLIS = 100L;
   private final RetroDedicatedServerTransport.Backend backend;
   private final RetroDedicatedServerTransport.Host host;
   private final int capacity;
   private final SteamBridgeRegistry<SteamConnectionBridge, AutoCloseable> bridges;
   private final SteamOutboundQueue<SteamConnectionBridge> outbound = new SteamOutboundQueue<>(2048, 1536, 0, 0, 256, 64);
   private final ConcurrentHashMap<Long, Long> pending = new ConcurrentHashMap<>();
   private final ConcurrentHashMap<Long, Long> delayedClose = new ConcurrentHashMap<>();
   private final ConcurrentHashMap<RetroDedicatedServerTransport.ProofKey, Long> proofs = new ConcurrentHashMap<>();
   private final Set<Long> active = ConcurrentHashMap.newKeySet();
   private final ConcurrentHashMap<SteamConnectionBridge, AutoCloseable> ingress = new ConcurrentHashMap<>();
   private final ThreadPoolExecutor admissions;
   private final AtomicBoolean running = new AtomicBoolean();
   private volatile Thread packetThread;
   private volatile SteamOutboundQueue.Packet<SteamConnectionBridge> deferred;

   public RetroDedicatedServerTransport(SteamGameServerRuntimeBackend backend, RetroDedicatedServerTransport.Host host, int capacity) {
      this(new RetroDedicatedServerTransport.GameServerBackend(backend), host, capacity);
   }

   RetroDedicatedServerTransport(RetroDedicatedServerTransport.Backend backend, RetroDedicatedServerTransport.Host host, int capacity) {
      if (backend != null && host != null && capacity >= 1 && capacity <= 64) {
         this.backend = backend;
         this.host = host;
         this.capacity = capacity;
         this.bridges = new SteamBridgeRegistry<>(capacity);
         this.admissions = new ThreadPoolExecutor(
            1,
            Math.min(4, capacity),
            30L,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(Math.max(16, capacity * 2)),
            daemonFactory("e4steam-retro-dedicated-admission"),
            new AbortPolicy()
         );
      } else {
         throw new IllegalArgumentException("Invalid retro dedicated transport");
      }
   }

   public void start() {
      if (this.running.compareAndSet(false, true)) {
         this.backend
            .peerListener(
               new RetroDedicatedServerTransport.Backend.PeerListener() {
                  @Override
                  public boolean onSessionRequest(long remoteSteamId) {
                     long now = System.currentTimeMillis();
                     RetroDedicatedServerTransport.this.expire(now);
                     if (remoteSteamId != 0L
                        && RetroDedicatedServerTransport.this.host.accepting()
                        && !RetroDedicatedServerTransport.this.active.contains(remoteSteamId)
                        && !RetroDedicatedServerTransport.this.delayedClose.containsKey(remoteSteamId)) {
                        Long previous = RetroDedicatedServerTransport.this.pending.get(remoteSteamId);
                        if (previous != null && previous > now) {
                           return true;
                        } else if (RetroDedicatedServerTransport.this.pending.size() >= RetroDedicatedServerTransport.this.capacity * 2) {
                           return false;
                        } else {
                           RetroDedicatedServerTransport.this.pending.put(remoteSteamId, now + 20000L);
                           return true;
                        }
                     } else {
                        return false;
                     }
                  }

                  @Override
                  public void onSessionFailed(long remoteSteamId, int reason, String detail) {
                     RetroDedicatedServerTransport.this.closeRemote(remoteSteamId);
                  }
               }
            );
         Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
               RetroDedicatedServerTransport.this.runPackets();
            }
         }, "e4steam-retro-dedicated-packets");
         worker.setDaemon(true);
         this.packetThread = worker;
         worker.start();
      }
   }

   private void runPackets() {
      ByteBuffer buffer = ByteBuffer.allocateDirect(1048576);

      while (this.running.get()) {
         try {
            this.receive(buffer);
            long now = System.currentTimeMillis();
            this.expire(now);
            this.closeRejected(now);
            this.drainOutbound();
            Thread.sleep(2L);
         } catch (InterruptedException var4) {
            Thread.currentThread().interrupt();
            return;
         } catch (IOException var5) {
            if (this.running.get()) {
               LOGGER.warn("Retro dedicated Steam packet loop stopped");
            }

            this.host.transportFailed("DEDICATED_TRANSPORT_FAILED");
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
            throw new IOException("Invalid dedicated Steam packet size");
         }

         buffer.clear();
         RetroDedicatedServerTransport.Backend.ReceivedPacket packet = this.backend.receive(buffer, 480);
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
         default:
            return;
         case 8:
            this.handleOpen(remoteSteamId, key, frame.payload());
      }
   }

   private void handleOpen(long remoteSteamId, SteamBridgeRegistry.Key key, byte[] payload) {
      Long deadline = this.pending.get(remoteSteamId);
      if (this.running.get()
         && this.host.accepting()
         && deadline != null
         && deadline > System.currentTimeMillis()
         && !this.bridges.contains(key)
         && !this.active.contains(remoteSteamId)) {
         SteamProtocol.DedicatedOpen decoded = SteamProtocol.decodeDedicatedOpen(payload);
         if (decoded != null && decoded.generation() == this.host.generation()) {
            final long generation = decoded.generation();
            final byte[] ticket = decoded.takeTicket();
            byte[] nonce = decoded.nonce();
            decoded.destroy();
            RetroDedicatedServerTransport.ProofKey proof = new RetroDedicatedServerTransport.ProofKey(remoteSteamId, generation, nonce);
            Arrays.fill(nonce, (byte)0);
            long proofDeadline = System.currentTimeMillis() + 120000L;
            if (!this.claimProof(proof, proofDeadline)) {
               Arrays.fill(ticket, (byte)0);
               this.reject(remoteSteamId, key.connectionId());
            } else {
               try {
                  this.admissions.execute(new Runnable() {
                     @Override
                     public void run() {
                        RetroDedicatedServerTransport.this.authenticateAndOpen(remoteSteamId, key, generation, ticket);
                     }
                  });
               } catch (RuntimeException var15) {
                  Arrays.fill(ticket, (byte)0);
                  this.reject(remoteSteamId, key.connectionId());
               }
            }
         } else {
            if (decoded != null) {
               decoded.destroy();
            }

            this.reject(remoteSteamId, key.connectionId());
         }
      } else {
         this.reject(remoteSteamId, key.connectionId());
      }
   }

   private void authenticateAndOpen(long remoteSteamId, SteamBridgeRegistry.Key key, long generation, byte[] ticket) {
      boolean authenticated = false;

      try {
         authenticated = Boolean.TRUE.equals(this.backend.authenticate(remoteSteamId, ticket, generation).toCompletableFuture().get(16L, TimeUnit.SECONDS));
      } catch (InterruptedException var14) {
         Thread.currentThread().interrupt();
      } catch (ExecutionException var15) {
      } catch (TimeoutException var16) {
      } finally {
         Arrays.fill(ticket, (byte)0);
      }

      this.pending.remove(remoteSteamId);
      if (authenticated
         && this.running.get()
         && this.host.accepting()
         && generation == this.host.generation()
         && this.host.allows(remoteSteamId)
         && this.active.size() < this.capacity) {
         this.openMinecraftBridge(remoteSteamId, key, generation);
      } else {
         this.backend.endAuthentication(remoteSteamId);
         this.reject(remoteSteamId, key.connectionId());
      }
   }

   private void openMinecraftBridge(long remoteSteamId, SteamBridgeRegistry.Key key, long generation) {
      Socket socket = new Socket();
      SteamConnectionBridge bridge = null;
      AutoCloseable ingressLease = null;
      boolean handedOff = false;

      try {
         if (!this.active.add(remoteSteamId)) {
            throw new IOException("Dedicated peer is already active");
         }

         InetAddress address = this.host.minecraftAddress();
         socket.connect(new InetSocketAddress(address, this.host.minecraftPort()), 1000);
         socket.setTcpNoDelay(true);
         socket.setKeepAlive(true);
         ingressLease = this.host.registerIngress(socket.getLocalPort(), remoteSteamId, generation);
         bridge = new SteamConnectionBridge(this, remoteSteamId, key.connectionId(), socket, true, null);
         SteamBridgeRegistry.Registration registration = this.bridges.register(key, bridge, new BooleanSupplier() {
            @Override
            public boolean getAsBoolean() {
               return RetroDedicatedServerTransport.this.running.get() && RetroDedicatedServerTransport.this.host.accepting();
            }
         });
         if (registration != SteamBridgeRegistry.Registration.REGISTERED) {
            throw new IOException("Dedicated bridge registration rejected");
         }

         this.ingress.put(bridge, ingressLease);
         ingressLease = null;
         if (!this.outbound
            .offerControl(
               remoteSteamId,
               key.connectionId(),
               SteamProtocol.encodeDedicatedOpenAck(key.connectionId(), generation),
               SteamOutboundQueue.Kind.DEDICATED_OPEN_ACK,
               bridge
            )) {
            throw new IOException("Dedicated acknowledgement queue is full");
         }

         bridge.start();
         handedOff = true;
         this.host.players(this.active.size());
         LOGGER.info("Accepted an authenticated retro dedicated e4steam peer");
      } catch (RuntimeException | IOException var15) {
         this.active.remove(remoteSteamId);
         this.backend.endAuthentication(remoteSteamId);
         this.reject(remoteSteamId, key.connectionId());
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
         SteamOutboundQueue.Packet<SteamConnectionBridge> packet = this.deferred;
         if (packet != null) {
            this.deferred = null;
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
               this.deferred = packet;
               return;
            }

            if (bridge != null && packet.kind() == SteamOutboundQueue.Kind.DATA && !bridge.isOutboundDataReady(now)) {
               this.deferred = packet;
               return;
            }

            ByteBuffer direct = ByteBuffer.allocateDirect(packet.payload().length);
            direct.put(packet.payload()).flip();
            if (!this.backend.send(packet.remoteSteamId(), direct, false, 480)) {
               if (bridge != null) {
                  bridge.close(false);
               } else {
                  this.backend.closePeer(packet.remoteSteamId());
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
      return this.running.get()
         && this.outbound
            .offerControl(bridge.remoteSteamId(), bridge.connectionId(), SteamProtocol.encodeFin(bridge.connectionId()), SteamOutboundQueue.Kind.FIN, bridge);
   }

   @Override
   public boolean sendReset(SteamConnectionBridge bridge) {
      return this.running.get()
         && this.outbound
            .offerControl(
               bridge.remoteSteamId(), bridge.connectionId(), SteamProtocol.encodeReset(bridge.connectionId()), SteamOutboundQueue.Kind.RESET, bridge
            );
   }

   @Override
   public void closeUdpBridge(SteamConnectionBridge bridge) {
   }

   @Override
   public void unregister(SteamConnectionBridge bridge) {
      SteamBridgeRegistry.Key key = new SteamBridgeRegistry.Key(bridge.remoteSteamId(), bridge.connectionId());
      this.outbound.purge(bridge);
      if (this.deferred != null && this.deferred.bridge() == bridge) {
         this.deferred = null;
      }

      if (this.bridges.remove(key, bridge)) {
         close(this.ingress.remove(bridge));
         this.active.remove(bridge.remoteSteamId());
         this.backend.endAuthentication(bridge.remoteSteamId());
         this.backend.closePeer(bridge.remoteSteamId());
         this.host.players(this.active.size());
      }
   }

   private void reject(long steamId, int connectionId) {
      this.pending.remove(steamId);
      this.backend.endAuthentication(steamId);
      if (this.running.get()) {
         this.outbound.offerControl(steamId, connectionId, SteamProtocol.encodeReset(connectionId), SteamOutboundQueue.Kind.RESET, null);
         this.delayedClose.put(steamId, System.currentTimeMillis() + 100L);
      }
   }

   private void expire(long now) {
      for (Entry<Long, Long> entry : this.pending.entrySet()) {
         if (entry.getValue() <= now && this.pending.remove(entry.getKey(), entry.getValue())) {
            this.backend.closePeer(entry.getKey());
         }
      }

      synchronized (this.proofs) {
         for (Entry<RetroDedicatedServerTransport.ProofKey, Long> entryx : this.proofs.entrySet()) {
            if (entryx.getValue() <= now && this.proofs.remove(entryx.getKey(), entryx.getValue())) {
               entryx.getKey().destroy();
            }
         }
      }
   }

   private void closeRejected(long now) {
      for (Entry<Long, Long> entry : this.delayedClose.entrySet()) {
         if (entry.getValue() <= now && this.delayedClose.remove(entry.getKey(), entry.getValue())) {
            this.backend.closePeer(entry.getKey());
         }
      }
   }

   private void closeRemote(long steamId) {
      this.pending.remove(steamId);
      this.delayedClose.remove(steamId);

      for (SteamConnectionBridge bridge : this.bridges.snapshot()) {
         if (bridge.remoteSteamId() == steamId) {
            bridge.close(false);
         }
      }

      this.active.remove(steamId);
      this.backend.endAuthentication(steamId);
      this.host.players(this.active.size());
   }

   @Override
   public void close() {
      if (this.running.compareAndSet(true, false)) {
         this.backend.peerListener(null);
         Thread worker = this.packetThread;
         this.packetThread = null;
         if (worker != null && worker != Thread.currentThread()) {
            worker.interrupt();
         }

         for (SteamConnectionBridge bridge : new ArrayList<>(this.bridges.snapshot())) {
            bridge.close(false);
         }

         this.deferred = null;

         for (AutoCloseable lease : this.ingress.values()) {
            close(lease);
         }

         this.ingress.clear();
         this.active.clear();
         this.pending.clear();
         this.delayedClose.clear();
         this.destroyProofs();
         this.outbound.clear();
         this.admissions.shutdownNow();
         this.host.players(0);
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

   private static ThreadFactory daemonFactory(String name) {
      return new ThreadFactory() {
         @Override
         public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
         }
      };
   }

   private boolean claimProof(RetroDedicatedServerTransport.ProofKey proof, long deadline) {
      synchronized (this.proofs) {
         if (this.running.get() && this.proofs.size() < this.capacity * 8 && this.proofs.putIfAbsent(proof, deadline) == null) {
            return true;
         } else {
            proof.destroy();
            return false;
         }
      }
   }

   private void destroyProofs() {
      synchronized (this.proofs) {
         for (RetroDedicatedServerTransport.ProofKey proof : this.proofs.keySet()) {
            proof.destroy();
         }

         this.proofs.clear();
      }
   }

   interface Backend {
      void peerListener(RetroDedicatedServerTransport.Backend.PeerListener var1);

      CompletionStage<Boolean> authenticate(long var1, byte[] var3, long var4);

      void endAuthentication(long var1);

      boolean send(long var1, ByteBuffer var3, boolean var4, int var5) throws IOException;

      int availablePacketSize(int var1) throws IOException;

      RetroDedicatedServerTransport.Backend.ReceivedPacket receive(ByteBuffer var1, int var2) throws IOException;

      void closePeer(long var1);

      public interface PeerListener {
         boolean onSessionRequest(long var1);

         void onSessionFailed(long var1, int var3, String var4);
      }

      public static final class ReceivedPacket {
         private final long steamId;
         private final int size;

         ReceivedPacket(long steamId, int size) {
            this.steamId = steamId;
            this.size = size;
         }

         long remoteSteamId() {
            return this.steamId;
         }

         int size() {
            return this.size;
         }
      }
   }

   private static final class GameServerBackend implements RetroDedicatedServerTransport.Backend {
      private final SteamGameServerRuntimeBackend delegate;

      private GameServerBackend(SteamGameServerRuntimeBackend delegate) {
         if (delegate == null) {
            throw new IllegalArgumentException("backend");
         } else {
            this.delegate = delegate;
         }
      }

      @Override
      public void peerListener(RetroDedicatedServerTransport.Backend.PeerListener listener) {
         if (listener == null) {
            this.delegate.peerListener(null);
         } else {
            this.delegate.peerListener(new SteamGameServerRuntimeBackend.PeerListener() {
               @Override
               public boolean onSessionRequest(long remoteSteamId) {
                  return listener.onSessionRequest(remoteSteamId);
               }

               @Override
               public void onSessionFailed(long remoteSteamId, int reason, String detail) {
                  listener.onSessionFailed(remoteSteamId, reason, detail);
               }
            });
         }
      }

      @Override
      public CompletionStage<Boolean> authenticate(long steamId, byte[] ticket, long generation) {
         return this.delegate.authenticate(steamId, ticket, generation);
      }

      @Override
      public void endAuthentication(long steamId) {
         this.delegate.endAuthentication(steamId);
      }

      @Override
      public boolean send(long steamId, ByteBuffer payload, boolean unreliable, int channel) throws IOException {
         return this.delegate.send(steamId, payload, unreliable, channel);
      }

      @Override
      public int availablePacketSize(int channel) throws IOException {
         return this.delegate.availablePacketSize(channel);
      }

      @Override
      public RetroDedicatedServerTransport.Backend.ReceivedPacket receive(ByteBuffer target, int channel) throws IOException {
         SteamGameServerRuntimeBackend.ReceivedPacket received = this.delegate.receive(target, channel);
         return new RetroDedicatedServerTransport.Backend.ReceivedPacket(received.remoteSteamId(), received.size());
      }

      @Override
      public void closePeer(long steamId) {
         this.delegate.closePeer(steamId);
      }
   }

   public interface Host {
      boolean accepting();

      long generation();

      int minecraftPort();

      InetAddress minecraftAddress();

      int maxPeers();

      boolean allows(long var1);

      AutoCloseable registerIngress(int var1, long var2, long var4);

      void transportFailed(String var1);

      void players(int var1);
   }

   private static final class ProofKey {
      private final long steamId;
      private final long generation;
      private final byte[] nonce;
      private final int hash;

      private ProofKey(long steamId, long generation, byte[] nonce) {
         this.steamId = steamId;
         this.generation = generation;
         this.nonce = (byte[])nonce.clone();
         int result = Long.valueOf(steamId).hashCode();
         result = 31 * result + Long.valueOf(generation).hashCode();
         this.hash = 31 * result + Arrays.hashCode(this.nonce);
      }

      @Override
      public int hashCode() {
         return this.hash;
      }

      @Override
      public boolean equals(Object other) {
         if (this == other) {
            return true;
         } else {
            return !(other instanceof RetroDedicatedServerTransport.ProofKey that)
               ? false
               : this.steamId == that.steamId && this.generation == that.generation && Arrays.equals(this.nonce, that.nonce);
         }
      }

      private void destroy() {
         Arrays.fill(this.nonce, (byte)0);
      }
   }
}
