package link.e4steam.internal.kaleido.lib.nightconfig.core;

import java.util.Map;
import java.util.function.Supplier;

final class SimpleConfig extends AbstractConfig {
   private final ConfigFormat<?> configFormat;

   @Deprecated
   SimpleConfig(ConfigFormat<?> configFormat, boolean concurrent) {
      super(concurrent);
      this.configFormat = configFormat;
   }

   SimpleConfig(Map<String, Object> map, ConfigFormat<?> configFormat) {
      super(map);
      this.configFormat = configFormat;
   }

   SimpleConfig(Supplier<Map<String, Object>> mapCreator, ConfigFormat<?> configFormat) {
      super(mapCreator);
      this.configFormat = configFormat;
   }

   @Deprecated
   SimpleConfig(UnmodifiableConfig toCopy, ConfigFormat<?> configFormat, boolean concurrent) {
      super(toCopy, concurrent);
      this.configFormat = configFormat;
   }

   SimpleConfig(UnmodifiableConfig toCopy, Supplier<Map<String, Object>> mapCreator, ConfigFormat<?> configFormat) {
      super(toCopy, mapCreator);
      this.configFormat = configFormat;
   }

   @Override
   public ConfigFormat<?> configFormat() {
      return this.configFormat;
   }

   public SimpleConfig createSubConfig() {
      return new SimpleConfig(this.mapCreator, this.configFormat);
   }

   public SimpleConfig clone() {
      return new SimpleConfig(this, this.mapCreator, this.configFormat);
   }
}
