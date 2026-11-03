package link.e4steam.internal.api;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import link.e4steam.api.ApiErrorCode;
import link.e4steam.api.ApiResult;
import link.e4steam.api.Registration;
import link.e4steam.api.ResourceScope;
import link.e4steam.api.addon.AddonId;
import link.e4steam.api.capability.Capabilities;
import link.e4steam.api.runtime.RuntimeMode;
import link.e4steam.api.ui.UiService;

final class CoreUiService implements UiService {
   private final AddonId owner;
   private final CoreCapabilityService capabilities;
   private final CoreContributionRegistry registry;
   private final ResourceScope resources;
   private final UiService.Availability availability;

   CoreUiService(AddonId owner, CoreCapabilityService capabilities, CoreContributionRegistry registry, ResourceScope resources, RuntimeMode mode) {
      this.owner = owner;
      this.capabilities = capabilities;
      this.registry = registry;
      this.resources = resources;
      this.availability = mode == RuntimeMode.DEDICATED_SERVER ? UiService.Availability.HEADLESS : UiService.Availability.AVAILABLE;
   }

   @Override
   public UiService.Availability availability() {
      return this.availability;
   }

   @Override
   public ApiResult<Registration> registerAction(UiService.ActionDescriptor descriptor, UiService.ActionHandler handler) {
      if (!this.allowed()) {
         return this.denied("ui.action.register");
      } else if (this.availability != UiService.Availability.AVAILABLE) {
         return this.unavailable("ui.action.register");
      } else {
         return descriptor != null && handler != null
            ? this.registry.register("ui-action", descriptor.id().value(), this.owner, new Object[]{descriptor, handler}, this.resources, false)
            : this.invalid("ui.action.register");
      }
   }

   @Override
   public ApiResult<Registration> registerSettings(UiService.SettingsCategory category) {
      if (!this.allowed()) {
         return this.denied("ui.settings.register");
      } else if (this.availability != UiService.Availability.AVAILABLE) {
         return this.unavailable("ui.settings.register");
      } else {
         return category == null
            ? this.invalid("ui.settings.register")
            : this.registry.register("ui-settings", category.id().value(), this.owner, category, this.resources, false);
      }
   }

   @Override
   public ApiResult<Registration> registerStatus(UiService.StatusBadge badge) {
      if (!this.allowed()) {
         return this.denied("ui.status.register");
      } else if (this.availability != UiService.Availability.AVAILABLE) {
         return this.unavailable("ui.status.register");
      } else {
         return badge == null
            ? this.invalid("ui.status.register")
            : this.registry.register("ui-status", badge.id().value(), this.owner, badge, this.resources, false);
      }
   }

   @Override
   public CompletionStage<ApiResult<Boolean>> toast(UiService.Message message) {
      return completed(
         this.availability == UiService.Availability.AVAILABLE && this.allowed()
            ? SafeApiErrors.failure(ApiErrorCode.UNAVAILABLE, "ui.toast", "AdapterNotRendered")
            : this.unavailable("ui.toast")
      );
   }

   @Override
   public CompletionStage<ApiResult<Boolean>> confirm(UiService.Confirmation confirmation) {
      return completed(
         this.availability == UiService.Availability.AVAILABLE && this.allowed()
            ? SafeApiErrors.failure(ApiErrorCode.UNAVAILABLE, "ui.confirm", "AdapterNotRendered")
            : this.unavailable("ui.confirm")
      );
   }

   @Override
   public CompletionStage<ApiResult<UiService.FormResult>> form(UiService.FormDescriptor form) {
      return completed(
         this.availability == UiService.Availability.AVAILABLE && this.allowed()
            ? SafeApiErrors.failure(ApiErrorCode.UNAVAILABLE, "ui.form", "AdapterNotRendered")
            : this.unavailable("ui.form")
      );
   }

   private boolean allowed() {
      return this.capabilities.has(Capabilities.UI_CONTRIBUTE);
   }

   private <T> ApiResult<T> denied(String operation) {
      return SafeApiErrors.failure(ApiErrorCode.CAPABILITY_DENIED, operation, "PolicyDenied");
   }

   private <T> ApiResult<T> unavailable(String operation) {
      return SafeApiErrors.failure(ApiErrorCode.UNAVAILABLE, operation, this.availability.name());
   }

   private <T> ApiResult<T> invalid(String operation) {
      return SafeApiErrors.failure(ApiErrorCode.INVALID_ARGUMENT, operation, "Validation");
   }

   private static <T> CompletionStage<T> completed(T value) {
      return CompletableFuture.completedFuture(value);
   }
}
