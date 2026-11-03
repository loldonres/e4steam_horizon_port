package link.e4steam.internal.api;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import link.e4steam.api.ApiErrorCode;
import link.e4steam.api.ApiResult;
import link.e4steam.api.addon.AddonId;
import link.e4steam.api.capability.Capabilities;
import link.e4steam.api.identity.IdentityService;
import link.e4steam.api.network.NetworkService;
import link.e4steam.api.scheduler.ExecutionContext;
import link.e4steam.api.scheduler.TaskHandle;
import link.e4steam.api.session.SessionService;

public final class AddonNetworkCoordinator implements AutoCloseable {
   private final Object lock = new Object();
   private final CoreSchedulerService scheduler;
   private final AddonNetworkCoordinator.SessionAccess sessions;
   private final Map<String, AddonNetworkCoordinator.Endpoint> channels = new LinkedHashMap<>();
   private final Map<AddonNetworkCoordinator.PeerKey, AddonNetworkCoordinator.PeerState> peers = new HashMap<>();
   private volatile AddonNetworkCoordinator.Transport transport;
   private boolean closed;

   AddonNetworkCoordinator(CoreSchedulerService scheduler, CoreSessionRegistry sessions) {
      this(scheduler, new AddonNetworkCoordinator.SessionAccess() {
         @Override
         public boolean matches(SessionService.SessionId sessionId, IdentityService.PeerId peerId) {
            return sessions.matches(sessionId, peerId);
         }

         @Override
         public boolean localIsHost(SessionService.SessionId sessionId) {
            return sessions.localIsHost(sessionId);
         }
      });
   }

   AddonNetworkCoordinator(CoreSchedulerService scheduler, AddonNetworkCoordinator.SessionAccess sessions) {
      this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
      this.sessions = Objects.requireNonNull(sessions, "sessions");
   }

   ApiResult<NetworkService.ChannelHandle> register(
      AddonId owner, NetworkService.ChannelDescriptor descriptor, NetworkService.MessageHandler handler, CoreCapabilityService capabilities
   ) {
      if (owner != null && descriptor != null && handler != null && capabilities != null) {
         AddonNetworkCoordinator.Endpoint endpoint;
         synchronized (this.lock) {
            if (this.closed) {
               return SafeApiErrors.failure(ApiErrorCode.UNAVAILABLE, "network.register", "Shutdown");
            }

            if (this.channels.size() >= 32) {
               return SafeApiErrors.failure(ApiErrorCode.QUEUE_FULL, "network.register", "RegistrationLimit");
            }

            String id = descriptor.id().value();
            if (this.channels.containsKey(id)) {
               return SafeApiErrors.failure(ApiErrorCode.INVALID_ARGUMENT, "network.register", "DuplicateId");
            }

            endpoint = new AddonNetworkCoordinator.Endpoint(owner, descriptor, handler, capabilities);
            this.channels.put(id, endpoint);
         }

         return ApiResult.success(endpoint.handle);
      } else {
         return SafeApiErrors.failure(ApiErrorCode.INVALID_ARGUMENT, "network.register", "Validation");
      }
   }

   public List<NetworkService.ChannelDescriptor> localDescriptors() {
      synchronized (this.lock) {
         List<NetworkService.ChannelDescriptor> result = new ArrayList<>();

         for (AddonNetworkCoordinator.Endpoint endpoint : this.channels.values()) {
            if (!endpoint.handle.isClosed()) {
               result.add(endpoint.descriptor);
            }
         }

         result.sort(Comparator.comparing(value -> value.id().value()));
         return Collections.unmodifiableList(result);
      }
   }

   public boolean hasRequiredChannels() {
      synchronized (this.lock) {
         for (AddonNetworkCoordinator.Endpoint endpoint : this.channels.values()) {
            if (!endpoint.handle.isClosed() && endpoint.descriptor.requirement() == NetworkService.Requirement.REQUIRED) {
               return true;
            }
         }

         return false;
      }
   }

   public boolean hasChannels() {
      synchronized (this.lock) {
         for (AddonNetworkCoordinator.Endpoint endpoint : this.channels.values()) {
            if (!endpoint.handle.isClosed()) {
               return true;
            }
         }

         return false;
      }
   }

