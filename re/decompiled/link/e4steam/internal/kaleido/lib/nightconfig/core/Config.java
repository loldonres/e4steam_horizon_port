package link.e4steam.internal.kaleido.lib.nightconfig.core;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import link.e4steam.internal.kaleido.lib.nightconfig.core.utils.StringUtils;

public interface Config extends UnmodifiableConfig {
   default <T> T set(String path, Object value) {
      return this.set(StringUtils.split(path, '.'), value);
   }

   <T> T set(List<String> var1, Object var2);

   boolean add(List<String> var1, Object var2);

   default boolean add(String path, Object value) {
      return this.add(StringUtils.split(path, '.'), value);
   }

   default void addAll(UnmodifiableConfig config) {
      for (UnmodifiableConfig.Entry ue : config.entrySet()) {
         List<String> key = Collections.singletonList(ue.getKey());
         Object value = ue.getRawValue();
         boolean existed = !this.add(key, value);
         if (existed && value instanceof UnmodifiableConfig) {
         }
      }
   }

   default void putAll(UnmodifiableConfig config) {
      this.valueMap().putAll(config.valueMap());
   }

   default <T> T remove(String path) {
      return this.remove(StringUtils.split(path, '.'));
   }

   <T> T remove(List<String> var1);

   default void removeAll(UnmodifiableConfig config) {
      this.valueMap().keySet().removeAll(config.valueMap().keySet());
   }

   void clear();

   default UnmodifiableConfig unmodifiable() {
      return new UnmodifiableConfig() {
         @Override
         public <T> T getRaw(List<String> path) {
            return Config.this.getRaw(path);
         }

         @Override
         public boolean contains(List<String> path) {
            return Config.this.contains(path);
         }

         @Override
         public int size() {
            return Config.this.size();
         }

         @Override
         public Map<String, Object> valueMap() {
            return Collections.unmodifiableMap(Config.this.valueMap());
         }

         @Override
         public Set<? extends UnmodifiableConfig.Entry> entrySet() {
            return Config.this.entrySet();
         }

         @Override
         public ConfigFormat<?> configFormat() {
            return Config.this.configFormat();
         }
      };
   }

   default Config checked() {
      return new CheckedConfig(this);
   }

   @Deprecated
   @Override
   Map<String, Object> valueMap();

   @Override
   Set<? extends Config.Entry> entrySet();

   Config createSubConfig();

   default void update(String path, Object value) {
      this.set(path, value);
   }

   default void update(List<String> path, Object value) {
      this.set(path, value);
   }

   static Config of(ConfigFormat<? extends Config> format) {
      return new SimpleConfig(format, false);
   }

   static Config of(Supplier<Map<String, Object>> mapCreator, ConfigFormat<?> format) {
      return new SimpleConfig(mapCreator, format);
   }

   @Deprecated
   static Config ofConcurrent(ConfigFormat<? extends Config> format) {
      return new SimpleConfig(format, true);
   }

   static Config inMemory() {
      return InMemoryFormat.defaultInstance().createConfig();
   }

   static Config inMemoryUniversal() {
      return InMemoryFormat.withUniversalSupport().createConfig();
   }

   @Deprecated
   static Config inMemoryConcurrent() {
      return InMemoryFormat.defaultInstance().createConcurrentConfig();
   }

   @Deprecated
   static Config inMemoryUniversalConcurrent() {
      return InMemoryFormat.withUniversalSupport().createConcurrentConfig();
   }

   static Config wrap(Map<String, Object> map, ConfigFormat<?> format) {
      return new SimpleConfig(map, format);
   }

   static Config copy(UnmodifiableConfig config) {
      return new SimpleConfig(config, config.configFormat(), false);
   }

   static Config copy(UnmodifiableConfig config, Supplier<Map<String, Object>> mapCreator) {
      return new SimpleConfig(config, mapCreator, config.configFormat());
   }

   static Config copy(UnmodifiableConfig config, ConfigFormat<?> format) {
      return new SimpleConfig(config, format, false);
   }

   static Config copy(UnmodifiableConfig config, Supplier<Map<String, Object>> mapCreator, ConfigFormat<?> format) {
      return new SimpleConfig(config, mapCreator, format);
   }

   @Deprecated
   static Config concurrentCopy(UnmodifiableConfig config) {
      return new SimpleConfig(config, config.configFormat(), true);
   }

   @Deprecated
   static Config concurrentCopy(UnmodifiableConfig config, ConfigFormat<?> format) {
      return new SimpleConfig(config, format, true);
   }

   static boolean isInsertionOrderPreserved() {
      String prop = System.getProperty("nightconfig.preserveInsertionOrder");
      return prop != null && (prop.equals("true") || prop.equals("1"));
   }

   static void setInsertionOrderPreserved(boolean orderPreserved) {
      System.setProperty("nightconfig.preserveInsertionOrder", orderPreserved ? "true" : "false");
   }

   @Deprecated
   static <T> Supplier<Map<String, T>> getDefaultMapCreator(boolean concurrent, boolean insertionOrderPreserved) {
      if (insertionOrderPreserved) {
         return concurrent ? () -> Collections.synchronizedMap(new LinkedHashMap<>()) : LinkedHashMap::new;
      } else {
         return concurrent ? ConcurrentHashMap::new : HashMap::new;
      }
   }

   @Deprecated
   static <T> Supplier<Map<String, T>> getDefaultMapCreator(boolean concurrent) {
      return getDefaultMapCreator(concurrent, isInsertionOrderPreserved());
   }

   public interface Entry extends UnmodifiableConfig.Entry {
      <T> T setValue(Object var1);
   }
}
