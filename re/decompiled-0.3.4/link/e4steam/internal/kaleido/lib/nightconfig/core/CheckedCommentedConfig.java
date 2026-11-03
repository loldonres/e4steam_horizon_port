package link.e4steam.internal.kaleido.lib.nightconfig.core;

import java.util.List;
import java.util.Map;
import java.util.Set;
import link.e4steam.internal.kaleido.lib.nightconfig.core.utils.CommentedConfigWrapper;
import link.e4steam.internal.kaleido.lib.nightconfig.core.utils.TransformingMap;
import link.e4steam.internal.kaleido.lib.nightconfig.core.utils.TransformingSet;

class CheckedCommentedConfig extends CommentedConfigWrapper<CommentedConfig> {
   CheckedCommentedConfig(CommentedConfig config) {
      super(config);
      config.valueMap().forEach((k, v) -> this.checkValue(v));
   }

   @Override
   public CommentedConfig checked() {
      return this;
   }

   @Override
   public <T> T set(List<String> path, Object value) {
      return super.set(path, this.checkedValue(value));
   }

   @Override
   public boolean add(List<String> path, Object value) {
      return super.add(path, this.checkedValue(value));
   }

   @Override
   public Map<String, Object> valueMap() {
      return new TransformingMap<>(super.valueMap(), v -> (Object)v, this::checkedValue, o -> o);
   }

   @Override
   public Set<? extends CommentedConfig.Entry> entrySet() {
      return new TransformingSet<>(super.entrySet(), v -> (CommentedConfig.Entry)v, this::checkedValue, o -> o);
   }

   @Override
   public String toString() {
      return "checked " + this.config;
   }

   private void checkValue(Object value) {
      ConfigFormat<?> format = this.configFormat();
      if (value != null && !format.supportsType(value.getClass())) {
         throw new IllegalArgumentException("Unsupported value type: " + value.getClass().getTypeName());
      } else if (value == null && !format.supportsType(null)) {
         throw new IllegalArgumentException("Null values aren't supported by this configuration.");
      } else {
         if (value instanceof Config) {
            ((Config)value).valueMap().forEach((k, v) -> this.checkValue(v));
         }
      }
   }

   private <T> T checkedValue(T value) {
      this.checkValue(value);
      return value;
   }
}