   public AddonNetworkCoordinator.Negotiation negotiate(
      SessionService.SessionId sessionId, IdentityService.PeerId peerId, List<NetworkService.ChannelDescriptor> remote, boolean authenticated
   ) {
      if (authenticated && this.current(sessionId, peerId) && remote != null && remote.size() <= 32) {
         Map<String, NetworkService.ChannelDescriptor> remoteById = new HashMap<>();

         for (NetworkService.ChannelDescriptor descriptor : remote) {
            if (descriptor == null || remoteById.put(descriptor.id().value(), descriptor) != null) {
               return new AddonNetworkCoordinator.Negotiation(false, "invalid-channel-list", Collections.emptyMap());
            }
         }

         LinkedHashMap<String, Integer> selected = new LinkedHashMap<>();
         LinkedHashMap<String, AddonNetworkCoordinator.NegotiatedChannel> negotiated = new LinkedHashMap<>();
         synchronized (this.lock) {
            if (this.closed) {
               return new AddonNetworkCoordinator.Negotiation(false, "runtime-closed", selected);
            }

            for (AddonNetworkCoordinator.Endpoint endpoint : this.channels.values()) {
               if (!endpoint.handle.isClosed()) {
                  NetworkService.ChannelDescriptor local = endpoint.descriptor;
                  NetworkService.ChannelDescriptor offered = remoteById.remove(local.id().value());
                  int version = offered == null ? 0 : compatibleVersion(local, offered);
                  if (version == 0) {
                     if (local.requirement() == NetworkService.Requirement.REQUIRED
                        || offered != null && offered.requirement() == NetworkService.Requirement.REQUIRED) {
                        return this.rejectPeer(sessionId, peerId, "required-channel-incompatible");
                     }
                  } else {
                     selected.put(local.id().value(), version);
                     negotiated.put(
                        local.id().value(),
                        new AddonNetworkCoordinator.NegotiatedChannel(
                           version,
                           Math.min(local.maximumMessageBytes(), offered.maximumMessageBytes()),
                           Math.min(local.bytesPerSecond(), offered.bytesPerSecond()),
                           Math.min(local.queueMessages(), offered.queueMessages())
                        )
                     );
                  }
               }
            }

            for (NetworkService.ChannelDescriptor offered : remoteById.values()) {
               if (offered.requirement() == NetworkService.Requirement.REQUIRED) {
                  return this.rejectPeer(sessionId, peerId, "required-channel-missing");
               }
            }

            AddonNetworkCoordinator.PeerState state = new AddonNetworkCoordinator.PeerState(sessionId, peerId, negotiated);
            this.peers.put(new AddonNetworkCoordinator.PeerKey(sessionId, peerId), state);
            this.refreshHandleStates();
         }

         return new AddonNetworkCoordinator.Negotiation(true, "ok", selected);
      } else {
         return new AddonNetworkCoordinator.Negotiation(false, authenticated ? "stale-session" : "unauthenticated", Collections.emptyMap());
      }
   }

   public ApiResult<Boolean> receive(
      SessionService.SessionId sessionId,
      IdentityService.PeerId peerId,
      String channelId,
      int version,
      byte[] payload,
      boolean authenticated,
      boolean senderIsHost
   ) {
      if (authenticated && channelId != null && payload != null) {
         byte[] defensive = (byte[])payload.clone();
         AddonNetworkCoordinator.Endpoint endpoint;
         AddonNetworkCoordinator.PeerState peer;
         synchronized (this.lock) {
            if (this.closed || !this.current(sessionId, peerId)) {
               return SafeApiErrors.failure(ApiErrorCode.STALE_HANDLE, "network.receive", "SessionGeneration");
            }

            endpoint = this.channels.get(channelId);
            peer = this.peers.get(new AddonNetworkCoordinator.PeerKey(sessionId, peerId));
            AddonNetworkCoordinator.NegotiatedChannel negotiated = peer == null ? null : peer.channels.get(channelId);
            if (endpoint == null || endpoint.handle.isClosed() || negotiated == null || negotiated.version != version) {
               return SafeApiErrors.failure(ApiErrorCode.SECURITY_REJECTION, "network.receive", "NotNegotiated");
            }

            if (defensive.length < 1 || defensive.length > negotiated.maximumMessageBytes) {
               return SafeApiErrors.failure(ApiErrorCode.INVALID_ARGUMENT, "network.receive", "Bounds");
            }

            if (!directionAllowsReceive(endpoint.descriptor.direction(), senderIsHost)) {
               return SafeApiErrors.failure(ApiErrorCode.SECURITY_REJECTION, "network.receive", "Direction");
            }

            if (!peer.allowInbound(endpoint.owner.value(), channelId, defensive.length, negotiated.bytesPerSecond, System.nanoTime())) {
               return SafeApiErrors.failure(ApiErrorCode.RATE_LIMITED, "network.receive", "ChannelBudget");
            }

            if (!peer.beginInbound(endpoint.owner.value(), channelId, negotiated.queueMessages)) {
               return SafeApiErrors.failure(ApiErrorCode.QUEUE_FULL, "network.receive", "InboundQueue");
            }
         }

         ApiResult<TaskHandle> scheduled = this.scheduler
            .execute(ExecutionContext.ADDON_WORKER, () -> this.invokeHandler(endpoint, peer, channelId, version, defensive), Duration.ofSeconds(2L));
         if (!scheduled.isSuccess()) {
            synchronized (this.lock) {
               peer.endInbound(endpoint.owner.value(), channelId);
            }

            return scheduled.error().map(ApiResult::failure).orElseGet(() -> SafeApiErrors.failure(ApiErrorCode.QUEUE_FULL, "network.receive", "WorkerQueue"));
         } else {
            return ApiResult.success(true);
         }
      } else {
         return SafeApiErrors.failure(ApiErrorCode.SECURITY_REJECTION, "network.receive", "Unauthenticated");
      }
   }

