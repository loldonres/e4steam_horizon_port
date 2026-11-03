package link.e4steam.internal.kaleido.lib.nightconfig.core;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;
import link.e4steam.internal.kaleido.lib.nightconfig.core.utils.TransformingSet;

public abstract class AbstractConfig implements Config, Cloneable {
   protected final Supplier<Map<String, Object>> mapCreator;
   protected final Map<String, Object> map;

   @Deprecated
   public AbstractConfig(boolean concurrent) {
      this(getDefaultMapCreator(concurrent));
   }

   public AbstractConfig(Supplier<Map<String, Object>> mapCreator) {
      this.mapCreator = mapCreator;
      this.map = mapCreator.get();
   }

   public AbstractConfig(Map<String, Object> map) {
      this.map = map;
      this.mapCreator = getDefaultMapCreator(map instanceof ConcurrentMap);
   }

   @Deprecated
   public AbstractConfig(UnmodifiableConfig toCopy, boolean concurrent) {
      this(toCopy, getDefaultMapCreator(concurrent));
   }

   public AbstractConfig(UnmodifiableConfig toCopy, Supplier<Map<String, Object>> mapCreator) {
      this.map = mapCreator.get();

      try {
         this.map.putAll(toCopy.valueMap());
      } catch (UnsupportedOperationException var6) {
         for (UnmodifiableConfig.Entry entry : toCopy.entrySet()) {
            this.map.put(entry.getKey(), entry.getRawValue());
         }
      }

      this.mapCreator = mapCreator;
   }

   @Deprecated
   protected static <T> Supplier<Map<String, T>> getDefaultMapCreator(boolean concurrent) {
      return Config.getDefaultMapCreator(concurrent);
   }

   protected static <T> Supplier<Map<String, T>> getWildcardMapCreator(Supplier<Map<String, Object>> mapCreator) {
      return () -> {
         Map<String, Object> map = mapCreator.get();
         map.clear();
         return (Map<String, T>)map;
      };
   }

   @Override
   public <T> T getRaw(List<String> path) {
      int lastIndex = path.size() - 1;
      Map<String, Object> parentMap = this.getMap(path.subList(0, lastIndex));
      if (parentMap == null) {
         return null;
      } else {
         String lastKey = path.get(lastIndex);
         return (T)parentMap.get(lastKey);
      }
   }

   @Override
   public <T> T set(List<String> path, Object value) {
      int lastIndex = path.size() - 1;
      Map<String, Object> parentMap = this.getOrCreateMap(path.subList(0, lastIndex));
      String lastKey = path.get(lastIndex);
      Object nonNull = value == null ? NullObject.NULL_OBJECT : value;
      return (T)parentMap.put(lastKey, nonNull);
   }

   @Override
   public boolean add(List<String> path, Object value) {
      int lastIndex = path.size() - 1;
      Map<String, Object> parentMap = this.getOrCreateMap(path.subList(0, lastIndex));
      String lastKey = path.get(lastIndex);
      Object nonNull = value == null ? NullObject.NULL_OBJECT : value;
      return parentMap.putIfAbsent(lastKey, nonNull) == null;
   }

   @Override
   public <T> T remove(List<String> path) {
      int lastIndex = path.size() - 1;
      Map<String, Object> parentMap = this.getMap(path.subList(0, lastIndex));
      if (parentMap == null) {
         return null;
      } else {
         String lastKey = path.get(lastIndex);
         return (T)parentMap.remove(lastKey);
      }
   }

   @Override
   public boolean contains(List<String> path) {
      int lastIndex = path.size() - 1;
      Map<String, Object> parentMap = this.getMap(path.subList(0, lastIndex));
      if (parentMap == null) {
         return false;
      } else {
         String lastKey = path.get(lastIndex);
         return parentMap.containsKey(lastKey);
      }
   }

