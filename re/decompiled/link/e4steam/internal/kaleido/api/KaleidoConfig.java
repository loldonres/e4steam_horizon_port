package link.e4steam.internal.kaleido.api;

import java.nio.file.Path;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.serializers.TomlSerializer;
import link.e4steam.internal.kaleido.lib.quiltconfig.implementor_api.ConfigEnvironment;

public class KaleidoConfig {
   public static ConfigEnvironment tomlEnvironment(Path configPath) {
      return new ConfigEnvironment(configPath, "toml", TomlSerializer.INSTANCE);
   }
}
