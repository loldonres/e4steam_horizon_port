package link.e4steam.internal.kaleido.api;

import java.nio.file.Path;
import java.nio.file.Paths;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.Config;
import link.e4steam.internal.kaleido.lib.quiltconfig.impl.ConfigImpl;
import link.e4steam.internal.kaleido.lib.quiltconfig.implementor_api.ConfigEnvironment;

public abstract class WrappedConfig extends link.e4steam.internal.kaleido.lib.quiltconfig.api.WrappedConfig {
   public static <T extends WrappedConfig> T create(ConfigEnvironment environment, String familyId, String id, Class<T> configCreatorClass) {
      return ConfigImpl.create(environment, familyId, id, Paths.get(""), b -> {}, configCreatorClass, b -> {});
   }

   public static <T extends WrappedConfig> T createToml(Path configPath, String familyId, String id, Class<T> configCreatorClass) {
      return create(KaleidoConfig.tomlEnvironment(configPath), familyId, id, configCreatorClass);
   }

   public interface Section extends Config.Section {
   }
}