   public void transport(AddonNetworkCoordinator.Transport transport) {
      this.transport = transport;
   }

   public void closeSession(long generation) {
      synchronized (this.lock) {
         this.peers.entrySet().removeIf(entry -> entry.getKey().generation == generation);
         this.refreshHandleStates();
      }
   }

   public void closePeer(SessionService.SessionId sessionId, IdentityService.PeerId peerId) {
      if (sessionId != null && peerId != null) {
         synchronized (this.lock) {
            this.peers.remove(new AddonNetworkCoordinator.PeerKey(sessionId, peerId));
            this.refreshHandleStates();
         }
      }
   }

   @Override
   public void close() {
      synchronized (this.lock) {
         if (!this.closed) {
            this.closed = true;
            this.peers.clear();

            for (AddonNetworkCoordinator.Endpoint endpoint : this.channels.values()) {
               endpoint.handle.closeInternal();
            }

            this.channels.clear();
         }
      }
   }

   private void invokeHandler(AddonNetworkCoordinator.Endpoint endpoint, AddonNetworkCoordinator.PeerState peer, String channelId, int version, byte[] payload) {
      boolean handedOff = false;

      try {
         CompletionStage<ApiResult<Boolean>> stage = endpoint.handler
            .onMessage(new NetworkService.MessageContext(peer.sessionId, peer.peerId, version), (byte[])payload.clone());
         if (stage != null) {
            handedOff = true;
            stage.toCompletableFuture().orTimeout(2L, TimeUnit.SECONDS).whenComplete((result, failure) -> {
               synchronized (this.lock) {
                  peer.endInbound(endpoint.owner.value(), channelId);
               }
            });
            return;
         }
      } catch (ThreadDeath | VirtualMachineError var23) {
         throw var23;
      } catch (Throwable var24) {
         return;
      } finally {
         if (!handedOff) {
            synchronized (this.lock) {
               peer.endInbound(endpoint.owner.value(), channelId);
            }
         }
      }
   }