   @Override
   public boolean isNull(List<String> path) {
      int lastIndex = path.size() - 1;
      Map<String, Object> parentMap = this.getMap(path.subList(0, lastIndex));
      if (parentMap == null) {
         return false;
      } else {
         String lastKey = path.get(lastIndex);
         Object value = parentMap.get(lastKey);
         return value == NullObject.NULL_OBJECT;
      }
   }

   private Map<String, Object> getOrCreateMap(List<String> path) {
      Map<String, Object> currentMap = this.map;

      for (String currentKey : path) {
         Object currentValue = currentMap.get(currentKey);
         Config config;
         if (currentValue == null) {
            config = this.createSubConfig();
            currentMap.put(currentKey, config);
         } else {
            if (!(currentValue instanceof Config)) {
               throw new IncompatibleIntermediaryLevelException("Cannot add an element to an intermediary value of type: " + currentValue.getClass());
            }

            config = (Config)currentValue;
         }

         currentMap = config.valueMap();
      }

      return currentMap;
   }

   private Map<String, Object> getMap(List<String> path) {
      Map<String, Object> currentMap = this.map;

      for (String key : path) {
         Object value = currentMap.get(key);
         if (!(value instanceof Config)) {
            return null;
         }

         currentMap = ((Config)value).valueMap();
      }

      return currentMap;
   }

   @Override
   public void clear() {
      this.map.clear();
   }

   @Override
   public int size() {
      return this.map.size();
   }

   @Override
   public Map<String, Object> valueMap() {
      return this.map;
   }

   @Override
   public Set<? extends Config.Entry> entrySet() {
      return new TransformingSet<>(this.map.entrySet(), AbstractConfig.EntryWrapper::new, o -> null, o -> o);
   }

   public abstract AbstractConfig clone();

   @Override
   public int hashCode() {
      return this.map.hashCode();
   }

   @Override
   public boolean equals(Object obj) {
      if (obj == this) {
         return true;
      } else if (obj instanceof AbstractConfig) {
         return this.map.equals(((AbstractConfig)obj).map);
      } else if (obj instanceof UnmodifiableConfig) {
         UnmodifiableConfig conf = (UnmodifiableConfig)obj;
         if (conf.size() != this.size()) {
            return false;
         } else {
            for (UnmodifiableConfig.Entry entry : this.entrySet()) {
               Object value = entry.getValue();
               Object otherEntry = conf.get(Collections.singletonList(entry.getKey()));
               if (value == null) {
                  if (otherEntry != null) {
                     return false;
                  }
               } else if (!value.equals(otherEntry)) {
                  return false;
               }
            }

            return true;
         }
      } else {
         return false;
      }
   }

   @Override
   public String toString() {
      return this.getClass().getSimpleName() + ':' + this.valueMap();
   }

   protected static class EntryWrapper implements Config.Entry {
      protected final Map.Entry<String, Object> mapEntry;

      public EntryWrapper(Map.Entry<String, Object> mapEntry) {
         this.mapEntry = mapEntry;
      }

      @Override
      public String getKey() {
         return this.mapEntry.getKey();
      }

      @Override
      public <T> T getRawValue() {
         return (T)this.mapEntry.getValue();
      }

      @Override
      public <T> T setValue(Object value) {
         return (T)this.mapEntry.setValue(value);
      }

      @Override
      public boolean equals(Object obj) {
         if (obj == this) {
            return true;
         } else if (!(obj instanceof AbstractConfig.EntryWrapper)) {
            return false;
         } else {
            AbstractConfig.EntryWrapper other = (AbstractConfig.EntryWrapper)obj;
            return Objects.equals(this.getKey(), other.getKey()) && Objects.equals(this.getValue(), other.getValue());
         }
      }

      @Override
      public int hashCode() {
         int result = 1;
         result = 31 * result + Objects.hashCode(this.getKey());
         return 31 * result + Objects.hashCode(this.getValue());
      }
   }
}
