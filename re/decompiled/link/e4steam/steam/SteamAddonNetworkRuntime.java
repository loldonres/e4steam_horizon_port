package link.e4steam.steam;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import link.e4steam.api.identity.IdentityService;
import link.e4steam.api.network.NetworkService;
import link.e4steam.api.session.SessionService;
import link.e4steam.internal.api.AddonNetworkCoordinator;
import link.e4steam.internal.api.CoreApiPlatform;

public final class SteamAddonNetworkRuntime implements AddonNetworkCoordinator.Transport {
   private static final SteamAddonNetworkRuntime INSTANCE = new SteamAddonNetworkRuntime();
   private static final int MAX_ASSEMBLIES_PER_BRIDGE = 16;
   private static final long ASSEMBLY_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5L);
   private static final long REQUIRED_HELLO_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5L);
   private static final long OPTIONAL_HELLO_GRACE_NANOS = TimeUnit.SECONDS.toNanos(1L);
   private final SecureRandom random = new SecureRandom();
   private final ConcurrentHashMap<SteamConnectionBridge, SteamAddonNetworkRuntime.BridgeState> bridges = new ConcurrentHashMap<>();

   static SteamAddonNetworkRuntime get() {
      return INSTANCE;
   }

   public static AutoCloseable installHooks() {
      return SteamAddonHooks.install(new SteamAddonHooks.Delegate() {
         @Override
         public void bridgeReady(SteamConnectionBridge bridge) {
            SteamAddonNetworkRuntime.INSTANCE.bridgeReady(bridge);
         }

         @Override
         public void bridgeClosed(SteamConnectionBridge bridge) {
            SteamAddonNetworkRuntime.INSTANCE.bridgeClosed(bridge);
         }

         @Override
         public void tick() {
            SteamAddonNetworkRuntime.INSTANCE.tick();
         }

         @Override
         public void accept(SteamConnectionBridge bridge, byte frameType, byte[] payload) {
            SteamAddonNetworkRuntime.INSTANCE.accept(bridge, frameType, payload);
         }
      });
   }

   void bridgeReady(SteamConnectionBridge bridge) {
      AddonNetworkCoordinator coordinator = coordinator();
      if (bridge != null && !bridge.isClosed() && coordinator != null) {
         SteamAddonNetworkRuntime.BridgeIdentity identity = clientIdentity(bridge);
         if (identity != null) {
            this.bridgeReady(bridge, identity.sessionId, identity.peerId, bridge.isHostSide(), new SteamAddonNetworkRuntime.FrameSender() {
               @Override
               public boolean sendHello(SteamConnectionBridge target, byte[] packet) {
                  return SteamClientApiBridge.sendAddonHello(target, packet);
               }

               @Override
               public boolean sendData(SteamConnectionBridge target, byte[] packet, boolean reliable) {
                  return SteamClientApiBridge.sendAddonFrame(target, packet, reliable);
               }
            }, true);
         }
      }
   }

   void bridgeReady(
      SteamConnectionBridge bridge,
      SessionService.SessionId sessionId,
      IdentityService.PeerId peerId,
      boolean localIsHost,
      SteamAddonNetworkRuntime.FrameSender sender
   ) {
      this.bridgeReady(bridge, sessionId, peerId, localIsHost, sender, true);
   }

   void bridgeReady(
      SteamConnectionBridge bridge,
      SessionService.SessionId sessionId,
      IdentityService.PeerId peerId,
      boolean localIsHost,
      SteamAddonNetworkRuntime.FrameSender sender,
      boolean gateUntilHello
   ) {
      AddonNetworkCoordinator coordinator = coordinator();
      if (bridge != null && !bridge.isClosed() && sessionId != null && peerId != null && sender != null && coordinator != null) {
         SteamAddonNetworkRuntime.BridgeState state = this.bridges.computeIfAbsent(bridge, ignored -> this.newState(sessionId, peerId, localIsHost, sender));
         if (sessionId.equals(state.sessionId) && peerId.equals(state.peerId)) {
            coordinator.transport(this);
            state.requiredLocal = coordinator.hasRequiredChannels();
            state.gateUntilHello = gateUntilHello && coordinator.hasChannels();
            if (state.requiredLocal || state.gateUntilHello) {
               bridge.requireAddonNegotiation();
            }

            this.sendHello(bridge, state, coordinator);
         } else {
            bridge.close(true);
         }
      }
   }

   void bridgeClosed(SteamConnectionBridge bridge) {
      SteamAddonNetworkRuntime.BridgeState state = this.bridges.remove(bridge);
      if (state != null) {
         AddonNetworkCoordinator coordinator = coordinator();
         if (coordinator != null && state.sessionId != null && state.peerId != null) {
            coordinator.closePeer(state.sessionId, state.peerId);
         }

         state.destroy();
      }
   }

   void tick() {
      AddonNetworkCoordinator coordinator = coordinator();
      if (coordinator != null) {
         long now = System.nanoTime();

         for (Entry<SteamConnectionBridge, SteamAddonNetworkRuntime.BridgeState> entry : this.bridges.entrySet()) {
            SteamConnectionBridge bridge = entry.getKey();
            SteamAddonNetworkRuntime.BridgeState state = entry.getValue();
            if (bridge.isClosed()) {
               this.bridgeClosed(bridge);
            } else {
               if (!state.helloSent) {
                  this.sendHello(bridge, state, coordinator);
               }

               if (state.requiredLocal && !state.negotiated && now - state.createdAtNanos >= REQUIRED_HELLO_TIMEOUT_NANOS) {
                  bridge.close(true);
               } else if (state.gateUntilHello && !state.negotiated && now - state.createdAtNanos >= OPTIONAL_HELLO_GRACE_NANOS) {
                  state.gateUntilHello = false;
                  bridge.markAddonNegotiated();
               }
            }
         }
      }
   }

   void accept(SteamConnectionBridge bridge, byte frameType, byte[] payload) {
      AddonNetworkCoordinator coordinator = coordinator();
      if (bridge != null && !bridge.isClosed() && coordinator != null) {
         SteamAddonNetworkRuntime.BridgeState state = this.bridges.get(bridge);
         if (state == null) {
            this.bridgeReady(bridge);
            state = this.bridges.get(bridge);
         }

         if (state != null && state.sessionId != null && state.peerId != null) {
            SteamAddonNetworkRuntime.BridgeIdentity identity = new SteamAddonNetworkRuntime.BridgeIdentity(state.sessionId, state.peerId);
            if (frameType == 10) {
               this.acceptHello(bridge, state, identity, payload, coordinator);
            } else if (frameType == 11) {
               this.acceptData(state, identity, payload, coordinator);
            }
         }
      }
   }

   @Override
   public boolean send(SessionService.SessionId sessionId, IdentityService.PeerId peerId, String channelId, int version, byte[] payload, boolean reliable) {
      SteamConnectionBridge bridge = this.find(sessionId, peerId);
      SteamAddonNetworkRuntime.BridgeState state = bridge == null ? null : this.bridges.get(bridge);
      if (bridge != null && state != null && state.negotiated && state.remoteNonce != null && sessionId.equals(state.sessionId) && peerId.equals(state.peerId)) {
         long sequence = state.outboundSequence.incrementAndGet();

         List<byte[]> fragments;
         try {
            fragments = SteamAddonProtocol.encodeData(state.remoteNonce, channelId, version, sequence, payload);
         } catch (RuntimeException var14) {
            return false;
         }

         for (byte[] fragment : fragments) {
            if (!state.sender.sendData(bridge, SteamProtocol.encodeAddonData(bridge.connectionId(), fragment), reliable)) {
               return false;
            }
         }

         return true;
      } else {
         return false;
      }
   }

   private void acceptHello(
      SteamConnectionBridge bridge,
      SteamAddonNetworkRuntime.BridgeState state,
      SteamAddonNetworkRuntime.BridgeIdentity identity,
      byte[] payload,
      AddonNetworkCoordinator coordinator
   ) {
      SteamAddonProtocol.Hello hello = SteamAddonProtocol.decodeHello(payload);
      if (hello != null) {
         List<NetworkService.ChannelDescriptor> remote = new ArrayList<>();

         for (SteamAddonProtocol.ChannelOffer offer : hello.channels()) {
            remote.add(offer.descriptor());
         }

         AddonNetworkCoordinator.Negotiation result = coordinator.negotiate(identity.sessionId, identity.peerId, remote, true);
         if (!result.compatible()) {
            bridge.close(true);
         } else {
            synchronized (state) {
               state.remoteNonce = hello.nonce();
               state.negotiated = true;
            }

            bridge.markAddonNegotiated();
            if (!state.helloSent) {
               this.sendHello(bridge, state, coordinator);
            }
         }
      }
   }

   private void acceptData(
      SteamAddonNetworkRuntime.BridgeState state, SteamAddonNetworkRuntime.BridgeIdentity identity, byte[] payload, AddonNetworkCoordinator coordinator
   ) {
      SteamAddonProtocol.Fragment fragment = SteamAddonProtocol.decodeData(payload);
      if (fragment != null) {
         byte[] localNonce;
         synchronized (state) {
            if (!state.negotiated || state.localNonce == null) {
               return;
            }

            localNonce = (byte[])state.localNonce.clone();
         }

         if (MessageDigest.isEqual(localNonce, fragment.bindingNonce())) {
            byte[] complete = state.accept(fragment, System.nanoTime());
            if (complete != null) {
               coordinator.receive(identity.sessionId, identity.peerId, fragment.channelId(), fragment.version(), complete, true, !state.localIsHost);
            }
         }
      }
   }

   private void sendHello(SteamConnectionBridge bridge, SteamAddonNetworkRuntime.BridgeState state, AddonNetworkCoordinator coordinator) {
      byte[] hello;
      synchronized (state) {
         if (state.helloSent || bridge.isClosed()) {
            return;
         }

         hello = SteamAddonProtocol.encodeHello(state.localNonce, coordinator.localDescriptors());
         state.helloSent = true;
      }

      if (!state.sender.sendHello(bridge, SteamProtocol.encodeAddonHello(bridge.connectionId(), hello))) {
         synchronized (state) {
            state.helloSent = false;
         }
      }
   }

   private SteamAddonNetworkRuntime.BridgeState newState(
      SessionService.SessionId sessionId, IdentityService.PeerId peerId, boolean localIsHost, SteamAddonNetworkRuntime.FrameSender sender
   ) {
      byte[] nonce = new byte[16];
      this.random.nextBytes(nonce);
      return new SteamAddonNetworkRuntime.BridgeState(nonce, sessionId, peerId, localIsHost, sender);
   }

   private SteamConnectionBridge find(SessionService.SessionId sessionId, IdentityService.PeerId peerId) {
      for (Entry<SteamConnectionBridge, SteamAddonNetworkRuntime.BridgeState> entry : this.bridges.entrySet()) {
         SteamAddonNetworkRuntime.BridgeState state = entry.getValue();
         if (sessionId.equals(state.sessionId) && peerId.equals(state.peerId) && !entry.getKey().isClosed()) {
            return entry.getKey();
         }
      }

      return null;
   }

   private static SteamAddonNetworkRuntime.BridgeIdentity clientIdentity(SteamConnectionBridge bridge) {
      SteamClientApiBridge.SessionView view = SteamClientApiBridge.sessionView();
      if (!view.active()) {
         return null;
      } else {
         String opaque = SteamClientApiBridge.opaquePeerId(bridge.remoteSteamId());
         return opaque == null
            ? null
            : new SteamAddonNetworkRuntime.BridgeIdentity(new SessionService.SessionId(view.sessionId(), view.generation()), new IdentityService.PeerId(opaque));
      }
   }

   private static AddonNetworkCoordinator coordinator() {
      CoreApiPlatform platform = CoreApiPlatform.current();
      return platform == null ? null : platform.addonNetwork();
   }

   private static final class Assembly {
      private final String channelId;
      private final int version;
      private final int count;
      private final int totalLength;
      private final long createdAt;
      private final byte[][] fragments;
      private int received;

      private Assembly(SteamAddonProtocol.Fragment first, long createdAt) {
         this.channelId = first.channelId();
         this.version = first.version();
         this.count = first.count();
         this.totalLength = first.totalLength();
         this.createdAt = createdAt;
         this.fragments = new byte[this.count][];
      }

      private byte[] add(SteamAddonProtocol.Fragment fragment) {
         if (this.channelId.equals(fragment.channelId())
            && this.version == fragment.version()
            && this.count == fragment.count()
            && this.totalLength == fragment.totalLength()) {
            if (this.fragments[fragment.index()] == null) {
               this.fragments[fragment.index()] = fragment.payload();
               this.received++;
            }

            if (this.received != this.count) {
               return null;
            } else {
               ByteArrayOutputStream output = new ByteArrayOutputStream(this.totalLength);

               for (byte[] part : this.fragments) {
                  output.write(part, 0, part.length);
               }

               byte[] result = output.toByteArray();
               return result.length == this.totalLength ? result : null;
            }
         } else {
            return null;
         }
      }
   }

   private static final class BridgeIdentity {
      private final SessionService.SessionId sessionId;
      private final IdentityService.PeerId peerId;

      private BridgeIdentity(SessionService.SessionId sessionId, IdentityService.PeerId peerId) {
         this.sessionId = sessionId;
         this.peerId = peerId;
      }
   }

   private static final class BridgeState {
      private byte[] localNonce;
      private byte[] remoteNonce;
      private SessionService.SessionId sessionId;
      private IdentityService.PeerId peerId;
      private boolean localIsHost;
      private final SteamAddonNetworkRuntime.FrameSender sender;
      private boolean helloSent;
      private boolean negotiated;
      private boolean requiredLocal;
      private boolean gateUntilHello;
      private final long createdAtNanos = System.nanoTime();
      private final AtomicLong outboundSequence = new AtomicLong();
      private long highestCompletedSequence;
      private final LinkedHashMap<Long, SteamAddonNetworkRuntime.Assembly> assemblies = new LinkedHashMap<>();

      private BridgeState(
         byte[] localNonce, SessionService.SessionId sessionId, IdentityService.PeerId peerId, boolean localIsHost, SteamAddonNetworkRuntime.FrameSender sender
      ) {
         this.localNonce = localNonce;
         this.sessionId = sessionId;
         this.peerId = peerId;
         this.localIsHost = localIsHost;
         this.sender = sender;
      }

      private synchronized byte[] accept(SteamAddonProtocol.Fragment fragment, long now) {
         this.assemblies.entrySet().removeIf(entry -> now - entry.getValue().createdAt >= SteamAddonNetworkRuntime.ASSEMBLY_TIMEOUT_NANOS);
         if (fragment.sequence() <= this.highestCompletedSequence) {
            return null;
         } else {
            SteamAddonNetworkRuntime.Assembly assembly = this.assemblies.get(fragment.sequence());
            if (assembly == null) {
               if (this.assemblies.size() >= 16) {
                  return null;
               }

               assembly = new SteamAddonNetworkRuntime.Assembly(fragment, now);
               this.assemblies.put(fragment.sequence(), assembly);
            }

            byte[] completed = assembly.add(fragment);
            if (completed == null) {
               return null;
            } else {
               this.assemblies.remove(fragment.sequence());
               this.highestCompletedSequence = fragment.sequence();
               this.assemblies.entrySet().removeIf(entry -> entry.getKey() <= this.highestCompletedSequence);
               return completed;
            }
         }
      }

      private synchronized void destroy() {
         if (this.localNonce != null) {
            Arrays.fill(this.localNonce, (byte)0);
         }

         if (this.remoteNonce != null) {
            Arrays.fill(this.remoteNonce, (byte)0);
         }

         this.localNonce = null;
         this.remoteNonce = null;
         this.assemblies.clear();
      }
   }

   interface FrameSender {
      boolean sendHello(SteamConnectionBridge var1, byte[] var2);

      boolean sendData(SteamConnectionBridge var1, byte[] var2, boolean var3);
   }
}
