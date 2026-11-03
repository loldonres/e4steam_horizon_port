package link.e4steam.internal.api;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import link.e4steam.api.ApiErrorCode;
import link.e4steam.api.ApiResult;
import link.e4steam.api.capability.Capabilities;
import link.e4steam.api.directory.PublicDirectoryService;
import link.e4steam.internal.dedicated.DedicatedServerController;
import link.e4steam.steam.SteamClientApiBridge;

final class CorePublicDirectoryService implements PublicDirectoryService {
   private static final int MAX_OPERATIONS = 32;
   private static final int MAX_CONSUMED_HANDLES = 1024;
   private static final String INTEGRATED_PREFIX = "iw_";
   private static final String DEDICATED_PREFIX = "ds_";
   private final CoreCapabilityService capabilities;
   private final SecureRandom random = new SecureRandom();
   private final Map<PublicDirectoryService.JoinOperationId, PublicDirectoryService.JoinOperation> operations = new LinkedHashMap<>();
   private final Map<PublicDirectoryService.JoinOperationId, Long> integratedTargets = new LinkedHashMap<>();
   private final Set<PublicDirectoryService.JoinOperationId> dedicatedOperations = new LinkedHashSet<>();
   private final Set<String> consumedHandles = new LinkedHashSet<>();

   CorePublicDirectoryService(CoreCapabilityService capabilities) {
      this.capabilities = capabilities;
   }

   @Override
   public ApiResult<PublicDirectoryService.DirectoryAvailability> availability() {
      if (!this.capabilities.has(Capabilities.DIRECTORY_JOIN) && !this.capabilities.has(Capabilities.DIRECTORY_PUBLICATION)) {
         return denied("directory.availability");
      } else {
         String status = SteamClientApiBridge.statusCode();
         if ("RUNNING".equals(status)) {
            return ApiResult.success(PublicDirectoryService.DirectoryAvailability.AVAILABLE);
         } else {
            DedicatedServerController dedicated = DedicatedServerController.current();
            if (dedicated != null && dedicated.accepting()) {
               return ApiResult.success(PublicDirectoryService.DirectoryAvailability.AVAILABLE);
            } else {
               return "FAILED".equals(status)
                  ? ApiResult.success(PublicDirectoryService.DirectoryAvailability.STEAM_UNAVAILABLE)
                  : ApiResult.success(PublicDirectoryService.DirectoryAvailability.NO_ACTIVE_SESSION);
            }
         }
      }
   }

   @Override
   public CompletionStage<ApiResult<PublicDirectoryService.PublicationTarget>> publicationTarget(PublicDirectoryService.PublicationTargetRequest request) {
      if (!this.capabilities.has(Capabilities.DIRECTORY_PUBLICATION)) {
         return completed(denied("directory.publication-target"));
      } else if (request == null) {
         return completed(invalid("directory.publication-target"));
      } else if (request.kind() == PublicDirectoryService.TargetKind.DEDICATED_SERVER) {
         DedicatedServerController controller = DedicatedServerController.current();
         if (controller != null && controller.accepting()) {
            String descriptor = controller.descriptor();
            if (descriptor.isEmpty()) {
               return completed(unavailable("directory.publication-target", "NoActiveDedicatedTarget"));
            } else {
               String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(descriptor.getBytes(StandardCharsets.US_ASCII));
               return completed(
                  ApiResult.success(
                     new PublicDirectoryService.PublicationTarget(
                        PublicDirectoryService.TargetKind.DEDICATED_SERVER,
                        new PublicDirectoryService.OpaqueTargetRef("ds_" + encoded),
                        controller.generation()
                     )
                  )
               );
            }
         } else {
            return completed(unavailable("directory.publication-target", "NoActiveDedicatedServer"));
         }
      } else {
         CompletableFuture<ApiResult<PublicDirectoryService.PublicationTarget>> result = new CompletableFuture<>();
         SteamClientApiBridge.publicHostLobbyTarget()
            .whenComplete(
               (target, failure) -> {
                  if (failure == null && target != null && target.lobbyId() != 0L && target.generation() > 0L) {
                     result.complete(
                        ApiResult.success(
                           new PublicDirectoryService.PublicationTarget(
                              PublicDirectoryService.TargetKind.INTEGRATED_WORLD,
                              new PublicDirectoryService.OpaqueTargetRef("iw_" + Long.toUnsignedString(target.lobbyId())),
                              target.generation()
                           )
                        )
                     );
                  } else {
                     result.complete(unavailable("directory.publication-target", "NoActivePublicWorld"));
                  }
               }
            );
         return result;
      }
   }

