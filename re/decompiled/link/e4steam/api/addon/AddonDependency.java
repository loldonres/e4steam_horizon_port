package link.e4steam.api.addon;

import java.util.Objects;
import link.e4steam.api.ApiVersionRange;

public final class AddonDependency {
   private final AddonId addonId;
   private final ApiVersionRange supportedVersions;
   private final boolean required;

   public AddonDependency(AddonId addonId, ApiVersionRange supportedVersions, boolean required) {
      this.addonId = Objects.requireNonNull(addonId, "addonId");
      this.supportedVersions = Objects.requireNonNull(supportedVersions, "supportedVersions");
      this.required = required;
   }

   public AddonId addonId() {
      return this.addonId;
   }

   public ApiVersionRange supportedVersions() {
      return this.supportedVersions;
   }

   public boolean required() {
      return this.required;
   }
}
