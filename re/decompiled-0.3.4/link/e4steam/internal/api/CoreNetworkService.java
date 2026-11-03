package link.e4steam.internal.api;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import link.e4steam.api.ApiErrorCode;
import link.e4steam.api.ApiResult;
import link.e4steam.api.Registration;
import link.e4steam.api.ResourceScope;
import link.e4steam.api.addon.AddonId;
import link.e4steam.api.capability.Capabilities;
import link.e4steam.api.identity.IdentityService;
import link.e4steam.api.network.NetworkService;
import link.e4steam.api.session.SessionService;

final class CoreNetworkService implements NetworkService {
   private final AddonId owner;
   private final CoreCapabilityService capabilities;
   private final CoreContributionRegistry registry;
   private final ResourceScope resources;
   private final AddonNetworkCoordinator coordinator;

   CoreNetworkService(
      AddonId owner, CoreCapabilityService capabilities, CoreContributionRegistry registry, ResourceScope resources, AddonNetworkCoordinator coordinator
   ) {
      this.owner = owner;
      this.capabilities = capabilities;
      this.registry = registry;
      this.resources = resources;
      this.coordinator = coordinator;
   }

   @Override
   public ApiResult<NetworkService.ChannelHandle> register(NetworkService.ChannelDescriptor descriptor, NetworkService.MessageHandler handler) {
      if (!this.capabilities.has(Capabilities.NETWORK_CHANNEL_REGISTER)) {
         return SafeApiErrors.failure(ApiErrorCode.CAPABILITY_DENIED, "network.register", "PolicyDenied");
      } else if (descriptor != null && handler != null) {
         ApiResult<Registration> reservation = this.registry.register("network", descriptor.id().value(), this.owner, descriptor, this.resources, true);
         if (!reservation.isSuccess()) {
            return ApiResult.failure(reservation.error().get());
         } else {
            ApiResult<NetworkService.ChannelHandle> registered = this.coordinator.register(this.owner, descriptor, handler, this.capabilities);
            if (!registered.isSuccess()) {
               reservation.value().get().close();
               return registered;
            } else {
               CoreNetworkService.BoundHandle handle = new CoreNetworkService.BoundHandle(registered.value().get(), reservation.value().get());
               this.resources.own(handle);
               return ApiResult.success(handle);
            }
         }
      } else {
         return SafeApiErrors.failure(ApiErrorCode.INVALID_ARGUMENT, "network.register", "Validation");
      }
   }

   @Override
   public boolean registrationsFrozen() {
      return this.registry.isFrozen();
   }

   private static final class BoundHandle extends CoreRegistration implements NetworkService.ChannelHandle {
      private final NetworkService.ChannelHandle delegate;
      private final Registration reservation;

      private BoundHandle(NetworkService.ChannelHandle delegate, Registration reservation) {
         super(null);
         this.delegate = delegate;
         this.reservation = reservation;
      }

      @Override
      public NetworkService.ChannelDescriptor descriptor() {
         return this.delegate.descriptor();
      }

      @Override
      public NetworkService.ChannelState state() {
         return this.isClosed() ? NetworkService.ChannelState.CLOSED : this.delegate.state();
      }

      @Override
      public CompletionStage<ApiResult<NetworkService.SendStatus>> send(SessionService.SessionId sessionId, IdentityService.PeerId peerId, byte[] payload) {
         return (CompletionStage<ApiResult<NetworkService.SendStatus>>)(this.isClosed()
            ? CompletableFuture.completedFuture(ApiResult.success(NetworkService.SendStatus.CLOSED))
            : this.delegate.send(sessionId, peerId, payload));
      }

      @Override
      public void close() {
         if (!this.isClosed()) {
            super.close();
            this.delegate.close();
            this.reservation.close();
         }
      }
   }
}