   @Override
   public CompletionStage<ApiResult<PublicDirectoryService.AttestationReceipt>> attest(PublicDirectoryService.AttestationChallenge challenge) {
      if (!this.capabilities.has(Capabilities.DIRECTORY_ATTESTATION)) {
         return completed(denied("directory.attest"));
      } else if (challenge == null || challenge.expiresAtEpochMillis() <= System.currentTimeMillis()) {
         return completed(invalid("directory.attest"));
      } else if (DevelopmentRegistryAttestation.enabledFor(challenge)) {
         String ownerRef = this.developmentOwnerRef(challenge.action());
         return ownerRef == null
            ? completed(unavailable("directory.attest", "NoActivePublisherIdentity"))
            : DevelopmentRegistryAttestation.attest(challenge, ownerRef)
               .handle(
                  (receipt, failure) -> {
                     if (failure == null) {
                        return ApiResult.success(receipt);
                     } else {
                        Throwable cause = failure;

                        while (cause instanceof CompletionException && cause.getCause() != null) {
                           cause = cause.getCause();
                        }

                        return SafeApiErrors.failure(
                           cause instanceof SecurityException ? ApiErrorCode.SECURITY_REJECTION : ApiErrorCode.UNAVAILABLE,
                           "directory.attest",
                           cause instanceof SecurityException ? "DevelopmentAttestationRejected" : "DevelopmentRegistryUnavailable"
                        );
                     }
                  }
               );
      } else {
         return completed(SafeApiErrors.failure(ApiErrorCode.UNSUPPORTED, "directory.attest", "RegistryVerifierUnavailable"));
      }
   }

   private String developmentOwnerRef(PublicDirectoryService.AttestationAction action) {
      if (action == PublicDirectoryService.AttestationAction.PUBLISH_INTEGRATED) {
         SteamClientApiBridge.MinecraftIdentity identity = SteamClientApiBridge.localIdentity();
         return identity == null ? null : DevelopmentRegistryAttestation.opaqueOwner("user", identity.minecraftUuid().toString());
      } else if (action == PublicDirectoryService.AttestationAction.PUBLISH_DEDICATED) {
         DedicatedServerController controller = DedicatedServerController.current();
         return controller != null && controller.accepting() && !controller.descriptor().isEmpty()
            ? DevelopmentRegistryAttestation.opaqueOwner("server", controller.descriptor())
            : null;
      } else {
         return null;
      }
   }

