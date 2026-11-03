package link.e4steam.internal.api;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import link.e4steam.api.ApiErrorCode;
import link.e4steam.api.ApiResult;
import link.e4steam.api.Registration;
import link.e4steam.api.ResourceScope;
import link.e4steam.api.addon.AddonId;
import link.e4steam.api.capability.Capabilities;
import link.e4steam.api.diagnostics.DiagnosticsService;
import link.e4steam.api.modpack.ModpackService;
import link.e4steam.api.scheduler.ExecutionContext;
import link.e4steam.api.scheduler.TaskHandle;
import link.e4steam.api.skin.SkinService;

final class CoreProviderServices {
   private CoreProviderServices() {
   }

   static ModpackService modpacks(
      AddonId owner, CoreCapabilityService capabilities, CoreContributionRegistry registry, ResourceScope resources, CoreSchedulerService scheduler
   ) {
      return new ModpackService() {
         private volatile ModpackService.ModpackProvider local;

         @Override
         public ApiResult<Registration> registerProvider(ModpackService.ModpackProvider provider) {
            if (!capabilities.has(Capabilities.MODPACK_INSPECT)) {
               return CoreProviderServices.denied("modpack.register");
            } else if (provider == null) {
               return CoreProviderServices.invalid("modpack.register");
            } else {
               ApiResult<Registration> result = registry.register("modpack", provider.id(), owner, provider, resources, false);
               if (!result.isSuccess()) {
                  return result;
               } else {
                  this.local = provider;
                  CoreRegistration scoped = new CoreRegistration(() -> {
                     result.value().get().close();
                     if (this.local == provider) {
                        this.local = null;
                     }
                  });
                  resources.own(scoped);
                  return ApiResult.success(scoped);
               }
            }
         }

         @Override
         public CompletionStage<ApiResult<ModpackService.CompatibilityReport>> inspect(
            ModpackService.ModpackManifest manifest, ModpackService.Environment environment
         ) {
            if (!capabilities.has(Capabilities.MODPACK_INSPECT)) {
               return CoreProviderServices.completed(CoreProviderServices.denied("modpack.inspect"));
            } else {
               ModpackService.ModpackProvider provider = this.local;
               return provider == null
                  ? CoreProviderServices.completed(CoreProviderServices.unavailable("modpack.inspect"))
                  : CoreProviderServices.invoke(() -> provider.inspect(manifest, environment), "modpack.inspect", scheduler);
            }
         }

         @Override
         public CompletionStage<ApiResult<ModpackService.InstallPlan>> plan(ModpackService.ModpackManifest manifest, ModpackService.CompatibilityReport report) {
            if (!capabilities.has(Capabilities.MODPACK_STAGE)) {
               return CoreProviderServices.completed(CoreProviderServices.denied("modpack.plan"));
            } else {
               ModpackService.ModpackProvider provider = this.local;
               return provider == null
                  ? CoreProviderServices.completed(CoreProviderServices.unavailable("modpack.plan"))
                  : CoreProviderServices.invoke(() -> provider.plan(manifest, report), "modpack.plan", scheduler);
            }
         }
      };
   }

   static SkinService skins(
      AddonId owner, CoreCapabilityService capabilities, CoreContributionRegistry registry, ResourceScope resources, CoreSchedulerService scheduler
   ) {
      return new SkinService() {
         private volatile SkinService.SkinProvider local;

         @Override
         public ApiResult<Registration> registerProvider(SkinService.SkinProvider provider) {
            if (!capabilities.has(Capabilities.SKINS_PROVIDE)) {
               return CoreProviderServices.denied("skin.register");
            } else if (provider == null) {
               return CoreProviderServices.invalid("skin.register");
            } else {
               ApiResult<Registration> result = registry.register("skin", provider.id(), owner, provider, resources, false);
               if (!result.isSuccess()) {
                  return result;
               } else {
                  this.local = provider;
                  CoreRegistration scoped = new CoreRegistration(() -> {
                     result.value().get().close();
                     if (this.local == provider) {
                        this.local = null;
                     }
                  });
                  resources.own(scoped);
                  return ApiResult.success(scoped);
               }
            }
         }

         @Override
         public CompletionStage<ApiResult<SkinService.SkinResult>> resolve(SkinService.SkinRequest request) {
            if (!capabilities.has(Capabilities.SKINS_PROVIDE)) {
               return CoreProviderServices.completed(CoreProviderServices.denied("skin.resolve"));
            } else {
               SkinService.SkinProvider provider = this.local;
               return provider == null
                  ? CoreProviderServices.completed(CoreProviderServices.unavailable("skin.resolve"))
                  : CoreProviderServices.invoke(() -> provider.resolve(request), "skin.resolve", scheduler);
            }
         }
      };
   }

