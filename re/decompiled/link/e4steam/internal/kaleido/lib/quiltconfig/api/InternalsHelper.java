package link.e4steam.internal.kaleido.lib.quiltconfig.api;

import org.jetbrains.annotations.ApiStatus.Internal;

@Internal
public final class InternalsHelper {
   private InternalsHelper() {
   }

   public static <T extends ReflectiveConfig> void setWrappedConfig(T wrapped, Config config) {
      wrapped.setWrappedConfig(config);
   }
}
