package link.e4steam.internal.api;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import link.e4steam.api.ApiErrorCode;
import link.e4steam.api.ApiResult;
import link.e4steam.api.ApiValidation;
import link.e4steam.api.Registration;
import link.e4steam.api.ResourceScope;
import link.e4steam.api.access.AccessService;
import link.e4steam.api.addon.AddonId;
import link.e4steam.api.capability.Capabilities;
import link.e4steam.api.scheduler.ExecutionContext;
import link.e4steam.api.scheduler.TaskHandle;

final class CoreAccessService implements AccessService {
   private static final String FAMILY = "access";
   private final AddonId owner;
   private final CoreCapabilityService capabilities;
   private final CoreContributionRegistry registry;
   private final ResourceScope resources;
   private final CoreSchedulerService scheduler;

   CoreAccessService(
      AddonId owner, CoreCapabilityService capabilities, CoreContributionRegistry registry, ResourceScope resources, CoreSchedulerService scheduler
   ) {
      this.owner = owner;
      this.capabilities = capabilities;
      this.registry = registry;
      this.resources = resources;
      this.scheduler = scheduler;
   }

   @Override
   public ApiResult<Registration> register(AccessService.AccessModeProvider provider) {
      if (!this.capabilities.has(Capabilities.ACCESS_MODE_REGISTER)) {
         return this.denied("access.register");
      } else if (provider != null && provider.id() != null && provider.policy() != null) {
         try {
            String key = ApiValidation.text(provider.displayNameKey(), "displayNameKey", 128);
            if (!key.matches("[a-z0-9_.:-]{3,128}")) {
               return SafeApiErrors.failure(ApiErrorCode.INVALID_ARGUMENT, "access.register", "DisplayNameKey");
            }

            if (provider instanceof AccessService.ConfirmableAccessModeProvider confirmable) {
               String title = ApiValidation.text(confirmable.confirmationTitleKey(), "confirmationTitleKey", 128);
               String message = ApiValidation.text(confirmable.confirmationMessageKey(), "confirmationMessageKey", 128);
               if (!title.matches("[a-z0-9_.:-]{3,128}") || !message.matches("[a-z0-9_.:-]{3,128}")) {
                  return SafeApiErrors.failure(ApiErrorCode.INVALID_ARGUMENT, "access.register", "ConfirmationKey");
               }
            }
         } catch (RuntimeException var6) {
            return SafeApiErrors.failure(ApiErrorCode.INVALID_ARGUMENT, "access.register", "DisplayNameKey");
         }

         return this.registry.register("access", provider.id().value(), this.owner, provider, this.resources, true);
      } else {
         return SafeApiErrors.failure(ApiErrorCode.INVALID_ARGUMENT, "access.register", "Validation");
      }
   }

   @Override
   public CompletionStage<ApiResult<AccessService.AdmissionDecision>> evaluate(AccessService.AccessModeId mode, AccessService.AdmissionContext context) {
      if (!this.capabilities.has(Capabilities.ACCESS_POLICY_EVALUATE)) {
         return completed(this.denied("access.evaluate"));
      } else {
         return mode != null && context != null && context.coreAuthenticated() && this.owner.equals(context.modeOwner())
            ? evaluateRegistered(this.registry, this.scheduler, mode, context)
            : completed(SafeApiErrors.failure(ApiErrorCode.SECURITY_REJECTION, "access.evaluate", "MandatoryGate"));
      }
   }

   static CompletionStage<ApiResult<AccessService.AdmissionDecision>> evaluateRegistered(
      CoreContributionRegistry registry, CoreSchedulerService scheduler, AccessService.AccessModeId mode, AccessService.AdmissionContext context
   ) {
      if (registry != null && scheduler != null && mode != null && context != null && context.coreAuthenticated()) {
         CoreContributionRegistry.OwnedContribution contribution = registry.findOwned("access", mode.value());
         if (contribution != null && contribution.owner().equals(context.modeOwner()) && contribution.value() instanceof AccessService.AccessModeProvider) {
            AccessService.AccessModeProvider provider = (AccessService.AccessModeProvider)contribution.value();
            CompletableFuture<ApiResult<AccessService.AdmissionDecision>> result = new CompletableFuture<>();
            ApiResult<TaskHandle> queued = scheduler.execute(ExecutionContext.ADDON_WORKER, () -> {
               try {
                  CompletionStage<AccessService.AdmissionDecision> stage = provider.policy().evaluate(context);
                  if (stage == null) {
                     result.complete(ApiResult.success(AccessService.AdmissionDecision.deny("addon-policy-failed")));
                  } else {
                     stage.whenComplete((decision, failurex) -> {
                        if (failurex == null && decision != null) {
                           result.complete(ApiResult.success(decision));
                        } else {
                           result.complete(ApiResult.success(AccessService.AdmissionDecision.deny("addon-policy-failed")));
                        }
                     });
                  }
               } catch (ThreadDeath | VirtualMachineError var4x) {
                  throw var4x;
               } catch (Throwable var5x) {
                  result.complete(ApiResult.success(AccessService.AdmissionDecision.deny("addon-policy-failed")));
               }
            }, Duration.ofSeconds(2L));
            if (!queued.isSuccess()) {
               return completed(SafeApiErrors.failure(ApiErrorCode.QUEUE_FULL, "access.evaluate", "Scheduler"));
            } else {
               AtomicReference<TaskHandle> timeout = new AtomicReference<>();
               ApiResult<TaskHandle> timeoutResult = scheduler.schedule(
                  ExecutionContext.E4STEAM_CONTROL,
                  () -> result.complete(ApiResult.success(AccessService.AdmissionDecision.deny("addon-policy-timeout"))),
                  Duration.ofSeconds(2L),
                  Duration.ofSeconds(1L)
               );
               if (!timeoutResult.isSuccess()) {
                  return completed(SafeApiErrors.failure(ApiErrorCode.QUEUE_FULL, "access.evaluate", "Scheduler"));
               } else {
                  timeout.set(timeoutResult.value().get());
                  result.whenComplete((valueResult, failure) -> {
                     TaskHandle task = timeout.get();
                     if (task != null) {
                        task.close();
                     }
                  });
                  return result;
               }
            }
         } else {
            return completed(SafeApiErrors.failure(ApiErrorCode.UNAVAILABLE, "access.evaluate", "ModeUnavailable"));
         }
      } else {
         return completed(SafeApiErrors.failure(ApiErrorCode.SECURITY_REJECTION, "access.evaluate", "MandatoryGate"));
      }
   }

   @Override
   public boolean registrationsFrozen() {
      return this.registry.isFrozen();
   }

   private <T> ApiResult<T> denied(String operation) {
      return SafeApiErrors.failure(ApiErrorCode.CAPABILITY_DENIED, operation, "PolicyDenied");
   }

   private static <T> CompletionStage<T> completed(T value) {
      return CompletableFuture.completedFuture(value);
   }
}
