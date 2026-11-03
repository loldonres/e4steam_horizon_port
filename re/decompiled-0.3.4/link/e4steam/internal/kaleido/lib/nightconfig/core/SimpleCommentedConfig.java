package link.e4steam.internal.kaleido.lib.nightconfig.core;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

final class SimpleCommentedConfig extends AbstractCommentedConfig {
   private final ConfigFormat<?> configFormat;

   @Deprecated
   SimpleCommentedConfig(ConfigFormat<?> configFormat, boolean concurrent) {
      super((Map<String, Object>)(concurrent ? new ConcurrentHashMap<>() : new HashMap<>()));
      this.configFormat = configFormat;
   }

   SimpleCommentedConfig(Map<String, Object> valueMap, ConfigFormat<?> configFormat) {
      super(valueMap);
      this.configFormat = configFormat;
   }

   SimpleCommentedConfig(Supplier<Map<String, Object>> mapCreator, ConfigFormat<?> configFormat) {
      super(mapCreator);
      this.configFormat = configFormat;
   }

   @Deprecated
   SimpleCommentedConfig(UnmodifiableConfig toCopy, ConfigFormat<?> configFormat, boolean concurrent) {
      super(toCopy, concurrent);
      this.configFormat = configFormat;
   }

   public SimpleCommentedConfig(UnmodifiableConfig toCopy, Supplier<Map<String, Object>> mapCreator, ConfigFormat<?> configFormat) {
      super(toCopy, mapCreator);
      this.configFormat = configFormat;
   }

   @Deprecated
   SimpleCommentedConfig(UnmodifiableCommentedConfig toCopy, ConfigFormat<?> configFormat, boolean concurrent) {
      super(toCopy, concurrent);
      this.configFormat = configFormat;
   }

   public SimpleCommentedConfig(UnmodifiableCommentedConfig toCopy, Supplier<Map<String, Object>> mapCreator, ConfigFormat<?> configFormat) {
      super(toCopy, mapCreator);
      this.configFormat = configFormat;
   }

   @Override
   public ConfigFormat<?> configFormat() {
      return this.configFormat;
   }

   public SimpleCommentedConfig createSubConfig() {
      return new SimpleCommentedConfig(this.mapCreator, this.configFormat);
   }

   @Override
   public AbstractCommentedConfig clone() {
      return new SimpleCommentedConfig((UnmodifiableCommentedConfig)this, this.mapCreator, this.configFormat);
   }
}
