package link.e4steam.internal.dedicated;

import java.net.InetAddress;
import java.net.SocketAddress;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import link.e4steam.api.ApiError;
import link.e4steam.api.ApiErrorCode;
import link.e4steam.api.ApiResult;
import link.e4steam.api.Retryability;
import link.e4steam.api.dedicated.DedicatedServerService;
import link.e4steam.api.identity.IdentityService;
import link.e4steam.api.session.SessionService;
import link.e4steam.steam.SteamDedicatedAddress;
import link.e4steam.steam.SteamDedicatedServerTransport;
import link.e4steam.steam.SteamGameServerRuntimeBackend;
import link.e4steam.steam.SteamMinecraftIdentity;
import link.e4steam.steam.SteamPeerPrivacy;
import link.e4steam.steam.SteamRuntimeBackend;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class DedicatedServerController implements AutoCloseable {
   private static final Logger LOGGER = LogManager.getLogger("e4steam");
   private static final AtomicLong GENERATIONS = new AtomicLong();
   private static volatile DedicatedServerController current;
   private final DedicatedRuntimeConfig config;
   private final DedicatedLifecycle lifecycle;
   private final SteamRuntimeBackend backend;
   private final DedicatedIngressRegistry ingress = new DedicatedIngressRegistry();
   private final DedicatedAccessStore accessStore;
   private final AtomicBoolean started = new AtomicBoolean();
   private final AtomicBoolean listenerReady = new AtomicBoolean();
   private final AtomicBoolean minecraftReady = new AtomicBoolean();
   private volatile long backendGeneration;
   private volatile long backendSteamId;
   private volatile int minecraftPort;
   private volatile SteamDedicatedServerTransport transport;

   public DedicatedServerController(DedicatedRuntimeConfig config) {
      this(config, SteamGameServerRuntimeBackend::new);
   }

   DedicatedServerController(DedicatedRuntimeConfig config, DedicatedServerController.BackendFactory factory) {
      this.config = Objects.requireNonNull(config, "config");
      this.lifecycle = new DedicatedLifecycle(config);
      this.accessStore = new DedicatedAccessStore(Paths.get(System.getProperty("user.dir", "."), "config", "e4steam-dedicated-access.txt"));
      this.backend = Objects.requireNonNull(factory, "factory").create(this::backendState);
   }

   public static DedicatedServerController install(DedicatedRuntimeConfig config) {
      synchronized (DedicatedServerController.class) {
         if (current != null) {
            return current;
         } else {
            current = new DedicatedServerController(config);
            return current;
         }
      }
   }

   public static DedicatedServerController current() {
      return current;
   }

   public void minecraftListening(InetAddress bindAddress, int port) {
      if (this.config.enabled()) {
         if (port >= 1 && port <= 65535) {
            try {
               this.config.validateMinecraftBind(bindAddress);
            } catch (RuntimeException var5) {
               this.fail("INGRESS_NOT_LOOPBACK");
               throw var5;
            }

            this.listenerReady.set(true);
            this.minecraftPort = port;
            if (!this.started.compareAndSet(false, true)) {
               this.maybeAccept();
            } else {
               long generation = GENERATIONS.incrementAndGet();
               this.lifecycle.begin(generation);
               this.backend
                  .start(this.config.backend(port))
                  .whenComplete(
                     (ready, failure) -> {
                        if (failure == null && ready != null) {
                           this.backendGeneration = ready.generation();
                           this.backendSteamId = ready.internalServerSteamId();
                           if (this.backend instanceof SteamGameServerRuntimeBackend) {
                              SteamDedicatedServerTransport created = new SteamDedicatedServerTransport(
                                 (SteamGameServerRuntimeBackend)this.backend, this, this.config.maxPeers()
                              );
                              this.transport = created;
                              created.start();
                           }

                           this.maybeAccept();
                        } else {
                           this.fail("GAMESERVER_START_FAILED");
                        }
                     }
                  );
            }
         } else {
            this.fail("INVALID_MINECRAFT_PORT");
         }
      }
   }

   public void minecraftReady() {
      if (this.config.enabled()) {
         this.minecraftReady.set(true);
         this.maybeAccept();
      }
   }

   public void validateMinecraftBind(InetAddress bindAddress) {
      if (this.config.enabled()) {
         this.config.validateMinecraftBind(bindAddress);
      }
   }

   public void minecraftStopped() {
      if (this.started.get()) {
         this.listenerReady.set(false);
         this.minecraftReady.set(false);

         try {
            if (this.lifecycle.snapshot().state() != DedicatedServerService.DedicatedServerState.DRAINING) {
               this.lifecycle.transition(DedicatedServerService.DedicatedServerState.DRAINING);
            }
         } catch (RuntimeException var2) {
         }

         this.ingress.clear();
         SteamDedicatedServerTransport activeTransport = this.transport;
         this.transport = null;
         if (activeTransport != null) {
            activeTransport.close();
         }

         this.backend.stop(SteamRuntimeBackend.ShutdownReason.MINECRAFT_STOPPING).whenComplete((ignored, failure) -> {
            if (failure != null) {
               this.fail("GAMESERVER_STOP_FAILED");
            } else {
               try {
                  this.lifecycle.transition(DedicatedServerService.DedicatedServerState.STOPPED);
               } catch (RuntimeException var4) {
               }
            }
         });
      }
   }

   public long authenticatedMinecraftPeer(SocketAddress remoteAddress) {
      long generation = this.backendGeneration;
      return generation <= 0L ? 0L : this.ingress.resolve(remoteAddress, generation);
   }

   public AutoCloseable registerAuthenticatedIngress(int localPort, long steamId, long generation) {
      if (generation == this.backendGeneration
         && this.backend instanceof SteamGameServerRuntimeBackend
         && ((SteamGameServerRuntimeBackend)this.backend).isAuthenticated(steamId, generation)) {
         return this.ingress.register(localPort, steamId, generation);
      } else {
         throw new SecurityException("Dedicated ingress requires current Steam authentication");
      }
   }

   public DedicatedServerService service() {
      return new DedicatedServerService() {
         @Override
         public ApiResult<DedicatedServerService.DedicatedServerSnapshot> snapshot() {
            return ApiResult.success(DedicatedServerController.this.lifecycle.snapshot());
         }

         @Override
         public ApiResult<DedicatedServerService.DedicatedConfigSnapshot> config() {
            return ApiResult.success(DedicatedServerController.this.config.safeSnapshot());
         }

         @Override
         public CompletionStage<ApiResult<DedicatedServerService.DedicatedServerSnapshot>> readiness() {
            CompletableFuture<ApiResult<DedicatedServerService.DedicatedServerSnapshot>> result = new CompletableFuture<>();
            DedicatedServerController.this.lifecycle
               .readiness()
               .whenComplete(
                  (snapshot, failure) -> {
                     if (failure != null) {
                        result.complete(
                           ApiResult.failure(
                              new ApiError(
                                 ApiErrorCode.UNAVAILABLE,
                                 "error.e4steam.dedicated_not_ready",
                                 Retryability.AFTER_STATE_CHANGE,
                                 "dedicated.readiness",
                                 "",
                                 "DedicatedNotReady"
                              )
                           )
                        );
                     } else {
                        result.complete(ApiResult.success(snapshot));
                     }
                  }
               );
            return result;
         }

         @Override
         public CompletionStage<ApiResult<DedicatedServerService.DedicatedServerSnapshot>> drain(String reason) {
            DedicatedServerController.this.minecraftStopped();
            return CompletableFuture.completedFuture(ApiResult.success(DedicatedServerController.this.lifecycle.snapshot()));
         }

         @Override
         public CompletionStage<ApiResult<DedicatedServerService.PublicationPlan>> proposePublication(DedicatedServerService.PublicationProposal proposal) {
            return CompletableFuture.completedFuture(ApiResult.success(new DedicatedServerService.PublicationPlan(false, "public-worlds-addon-required")));
         }
      };
   }

   public long generation() {
      return this.backendGeneration;
   }

   public int minecraftPort() {
      return this.minecraftPort;
   }

   public int maxPeers() {
      return this.config.maxPeers();
   }

   public SessionService.SessionId addonSessionId() {
      long generation = this.backendGeneration;
      return generation <= 0L ? null : new SessionService.SessionId(SteamPeerPrivacy.dedicatedSessionId(generation), generation);
   }

   public Set<String> addonPeerIds() {
      SteamDedicatedServerTransport active = this.transport;
      return active == null ? Collections.emptySet() : active.safeOpaquePeerIds();
   }

   public boolean matchesAddonPeer(SessionService.SessionId sessionId, IdentityService.PeerId peerId) {
      SessionService.SessionId current = this.addonSessionId();
      return current != null && current.equals(sessionId) && peerId != null && this.addonPeerIds().contains(peerId.value());
   }

   public DedicatedServerController.DedicatedPeerIdentity addonPeerIdentity(String opaquePeerId) {
      if (opaquePeerId != null && !opaquePeerId.isEmpty()) {
         SteamDedicatedServerTransport active = this.transport;
         return active == null ? null : active.safePeerIdentity(opaquePeerId);
      } else {
         return null;
      }
   }

   public boolean accepting() {
      return this.lifecycle.snapshot().state() == DedicatedServerService.DedicatedServerState.ACCEPTING;
   }

   public boolean requiresAuthenticatedIngress() {
      return this.config.enabled() && this.started.get();
   }

   public void players(int value) {
      this.lifecycle.players(value);
   }

   public void transportFailed(String category) {
      this.fail(category);
   }

   public boolean isBanned(long steamId) {
      return this.accessStore.banned(steamId) || this.accessStore.banned(SteamMinecraftIdentity.uuid(steamId));
   }

   public boolean whitelistRequired() {
      return false;
   }

   public boolean isWhitelisted(long steamId) {
      return this.config.isWhitelisted(steamId) || this.accessStore.whitelisted(steamId) || this.accessStore.whitelisted(SteamMinecraftIdentity.uuid(steamId));
   }

   public boolean updateWhitelist(String identity, boolean present) {
      DedicatedServerController.ParsedIdentity parsed = DedicatedServerController.ParsedIdentity.parse(identity);
      return parsed.steamId != 0L ? this.accessStore.setWhitelisted(parsed.steamId, present) : this.accessStore.setWhitelisted(parsed.uuid, present);
   }

   public boolean updateBan(String identity, boolean present) {
      DedicatedServerController.ParsedIdentity parsed = DedicatedServerController.ParsedIdentity.parse(identity);
      return parsed.steamId != 0L ? this.accessStore.setBanned(parsed.steamId, present) : this.accessStore.setBanned(parsed.uuid, present);
   }

   public String normalizedIdentity(String identity) {
      DedicatedServerController.ParsedIdentity parsed = DedicatedServerController.ParsedIdentity.parse(identity);
      return parsed.steamId != 0L ? SteamMinecraftIdentity.uuid(parsed.steamId).toString() : parsed.uuid.toString();
   }

   public String descriptor() {
      long id = this.backendSteamId;
      long generation = this.backendGeneration;
      return id != 0L && generation > 0L && this.accepting() ? new SteamDedicatedAddress(id, generation).descriptor() : "";
   }

   public String safeStatus() {
      DedicatedServerService.DedicatedServerSnapshot snapshot = this.lifecycle.snapshot();
      return "e4steam dedicated: state="
         + snapshot.state()
         + ", access="
         + snapshot.accessMode()
         + ", players="
         + snapshot.players()
         + "/"
         + snapshot.capacity()
         + ", guarded="
         + snapshot.ingressGuarded()
         + ", publication=false";
   }

   @Override
   public void close() {
      this.minecraftStopped();
   }

   private void maybeAccept() {
      if (this.listenerReady.get()
         && this.minecraftReady.get()
         && this.backend.snapshot().state() == SteamRuntimeBackend.State.TRANSPORT_READY
         && this.backendGeneration > 0L
         && this.backendSteamId != 0L) {
         try {
            if (this.lifecycle.snapshot().state() == DedicatedServerService.DedicatedServerState.TRANSPORT_READY) {
               this.lifecycle.accepting(true);
               LOGGER.info("Dedicated e4steam transport is accepting authenticated peers");
               LOGGER.info("e4steam dedicated address: {}", this.descriptor());
            }
         } catch (RuntimeException var2) {
            this.fail("READINESS_TRANSITION_FAILED");
         }
      }
   }

   private void backendState(SteamRuntimeBackend.State state, String category) {
      try {
         switch (state) {
            case CONFIG_VALIDATED:
            case OFF:
               return;
            case NATIVES_READY:
               this.lifecycle.transition(DedicatedServerService.DedicatedServerState.NATIVES_READY);
               return;
            case STEAM_INITIALIZING:
               this.lifecycle.transition(DedicatedServerService.DedicatedServerState.STEAM_INITIALIZING);
               return;
            case STEAM_LOGGING_ON:
               this.lifecycle.transition(DedicatedServerService.DedicatedServerState.STEAM_LOGGING_ON);
               return;
            case TRANSPORT_READY:
               this.lifecycle.transition(DedicatedServerService.DedicatedServerState.TRANSPORT_READY);
               this.maybeAccept();
               return;
            case DRAINING:
               if (this.lifecycle.snapshot().state() != DedicatedServerService.DedicatedServerState.DRAINING) {
                  this.lifecycle.transition(DedicatedServerService.DedicatedServerState.DRAINING);
               }

               return;
            case STOPPED:
               if (this.lifecycle.snapshot().state() == DedicatedServerService.DedicatedServerState.DRAINING) {
                  this.lifecycle.transition(DedicatedServerService.DedicatedServerState.STOPPED);
               }

               return;
            case FAILED:
               this.fail(category);
               return;
         }
      } catch (RuntimeException var4) {
         this.fail("INVALID_BACKEND_TRANSITION");
      }
   }

   private void fail(String category) {
      this.ingress.clear();
      SteamDedicatedServerTransport activeTransport = this.transport;
      this.transport = null;
      if (activeTransport != null) {
         activeTransport.close();
      }

      this.lifecycle.fail(category);
      LOGGER.warn("Dedicated e4steam is unavailable [{}]", category);
   }

   interface BackendFactory {
      SteamRuntimeBackend create(SteamRuntimeBackend.StateListener var1);
   }

   public static final class DedicatedPeerIdentity {
      private final String opaquePeerId;
      private final UUID minecraftUuid;
      private final String minecraftName;

      public DedicatedPeerIdentity(String opaquePeerId, UUID minecraftUuid, String minecraftName) {
         this.opaquePeerId = Objects.requireNonNull(opaquePeerId, "opaquePeerId");
         this.minecraftUuid = Objects.requireNonNull(minecraftUuid, "minecraftUuid");
         this.minecraftName = Objects.requireNonNull(minecraftName, "minecraftName");
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
         return "DedicatedPeerIdentity{opaque}";
      }
   }

   private static final class ParsedIdentity {
      private final long steamId;
      private final UUID uuid;

      private ParsedIdentity(long steamId, UUID uuid) {
         this.steamId = steamId;
         this.uuid = uuid;
      }

      private static DedicatedServerController.ParsedIdentity parse(String value) {
         String checked = Objects.requireNonNull(value, "identity").trim();
         if (!checked.isEmpty() && checked.length() <= 64) {
            try {
               long steamId = Long.parseUnsignedLong(checked);
               if (steamId != 0L) {
                  return new DedicatedServerController.ParsedIdentity(steamId, null);
               }
            } catch (NumberFormatException var5) {
            }

            try {
               return new DedicatedServerController.ParsedIdentity(0L, UUID.fromString(checked));
            } catch (IllegalArgumentException var4) {
               throw new IllegalArgumentException("Expected SteamID64 or derived UUID", var4);
            }
         } else {
            throw new IllegalArgumentException("identity");
         }
      }
   }
}