   @Override
   public CompletionStage<ApiResult<PublicDirectoryService.JoinOperation>> join(PublicDirectoryService.JoinRequest request) {
      if (!this.capabilities.has(Capabilities.DIRECTORY_JOIN)) {
         return completed(denied("directory.join"));
      } else if (request == null) {
         return completed(invalid("directory.join"));
      } else {
         PublicDirectoryService.JoinOperation started;
         synchronized (this) {
            this.evictFinishedOperations();
            if (this.operations.size() >= 32) {
               return completed(SafeApiErrors.failure(ApiErrorCode.QUEUE_FULL, "directory.join", "OperationLimit"));
            }

            if (!this.consumedHandles.add(request.handle().value())) {
               return completed(SafeApiErrors.failure(ApiErrorCode.SECURITY_REJECTION, "directory.join", "JoinHandleReplay"));
            }

            this.trimConsumedHandles();
            PublicDirectoryService.JoinOperationId id = new PublicDirectoryService.JoinOperationId(this.randomId());
            started = new PublicDirectoryService.JoinOperation(id, PublicDirectoryService.JoinState.RESOLVING, "");
            this.operations.put(id, started);
         }

         String target = request.target().value();
         if (!target.startsWith("iw_")) {
            if (target.startsWith("ds_")) {
               String descriptor;
               try {
                  byte[] decoded = Base64.getUrlDecoder().decode(target.substring("ds_".length()));
                  descriptor = new String(decoded, StandardCharsets.US_ASCII);
               } catch (IllegalArgumentException var11) {
                  return completed(this.fail(started.id(), PublicDirectoryService.JoinState.STALE, "invalid-target"));
               }

               if (!SteamClientApiBridge.acceptDirectSteamInvite(descriptor, "Public e4steam server")) {
                  return completed(this.fail(started.id(), PublicDirectoryService.JoinState.STALE, "invalid-target"));
               } else {
                  synchronized (this) {
                     this.dedicatedOperations.add(started.id());
                  }

                  this.update(started.id(), PublicDirectoryService.JoinState.CONNECTING_MINECRAFT, "");
                  return completed(this.success(started.id(), PublicDirectoryService.JoinState.CONNECTING_MINECRAFT, ""));
               }
            } else {
               return completed(this.fail(started.id(), PublicDirectoryService.JoinState.STALE, "unknown-target"));
            }
         } else {
            long lobbyId;
            try {
               lobbyId = Long.parseUnsignedLong(target.substring("iw_".length()));
               if (lobbyId == 0L) {
                  throw new NumberFormatException("zero lobby");
               }
            } catch (NumberFormatException var12) {
               return completed(this.fail(started.id(), PublicDirectoryService.JoinState.STALE, "invalid-target"));
            }

            synchronized (this) {
               this.integratedTargets.put(started.id(), lobbyId);
            }

            this.update(started.id(), PublicDirectoryService.JoinState.JOINING_STEAM_TARGET, "");
            CompletableFuture<ApiResult<PublicDirectoryService.JoinOperation>> result = new CompletableFuture<>();
            SteamClientApiBridge.joinPublicLobby(lobbyId).whenComplete((accepted, failurex) -> {
               if (failurex == null && Boolean.TRUE.equals(accepted)) {
                  result.complete(this.refresh(started.id()));
               } else {
                  result.complete(this.fail(started.id(), PublicDirectoryService.JoinState.FAILED, "steam-join-rejected"));
               }
            });
            return result;
         }
      }
   }

   @Override
   public CompletionStage<ApiResult<PublicDirectoryService.JoinOperation>> cancel(PublicDirectoryService.JoinOperationId operationId) {
      if (!this.capabilities.has(Capabilities.DIRECTORY_JOIN)) {
         return completed(denied("directory.cancel"));
      } else if (operationId == null) {
         return completed(invalid("directory.cancel"));
      } else {
         PublicDirectoryService.JoinOperation existing;
         synchronized (this) {
            existing = this.operations.get(operationId);
         }

         if (existing == null) {
            return completed(SafeApiErrors.failure(ApiErrorCode.STALE_HANDLE, "directory.cancel", "UnknownOperation"));
         } else if (terminal(existing.state())) {
            return completed(ApiResult.success(existing));
         } else {
            SteamClientApiBridge.cancelGuestJoin();
            return completed(this.success(operationId, PublicDirectoryService.JoinState.CANCELLED, ""));
         }
      }
   }

   @Override
   public ApiResult<PublicDirectoryService.JoinOperation> joinSnapshot(PublicDirectoryService.JoinOperationId operationId) {
      if (!this.capabilities.has(Capabilities.DIRECTORY_JOIN)) {
         return denied("directory.join-snapshot");
      } else if (operationId == null) {
         return invalid("directory.join-snapshot");
      } else {
         PublicDirectoryService.JoinOperation operation;
         synchronized (this) {
            operation = this.operations.get(operationId);
         }

         return operation == null ? SafeApiErrors.failure(ApiErrorCode.STALE_HANDLE, "directory.join-snapshot", "UnknownOperation") : this.refresh(operationId);
      }
   }

   private synchronized ApiResult<PublicDirectoryService.JoinOperation> success(
      PublicDirectoryService.JoinOperationId id, PublicDirectoryService.JoinState state, String detail
   ) {
      PublicDirectoryService.JoinOperation previous = this.operations.get(id);
      if (previous != null && terminal(previous.state())) {
         return ApiResult.success(previous);
      } else {
         PublicDirectoryService.JoinOperation operation = new PublicDirectoryService.JoinOperation(id, state, detail);
         this.operations.put(id, operation);
         return ApiResult.success(operation);
      }
   }

   private ApiResult<PublicDirectoryService.JoinOperation> fail(
      PublicDirectoryService.JoinOperationId id, PublicDirectoryService.JoinState state, String detail
   ) {
      return this.success(id, state, detail);
   }

