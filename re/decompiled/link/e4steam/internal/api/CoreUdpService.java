package link.e4steam.internal.api;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import link.e4steam.api.ApiErrorCode;
import link.e4steam.api.ApiResult;
import link.e4steam.api.Registration;
import link.e4steam.api.ResourceScope;
import link.e4steam.api.addon.AddonId;
import link.e4steam.api.capability.Capabilities;
import link.e4steam.api.udp.UdpService;

final class CoreUdpService implements UdpService {
   private final AddonId owner;
   private final CoreCapabilityService capabilities;
   private final CoreContributionRegistry registry;
   private final ResourceScope resources;

   CoreUdpService(AddonId owner, CoreCapabilityService capabilities, CoreContributionRegistry registry, ResourceScope resources) {
      this.owner = owner;
      this.capabilities = capabilities;
      this.registry = registry;
      this.resources = resources;
   }

   @Override
   public ApiResult<UdpService.EndpointHandle> register(UdpService.EndpointDescriptor descriptor, UdpService.DatagramHandler handler) {
      if (!this.capabilities.has(Capabilities.UDP_PROVIDER_REGISTER)) {
         return this.denied("udp.register");
      } else if (descriptor != null && handler != null) {
         CoreUdpService.Handle handle = new CoreUdpService.Handle(descriptor);
         ApiResult<Registration> registration = this.registry.register("udp", descriptor.id().value(), this.owner, handler, this.resources, true);
         if (!registration.isSuccess()) {
            return ApiResult.failure(registration.error().get());
         } else {
            handle.registration = registration.value().get();
            this.resources.own(handle);
            return ApiResult.success(handle);
         }
      } else {
         return SafeApiErrors.failure(ApiErrorCode.INVALID_ARGUMENT, "udp.register", "Validation");
      }
   }

   private <T> ApiResult<T> denied(String operation) {
      return SafeApiErrors.failure(ApiErrorCode.CAPABILITY_DENIED, operation, "PolicyDenied");
   }

   private static <T> CompletionStage<T> completed(T value) {
      return CompletableFuture.completedFuture(value);
   }

   private final class Handle extends CoreRegistration implements UdpService.EndpointHandle {
      private final UdpService.EndpointDescriptor descriptor;
      private Registration registration;

      private Handle(UdpService.EndpointDescriptor descriptor) {
         super(null);
         this.descriptor = descriptor;
      }

      @Override
      public UdpService.EndpointDescriptor descriptor() {
         return this.descriptor;
      }

      @Override
      public boolean ready() {
         return false;
      }

      @Override
      public CompletionStage<ApiResult<Boolean>> send(UdpService.Datagram datagram) {
         if (!CoreUdpService.this.capabilities.has(Capabilities.UDP_PROVIDER_REGISTER)) {
            return CoreUdpService.completed(CoreUdpService.this.denied("udp.send"));
         } else {
            return datagram != null
                  && datagram.endpointId().equals(this.descriptor.id())
                  && datagram.payload().length <= this.descriptor.maximumDatagramBytes()
               ? CoreUdpService.completed(SafeApiErrors.failure(ApiErrorCode.UNAVAILABLE, "udp.send", "NotNegotiated"))
               : CoreUdpService.completed(SafeApiErrors.failure(ApiErrorCode.INVALID_ARGUMENT, "udp.send", "Bounds"));
         }
      }

      @Override
      public void close() {
         if (!this.isClosed()) {
            super.close();
            if (this.registration != null) {
               this.registration.close();
            }
         }
      }
   }
}
