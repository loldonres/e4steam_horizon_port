package link.e4steam.api.addon;

import java.util.Objects;
import link.e4steam.api.ApiError;

public final class AddonFailure {
   private final AddonId addonId;
   private final ApiError error;

   public AddonFailure(AddonId addonId, ApiError error) {
      this.addonId = Objects.requireNonNull(addonId, "addonId");
      this.error = Objects.requireNonNull(error, "error");
   }

   public AddonId addonId() {
      return this.addonId;
   }

   public ApiError error() {
      return this.error;
   }

   @Override
   public String toString() {
      return "AddonFailure{addonId=" + this.addonId + ", error=" + this.error + '}';
   }
}
