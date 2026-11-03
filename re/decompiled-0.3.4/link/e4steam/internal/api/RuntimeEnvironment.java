package link.e4steam.internal.api;

import java.util.Objects;
import link.e4steam.api.runtime.LoaderInfo;
import link.e4steam.api.runtime.RuntimeMode;

public final class RuntimeEnvironment {
   private final LoaderInfo loader;
   private final String minecraftVersion;
   private final RuntimeMode mode;
   private final boolean experimental;

   public RuntimeEnvironment(String loaderId, String loaderVersion, String minecraftVersion, RuntimeMode mode, boolean experimental) {
      this.loader = new LoaderInfo(loaderId, loaderVersion);
      this.minecraftVersion = safe(minecraftVersion);
      this.mode = Objects.requireNonNull(mode, "mode");
      this.experimental = experimental;
   }

   public LoaderInfo loader() {
      return this.loader;
   }

   public String minecraftVersion() {
      return this.minecraftVersion;
   }

   public RuntimeMode mode() {
      return this.mode;
   }

   public boolean experimental() {
      return this.experimental;
   }

   private static String safe(String value) {
      if (value != null && !value.trim().isEmpty() && value.length() <= 64) {
         return value.trim();
      } else {
         throw new IllegalArgumentException("Invalid Minecraft version");
      }
   }
}
