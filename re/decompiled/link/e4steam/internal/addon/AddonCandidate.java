package link.e4steam.internal.addon;

import java.util.Objects;
import link.e4steam.api.addon.AddonDescriptor;
import link.e4steam.api.addon.E4steamAddon;
import link.e4steam.api.addon.E4steamAddonEntrypoint;

public final class AddonCandidate {
   private final AddonDescriptor descriptor;
   private final E4steamAddon addon;
   private final String sourceModId;
   private final boolean enabled;

   public AddonCandidate(AddonDescriptor descriptor, E4steamAddon addon, String sourceModId, boolean enabled) {
      this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
      this.addon = Objects.requireNonNull(addon, "addon");
      this.sourceModId = safeSource(sourceModId);
      this.enabled = enabled;
   }

   public static AddonCandidate fromEntrypoint(E4steamAddonEntrypoint entrypoint, String sourceModId) {
      Objects.requireNonNull(entrypoint, "entrypoint");
      return new AddonCandidate(entrypoint.descriptor(), entrypoint, sourceModId, true);
   }

   public AddonDescriptor descriptor() {
      return this.descriptor;
   }

   public E4steamAddon addon() {
      return this.addon;
   }

   public String sourceModId() {
      return this.sourceModId;
   }

   public boolean enabled() {
      return this.enabled;
   }

   private static String safeSource(String value) {
      if (value == null) {
         throw new NullPointerException("sourceModId");
      } else {
         String checked = value.trim();
         if (!checked.matches("[a-z][a-z0-9_.-]{0,95}")) {
            throw new IllegalArgumentException("Invalid source mod id");
         } else {
            return checked;
         }
      }
   }
}
