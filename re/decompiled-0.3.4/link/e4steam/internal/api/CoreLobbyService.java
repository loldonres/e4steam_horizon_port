package link.e4steam.internal.api;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import link.e4steam.api.ApiErrorCode;
import link.e4steam.api.ApiResult;
import link.e4steam.api.Registration;
import link.e4steam.api.ResourceScope;
import link.e4steam.api.addon.AddonId;
import link.e4steam.api.capability.Capabilities;
import link.e4steam.api.capability.CapabilityId;
import link.e4steam.api.lobby.LobbyService;
import link.e4steam.api.scheduler.ExecutionContext;
import link.e4steam.steam.SteamClientApiBridge;

final class CoreLobbyService implements LobbyService, Registration {
   private final String owner;
   private final CoreCapabilityService capabilities;
   private final CoreSchedulerService scheduler;
   private final AtomicBoolean closed = new AtomicBoolean();
   private final Semaphore pending = new Semaphore(4);

   CoreLobbyService(AddonId owner, CoreCapabilityService capabilities, CoreSchedulerService scheduler, ResourceScope resources) {
      this.owner = owner.value();
      this.capabilities = capabilities;
      this.scheduler = scheduler;
      resources.own(this);
   }

   static boolean ownsMetadata(String owner, LobbyService.Metadata metadata) {
      if (metadata == null) {
         return false;
      } else {
         String prefix = owner.replace(':', '-') + ":";
         return metadata.values().keySet().stream().allMatch(key -> key.startsWith(prefix));
      }
   }

   @Override
   public CompletionStage<ApiResult<LobbyService.LobbySnapshot>> create(LobbyService.CreateRequest request) {
      return request != null && ownsMetadata(this.owner, request.metadata())
         ? this.call(Capabilities.LOBBY_CREATE, service -> service.create(request))
         : this.invalid();
   }

   @Override
   public CompletionStage<ApiResult<LobbyService.LobbySnapshot>> updateMetadata(LobbyService.LobbyId id, LobbyService.Metadata metadata) {
      return id != null && ownsMetadata(this.owner, metadata)
         ? this.call(Capabilities.LOBBY_METADATA_WRITE, service -> service.updateMetadata(id, metadata))
         : this.invalid();
   }

   @Override
   public CompletionStage<ApiResult<LobbyService.SearchPage>> search(LobbyService.SearchQuery query) {
      return query != null && ownsMetadata(this.owner, query.filters()) && query.cursor().isEmpty()
         ? this.call(Capabilities.LOBBY_SEARCH, service -> service.search(query))
         : this.invalid();
   }

   @Override
   public CompletionStage<ApiResult<Boolean>> leave(LobbyService.LobbyId id) {
      return id == null ? this.invalid() : this.call(Capabilities.LOBBY_CREATE, service -> service.leave(id));
   }

   @Override
   public CompletionStage<ApiResult<LobbyService.LobbySnapshot>> join(LobbyService.LobbyId id) {
      return CompletableFuture.completedFuture(error(ApiErrorCode.UNSUPPORTED, "UseDirectoryJoin"));
   }

   private <T> CompletionStage<ApiResult<T>> invalid() {
      return CompletableFuture.completedFuture(error(ApiErrorCode.INVALID_ARGUMENT, "LobbyRequest"));
   }

   private <T> CompletionStage<ApiResult<T>> call(CapabilityId capability, Function<LobbyService, CompletionStage<ApiResult<T>>> action) {
      if (!this.capabilities.has(capability)) {
         return CompletableFuture.completedFuture(error(ApiErrorCode.CAPABILITY_DENIED, "LobbyCapability"));
      } else if (this.closed.get()) {
         return CompletableFuture.completedFuture(error(ApiErrorCode.UNAVAILABLE, "AddonStopped"));
      } else {
         LobbyService service = SteamClientApiBridge.addonLobbies(this.owner);
         if (service == null) {
            return CompletableFuture.completedFuture(error(ApiErrorCode.UNAVAILABLE, "SteamUnavailable"));
         } else if (!this.pending.tryAcquire()) {
            return CompletableFuture.completedFuture(error(ApiErrorCode.QUEUE_FULL, "LobbyQueue"));
         } else {
            CompletableFuture<ApiResult<T>> result = new CompletableFuture<>();
            result.completeOnTimeout(error(ApiErrorCode.UNAVAILABLE, "LobbyTimeout"), 20L, TimeUnit.SECONDS);
            result.whenComplete((ignored, failure) -> this.pending.release());

            try {
               action.apply(service)
                  .whenComplete(
                     (value, failure) -> {
                        ApiResult<?> scheduled = this.scheduler
                           .execute(
                              ExecutionContext.ADDON_WORKER,
                              () -> result.complete(
                                 this.closed.get()
                                    ? error(ApiErrorCode.CANCELLED, "AddonStopped")
                                    : (failure == null && value != null ? value : error(ApiErrorCode.UNAVAILABLE, "SteamLobbyOperation"))
                              ),
                              Duration.ofSeconds(2L)
                           );
                        if (!scheduled.isSuccess()) {
                           result.completeOnTimeout(error(ApiErrorCode.QUEUE_FULL, "AddonWorkerQueue"), 1L, TimeUnit.MILLISECONDS);
                        }
                     }
                  );
            } catch (LinkageError | RuntimeException var6) {
               result.complete(error(ApiErrorCode.UNAVAILABLE, "SteamLobbyOperation"));
            }

            return result;
         }
      }
   }

   private static <T> ApiResult<T> error(ApiErrorCode code, String category) {
      return SafeApiErrors.failure(code, "lobby.operation", category);
   }

   @Override
   public void close() {
      if (this.closed.compareAndSet(false, true)) {
         SteamClientApiBridge.releaseAddonLobbies(this.owner);
      }
   }

   @Override
   public boolean isClosed() {
      return this.closed.get();
   }
}