   private synchronized void update(PublicDirectoryService.JoinOperationId id, PublicDirectoryService.JoinState state, String detail) {
      this.success(id, state, detail);
   }

   private ApiResult<PublicDirectoryService.JoinOperation> refresh(PublicDirectoryService.JoinOperationId id) {
      PublicDirectoryService.JoinOperation current;
      Long lobbyId;
      boolean dedicated;
      synchronized (this) {
         current = this.operations.get(id);
         lobbyId = this.integratedTargets.get(id);
         dedicated = this.dedicatedOperations.contains(id);
      }

      if (current == null) {
         return SafeApiErrors.failure(ApiErrorCode.STALE_HANDLE, "directory.join-snapshot", "UnknownOperation");
      } else if (terminal(current.state())) {
         return ApiResult.success(current);
      } else if (lobbyId == null) {
         if (dedicated) {
            SteamClientApiBridge.SessionView session = SteamClientApiBridge.sessionView();
            if (session.active() && "DEDICATED_SERVER_CLIENT".equals(session.roleCode()) && "ACTIVE".equals(session.stateCode())) {
               return this.success(id, PublicDirectoryService.JoinState.ACTIVE, "");
            }

            if ("FAILED".equals(SteamClientApiBridge.statusCode())) {
               return this.fail(id, PublicDirectoryService.JoinState.FAILED, "steam-unavailable");
            }
         }

         return ApiResult.success(current);
      } else {
         SteamClientApiBridge.PublicJoinSnapshot snapshot = SteamClientApiBridge.publicJoinSnapshot(lobbyId);
         PublicDirectoryService.JoinState state = joinState(snapshot.stateCode());
         return state != null && state != PublicDirectoryService.JoinState.IDLE ? this.success(id, state, snapshot.detailCode()) : ApiResult.success(current);
      }
   }

   private synchronized void evictFinishedOperations() {
      while (this.operations.size() >= 32) {
         PublicDirectoryService.JoinOperationId finished = null;

         for (Entry<PublicDirectoryService.JoinOperationId, PublicDirectoryService.JoinOperation> entry : this.operations.entrySet()) {
            if (terminal(entry.getValue().state())) {
               finished = entry.getKey();
               break;
            }
         }

         if (finished == null) {
            return;
         }

         this.operations.remove(finished);
         this.integratedTargets.remove(finished);
         this.dedicatedOperations.remove(finished);
      }
   }

   private static PublicDirectoryService.JoinState joinState(String stateCode) {
      if (stateCode != null && !stateCode.isEmpty()) {
         try {
            return PublicDirectoryService.JoinState.valueOf(stateCode);
         } catch (IllegalArgumentException var2) {
            return PublicDirectoryService.JoinState.FAILED;
         }
      } else {
         return null;
      }
   }

   private static boolean terminal(PublicDirectoryService.JoinState state) {
      return state == PublicDirectoryService.JoinState.ACTIVE
         || state == PublicDirectoryService.JoinState.CANCELLED
         || state == PublicDirectoryService.JoinState.STALE
         || state == PublicDirectoryService.JoinState.FULL
         || state == PublicDirectoryService.JoinState.INCOMPATIBLE
         || state == PublicDirectoryService.JoinState.FAILED;
   }

   private void trimConsumedHandles() {
      while (this.consumedHandles.size() > 1024) {
         String oldest = this.consumedHandles.iterator().next();
         this.consumedHandles.remove(oldest);
      }
   }

   private String randomId() {
      byte[] value = new byte[16];
      this.random.nextBytes(value);
      return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
   }

   private static <T> ApiResult<T> denied(String operation) {
      return SafeApiErrors.failure(ApiErrorCode.CAPABILITY_DENIED, operation, "PolicyDenied");
   }

   private static <T> ApiResult<T> invalid(String operation) {
      return SafeApiErrors.failure(ApiErrorCode.INVALID_ARGUMENT, operation, "Validation");
   }

   private static <T> ApiResult<T> unavailable(String operation, String category) {
      return SafeApiErrors.failure(ApiErrorCode.UNAVAILABLE, operation, category);
   }

   private static <T> CompletionStage<T> completed(T value) {
      return CompletableFuture.completedFuture(value);
   }
}