   private CompletionStage<ApiResult<NetworkService.SendStatus>> send(
      AddonNetworkCoordinator.Endpoint endpoint, SessionService.SessionId sessionId, IdentityService.PeerId peerId, byte[] payload
   ) {
      if (payload != null && payload.length >= 1 && payload.length <= endpoint.descriptor.maximumMessageBytes()) {
         byte[] copy = (byte[])payload.clone();
         AddonNetworkCoordinator.PeerState peer;
         int version;
         synchronized (this.lock) {
            if (this.closed || endpoint.handle.isClosed()) {
               return completed(ApiResult.success(NetworkService.SendStatus.CLOSED));
            }

            if (!this.current(sessionId, peerId)) {
               return completed(ApiResult.success(NetworkService.SendStatus.STALE_SESSION));
            }

            peer = this.peers.get(new AddonNetworkCoordinator.PeerKey(sessionId, peerId));
            AddonNetworkCoordinator.NegotiatedChannel selected = peer == null ? null : peer.channels.get(endpoint.descriptor.id().value());
            if (selected == null) {
               return completed(ApiResult.success(NetworkService.SendStatus.UNAVAILABLE));
            }

            version = selected.version;
            if (copy.length > selected.maximumMessageBytes) {
               return completed(SafeApiErrors.failure(ApiErrorCode.INVALID_ARGUMENT, "network.send", "NegotiatedBounds"));
            }

            if (!directionAllowsSend(endpoint.descriptor.direction(), this.sessions.localIsHost(sessionId))) {
               return completed(ApiResult.success(NetworkService.SendStatus.UNAVAILABLE));
            }

            if (!peer.allowOutbound(endpoint.owner.value(), endpoint.descriptor.id().value(), copy.length, selected.bytesPerSecond, System.nanoTime())) {
               return completed(ApiResult.success(NetworkService.SendStatus.RATE_LIMITED));
            }

            if (!peer.beginOutbound(endpoint.owner.value(), endpoint.descriptor.id().value(), selected.queueMessages)) {
               return completed(ApiResult.success(NetworkService.SendStatus.QUEUE_FULL));
            }
         }

         AddonNetworkCoordinator.Transport currentTransport = this.transport;
         boolean reliable = endpoint.descriptor.delivery() != NetworkService.Delivery.UNRELIABLE;
         if (currentTransport == null) {
            synchronized (this.lock) {
               peer.endOutbound(endpoint.owner.value(), endpoint.descriptor.id().value());
            }

            return completed(ApiResult.success(NetworkService.SendStatus.UNAVAILABLE));
         } else {
            boolean accepted = currentTransport.send(sessionId, peerId, endpoint.descriptor.id().value(), version, copy, reliable);
            synchronized (this.lock) {
               peer.endOutbound(endpoint.owner.value(), endpoint.descriptor.id().value());
            }

            return completed(ApiResult.success(accepted ? NetworkService.SendStatus.ACCEPTED : NetworkService.SendStatus.QUEUE_FULL));
         }
      } else {
         return completed(SafeApiErrors.failure(ApiErrorCode.INVALID_ARGUMENT, "network.send", "Bounds"));
      }
   }

   private void unregister(AddonNetworkCoordinator.Endpoint endpoint) {
      synchronized (this.lock) {
         this.channels.remove(endpoint.descriptor.id().value(), endpoint);

         for (AddonNetworkCoordinator.PeerState peer : this.peers.values()) {
            peer.channels.remove(endpoint.descriptor.id().value());
         }

         this.refreshHandleStates();
      }
   }

   private boolean current(SessionService.SessionId sessionId, IdentityService.PeerId peerId) {
      return sessionId != null && peerId != null ? this.sessions.matches(sessionId, peerId) : false;
   }

   private AddonNetworkCoordinator.Negotiation rejectPeer(SessionService.SessionId sessionId, IdentityService.PeerId peerId, String reason) {
      this.peers.remove(new AddonNetworkCoordinator.PeerKey(sessionId, peerId));
      this.refreshHandleStates();
      return new AddonNetworkCoordinator.Negotiation(false, reason, Collections.emptyMap());
   }

   private void refreshHandleStates() {
      for (AddonNetworkCoordinator.Endpoint endpoint : this.channels.values()) {
         boolean available = false;

         for (AddonNetworkCoordinator.PeerState peer : this.peers.values()) {
            if (peer.channels.containsKey(endpoint.descriptor.id().value())) {
               available = true;
               break;
            }
         }

         endpoint.handle.state = available ? NetworkService.ChannelState.AVAILABLE : NetworkService.ChannelState.REGISTERED;
      }
   }

   private static int compatibleVersion(NetworkService.ChannelDescriptor local, NetworkService.ChannelDescriptor remote) {
      if (local.schemaId().equals(remote.schemaId()) && local.delivery() == remote.delivery() && local.direction() == remote.direction()) {
         int minimum = Math.max(local.minimumVersion(), remote.minimumVersion());
         int maximum = Math.min(local.maximumVersion(), remote.maximumVersion());
         return maximum >= minimum ? maximum : 0;
      } else {
         return 0;
      }
   }

   private static boolean directionAllowsReceive(NetworkService.Direction direction, boolean senderIsHost) {
      return direction == NetworkService.Direction.BIDIRECTIONAL
         || senderIsHost && direction == NetworkService.Direction.HOST_TO_CLIENT
         || !senderIsHost && direction == NetworkService.Direction.CLIENT_TO_HOST;
   }

   private static boolean directionAllowsSend(NetworkService.Direction direction, boolean localIsHost) {
      return direction == NetworkService.Direction.BIDIRECTIONAL
         || localIsHost && direction == NetworkService.Direction.HOST_TO_CLIENT
         || !localIsHost && direction == NetworkService.Direction.CLIENT_TO_HOST;
   }