   static DiagnosticsService diagnostics(
      AddonId owner, CoreCapabilityService capabilities, CoreContributionRegistry registry, ResourceScope resources, CoreSchedulerService scheduler
   ) {
      return new DiagnosticsService() {
         private final List<DiagnosticsService.DiagnosticsContributor> local = new CopyOnWriteArrayList<>();

         @Override
         public ApiResult<DiagnosticsService.HealthSnapshot> health() {
            return ApiResult.success(
               new DiagnosticsService.HealthSnapshot(
                  Collections.singletonList(new DiagnosticsService.ComponentHealth("e4steam:addon-api", DiagnosticsService.Health.HEALTHY, "ready"))
               )
            );
         }

         @Override
         public ApiResult<Registration> registerContributor(DiagnosticsService.DiagnosticsContributor contributor) {
            if (!capabilities.has(Capabilities.DIAGNOSTICS_CONTRIBUTE)) {
               return CoreProviderServices.denied("diagnostics.register");
            } else if (contributor == null) {
               return CoreProviderServices.invalid("diagnostics.register");
            } else if (this.local.size() >= 128) {
               return SafeApiErrors.failure(ApiErrorCode.QUEUE_FULL, "diagnostics.register", "ContributorLimit");
            } else {
               ApiResult<Registration> result = registry.register("diagnostics", contributor.id(), owner, contributor, resources, false);
               if (!result.isSuccess()) {
                  return result;
               } else {
                  this.local.add(contributor);
                  CoreRegistration scoped = new CoreRegistration(() -> {
                     result.value().get().close();
                     this.local.remove(contributor);
                  });
                  resources.own(scoped);
                  return ApiResult.success(scoped);
               }
            }
         }

         @Override
         public CompletionStage<ApiResult<DiagnosticsService.DoctorPreview>> doctorPreview(DiagnosticsService.PrivacyOptions options) {
            if (options == null) {
               return CoreProviderServices.completed(CoreProviderServices.invalid("diagnostics.preview"));
            } else {
               ArrayList<CompletableFuture<ApiResult<DiagnosticsService.DiagnosticsSection>>> pending = new ArrayList<>();

               for (DiagnosticsService.DiagnosticsContributor contributor : this.local) {
                  CompletionStage<ApiResult<DiagnosticsService.DiagnosticsSection>> stage = CoreProviderServices.invoke(
                     contributor::contribute, "diagnostics.contribute", scheduler
                  );
                  pending.add(stage.toCompletableFuture());
               }

               CompletableFuture<ApiResult<DiagnosticsService.DoctorPreview>> preview = new CompletableFuture<>();
               CompletableFuture<?>[] futures = pending.toArray(new CompletableFuture[0]);
               CompletableFuture.allOf(futures)
                  .whenComplete(
                     (ignored, failure) -> {
                        ArrayList<DiagnosticsService.DiagnosticsSection> sections = new ArrayList<>();
                        int bytes = 0;

                        for (CompletableFuture<ApiResult<DiagnosticsService.DiagnosticsSection>> contribution : pending) {
                           ApiResult<DiagnosticsService.DiagnosticsSection> result = contribution.getNow(null);
                           if (result != null && result.isSuccess()) {
                              DiagnosticsService.DiagnosticsSection section = result.value().get();
                              int sectionBytes = CoreProviderServices.estimatedBytes(section);
                              if (sectionBytes > 2097152 - bytes) {
                                 break;
                              }

                              sections.add(section);
                              bytes += sectionBytes;
                           }
                        }

                        preview.complete(
                           ApiResult.success(
                              new DiagnosticsService.DoctorPreview(sections, Collections.singletonList("secrets-and-personal-data-excluded"), bytes)
                           )
                        );
                     }
                  );
               return preview;
            }
         }
      };
   }

