package link.e4steam.internal.kaleido.api;

import java.nio.file.Path;
import java.nio.file.Paths;
import link.e4steam.internal.kaleido.lib.quiltconfig.impl.ConfigImpl;
import link.e4steam.internal.kaleido.lib.quiltconfig.implementor_api.ConfigEnvironment;

public abstract class ReflectiveConfig extends link.e4steam.internal.kaleido.lib.quiltconfig.api.ReflectiveConfig {
   public static <T extends ReflectiveConfig> T create(ConfigEnvironment environment, String familyId, String id, Class<T> configCreatorClass) {
      return ConfigImpl.createReflective(environment, familyId, id, Paths.get(""), b -> {}, configCreatorClass, b -> {});
   }

   public static <T extends ReflectiveConfig> T createToml(Path configPath, String familyId, String id, Class<T> configCreatorClass) {
      return create(KaleidoConfig.tomlEnvironment(configPath), familyId, id, configCreatorClass);
   }
}