   private static <T> CompletionStage<T> completed(T value) {
      return CompletableFuture.completedFuture(value);
   }

   private static final class Budget {
      private static final long WINDOW_NANOS = TimeUnit.SECONDS.toNanos(1L);
      private final Map<String, AddonNetworkCoordinator.Window> windows = new HashMap<>();

      private boolean allow(String id, int bytes, int maximum, long now) {
         AddonNetworkCoordinator.Window window = this.windows.computeIfAbsent(id, ignored -> new AddonNetworkCoordinator.Window(now));
         if (now - window.started >= WINDOW_NANOS || now < window.started) {
            window.started = now;
            window.bytes = 0;
         }

         if (bytes > maximum - window.bytes) {
            return false;
         } else {
            window.bytes += bytes;
            return true;
         }
      }
   }

   private final class Endpoint {
      private final AddonId owner;
      private final NetworkService.ChannelDescriptor descriptor;
      private final NetworkService.MessageHandler handler;
      private final CoreCapabilityService capabilities;
      private final AddonNetworkCoordinator.Endpoint.Handle handle = new AddonNetworkCoordinator.Endpoint.Handle();

      private Endpoint(AddonId owner, NetworkService.ChannelDescriptor descriptor, NetworkService.MessageHandler handler, CoreCapabilityService capabilities) {
         this.owner = owner;
         this.descriptor = descriptor;
         this.handler = handler;
         this.capabilities = capabilities;
      }

      private final class Handle extends CoreRegistration implements NetworkService.ChannelHandle {
         private volatile NetworkService.ChannelState state = NetworkService.ChannelState.REGISTERED;

         private Handle() {
            super(null);
         }

         @Override
         public NetworkService.ChannelDescriptor descriptor() {
            return Endpoint.this.descriptor;
         }

         @Override
         public NetworkService.ChannelState state() {
            return this.isClosed() ? NetworkService.ChannelState.CLOSED : this.state;
         }

         @Override
         public CompletionStage<ApiResult<NetworkService.SendStatus>> send(SessionService.SessionId sessionId, IdentityService.PeerId peerId, byte[] payload) {
            return !Endpoint.this.capabilities.has(Capabilities.NETWORK_CHANNEL_REGISTER)
               ? AddonNetworkCoordinator.completed(SafeApiErrors.failure(ApiErrorCode.CAPABILITY_DENIED, "network.send", "PolicyDenied"))
               : AddonNetworkCoordinator.this.send(Endpoint.this, sessionId, peerId, payload);
         }

         @Override
         public void close() {
            if (!this.isClosed()) {
               super.close();
               AddonNetworkCoordinator.this.unregister(Endpoint.this);
            }
         }

         private void closeInternal() {
            super.close();
            this.state = NetworkService.ChannelState.CLOSED;
         }
      }
   }

   private static final class NegotiatedChannel {
      private final int version;
      private final int maximumMessageBytes;
      private final int bytesPerSecond;
      private final int queueMessages;

      private NegotiatedChannel(int version, int maximumMessageBytes, int bytesPerSecond, int queueMessages) {
         this.version = version;
         this.maximumMessageBytes = maximumMessageBytes;
         this.bytesPerSecond = bytesPerSecond;
         this.queueMessages = queueMessages;
      }
   }

   public static final class Negotiation {
      private final boolean compatible;
      private final String reasonCode;
      private final Map<String, Integer> channels;

      private Negotiation(boolean compatible, String reasonCode, Map<String, Integer> channels) {
         this.compatible = compatible;
         this.reasonCode = reasonCode;
         this.channels = Collections.unmodifiableMap(new LinkedHashMap<>(channels));
      }

      public boolean compatible() {
         return this.compatible;
      }

      public String reasonCode() {
         return this.reasonCode;
      }

      public Map<String, Integer> channels() {
         return this.channels;
      }

      @Override
      public String toString() {
         return "Negotiation{compatible=" + this.compatible + ", channels=" + this.channels.size() + "}";
      }
   }

   private static final class PeerKey {
      private final long generation;
      private final String session;
      private final String peer;

      private PeerKey(SessionService.SessionId sessionId, IdentityService.PeerId peerId) {
         this.generation = sessionId.generation();
         this.session = sessionId.value();
         this.peer = peerId.value();
      }