   private static <T> CompletionStage<ApiResult<T>> invoke(Supplier<CompletionStage<ApiResult<T>>> callback, String operation, CoreSchedulerService scheduler) {
      CompletableFuture<ApiResult<T>> result = new CompletableFuture<>();
      ApiResult<TaskHandle> invocation = scheduler.execute(
         ExecutionContext.ADDON_WORKER,
         () -> {
            try {
               CompletionStage<ApiResult<T>> stage = callback.get();
               if (stage == null) {
                  result.complete(SafeApiErrors.failure(ApiErrorCode.ADDON_FAILURE, operation, "NullStage"));
                  return;
               }

               stage.whenComplete(
                  (value, failurex) -> result.complete(
                     (ApiResult<T>)(failurex == null && value != null
                        ? value
                        : SafeApiErrors.failure(ApiErrorCode.ADDON_FAILURE, operation, failurex == null ? "NullResult" : safeCategory(failurex)))
                  )
               );
            } catch (ThreadDeath | VirtualMachineError var4x) {
               throw var4x;
            } catch (Throwable var5x) {
               result.complete(addonFailure(operation, var5x));
            }
         },
         Duration.ofSeconds(1L)
      );
      if (!invocation.isSuccess()) {
         return completed(SafeApiErrors.failure(ApiErrorCode.QUEUE_FULL, operation, "Scheduler"));
      } else {
         AtomicReference<TaskHandle> timeout = new AtomicReference<>();
         ApiResult<TaskHandle> timeoutResult = scheduler.schedule(
            ExecutionContext.E4STEAM_CONTROL,
            () -> result.complete(SafeApiErrors.failure(ApiErrorCode.TIMEOUT, operation, "Timeout")),
            Duration.ofSeconds(2L),
            Duration.ofSeconds(1L)
         );
         if (timeoutResult.isSuccess()) {
            timeout.set(timeoutResult.value().get());
         } else {
            result.complete(SafeApiErrors.failure(ApiErrorCode.QUEUE_FULL, operation, "Scheduler"));
         }

         result.whenComplete((value, failure) -> {
            TaskHandle task = timeout.get();
            if (task != null) {
               task.close();
            }
         });
         return result;
      }
   }

   private static int estimatedBytes(DiagnosticsService.DiagnosticsSection section) {
      int total = section.id().getBytes(StandardCharsets.UTF_8).length;

      for (Entry<String, String> field : section.fields().entrySet()) {
         total += field.getKey().getBytes(StandardCharsets.UTF_8).length;
         total += field.getValue().getBytes(StandardCharsets.UTF_8).length;
      }

      return total;
   }

   private static <T> ApiResult<T> denied(String operation) {
      return SafeApiErrors.failure(ApiErrorCode.CAPABILITY_DENIED, operation, "PolicyDenied");
   }

   private static <T> ApiResult<T> invalid(String operation) {
      return SafeApiErrors.failure(ApiErrorCode.INVALID_ARGUMENT, operation, "Validation");
   }

   private static <T> ApiResult<T> unavailable(String operation) {
      return SafeApiErrors.failure(ApiErrorCode.UNAVAILABLE, operation, "ProviderUnavailable");
   }

   private static <T> ApiResult<T> addonFailure(String operation, Throwable failure) {
      return SafeApiErrors.failure(ApiErrorCode.ADDON_FAILURE, operation, safeCategory(failure));
   }

   private static String safeCategory(Throwable failure) {
      String value = failure == null ? "AddonFailure" : failure.getClass().getSimpleName();
      return value.matches("[A-Za-z0-9_$.-]{1,64}") ? value : "AddonFailure";
   }

   private static <T> CompletionStage<T> completed(T value) {
      return CompletableFuture.completedFuture(value);
   }
}