      @Override
      public boolean equals(Object other) {
         if (this == other) {
            return true;
         } else {
            return !(other instanceof AddonNetworkCoordinator.PeerKey value)
               ? false
               : this.generation == value.generation && this.session.equals(value.session) && this.peer.equals(value.peer);
         }
      }

      @Override
      public int hashCode() {
         int result = this.session.hashCode();
         result = 31 * result + this.peer.hashCode();
         return 31 * result + (int)(this.generation ^ this.generation >>> 32);
      }
   }

   private static final class PeerState {
      private final SessionService.SessionId sessionId;
      private final IdentityService.PeerId peerId;
      private final Map<String, AddonNetworkCoordinator.NegotiatedChannel> channels;
      private final AddonNetworkCoordinator.Budget inbound = new AddonNetworkCoordinator.Budget();
      private final AddonNetworkCoordinator.Budget outbound = new AddonNetworkCoordinator.Budget();
      private final Map<String, Integer> inboundQueued = new HashMap<>();
      private final Map<String, Integer> outboundQueued = new HashMap<>();

      private PeerState(SessionService.SessionId sessionId, IdentityService.PeerId peerId, Map<String, AddonNetworkCoordinator.NegotiatedChannel> channels) {
         this.sessionId = sessionId;
         this.peerId = peerId;
         this.channels = new LinkedHashMap<>(channels);
      }

      private boolean allowInbound(String owner, String id, int bytes, int channelMaximum, long now) {
         return this.inbound.allow("*", bytes, 4194304, now)
            && this.inbound.allow("addon:" + owner, bytes, 2097152, now)
            && this.inbound.allow("channel:" + id, bytes, channelMaximum, now);
      }

      private boolean allowOutbound(String owner, String id, int bytes, int channelMaximum, long now) {
         return this.outbound.allow("*", bytes, 4194304, now)
            && this.outbound.allow("addon:" + owner, bytes, 2097152, now)
            && this.outbound.allow("channel:" + id, bytes, channelMaximum, now);
      }

      private boolean beginInbound(String owner, String id, int maximum) {
         return incrementAll(this.inboundQueued, owner, id, maximum);
      }

      private boolean beginOutbound(String owner, String id, int maximum) {
         return incrementAll(this.outboundQueued, owner, id, maximum);
      }

      private void endInbound(String owner, String id) {
         decrementAll(this.inboundQueued, owner, id);
      }

      private void endOutbound(String owner, String id) {
         decrementAll(this.outboundQueued, owner, id);
      }

      private static boolean incrementAll(Map<String, Integer> values, String owner, String id, int channelMaximum) {
         if (!increment(values, "*", 256)) {
            return false;
         } else if (!increment(values, "addon:" + owner, 64)) {
            decrement(values, "*");
            return false;
         } else if (!increment(values, "channel:" + id, channelMaximum)) {
            decrement(values, "addon:" + owner);
            decrement(values, "*");
            return false;
         } else {
            return true;
         }
      }

      private static void decrementAll(Map<String, Integer> values, String owner, String id) {
         decrement(values, "channel:" + id);
         decrement(values, "addon:" + owner);
         decrement(values, "*");
      }

      private static boolean increment(Map<String, Integer> values, String id, int maximum) {
         int next = values.getOrDefault(id, 0) + 1;
         if (next > maximum) {
            return false;
         } else {
            values.put(id, next);
            return true;
         }
      }

      private static void decrement(Map<String, Integer> values, String id) {
         int next = values.getOrDefault(id, 0) - 1;
         if (next <= 0) {
            values.remove(id);
         } else {
            values.put(id, next);
         }
      }
   }

   interface SessionAccess {
      boolean matches(SessionService.SessionId var1, IdentityService.PeerId var2);

      boolean localIsHost(SessionService.SessionId var1);
   }

   private static final class SteamLimits {
      private static final int MAX_CHANNELS = 32;
      private static final int GLOBAL_BYTES_PER_SECOND = 4194304;
      private static final int ADDON_BYTES_PER_SECOND = 2097152;
      private static final int GLOBAL_QUEUED_MESSAGES = 256;
      private static final int ADDON_QUEUED_MESSAGES = 64;
   }

   public interface Transport {
      boolean send(SessionService.SessionId var1, IdentityService.PeerId var2, String var3, int var4, byte[] var5, boolean var6);
   }

   private static final class Window {
      private long started;
      private int bytes;

      private Window(long started) {
         this.started = started;
      }
   }
}
