package link.e4steam.internal.kaleido.lib.quiltconfig.impl.values;

import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Map.Entry;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.values.ComplexConfigValue;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.values.CompoundConfigValue;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.values.TrackedValue;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.values.ValueMap;
import link.e4steam.internal.kaleido.lib.quiltconfig.impl.tree.TrackedValueImpl;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class ValueMapImpl<T> implements ValueMap<T>, CompoundConfigValue<T> {
   private final T defaultValue;
   private final Map<String, T> values;
   private TrackedValueImpl<?> configValue;

   public ValueMapImpl(T defaultValue, Map<String, T> values) {
      this.defaultValue = defaultValue;
      this.values = values;
   }

   @Override
   public void setValue(TrackedValue<?> configValue) {
      this.configValue = (TrackedValueImpl<?>)configValue;
      if (this.defaultValue instanceof ComplexConfigValue) {
         for (T value : this.values.values()) {
            ((ComplexConfigValue)value).setValue(configValue);
         }
      }
   }

   public ValueMap<T> copy() {
      Map<String, T> values = new LinkedHashMap<>();

      for (Entry<String, T> entry : this) {
         T value = entry.getValue();
         if (value instanceof CompoundConfigValue) {
            values.put(entry.getKey(), (T)((CompoundConfigValue)value).copy());
         } else {
            values.put(entry.getKey(), value);
         }
      }

      ValueMapImpl<T> result = new ValueMapImpl<>(this.defaultValue, values);
      result.setValue(this.configValue);
      return result;
   }

   @Override
   public int size() {
      return this.values.size();
   }

   @Override
   public boolean isEmpty() {
      return this.values.isEmpty();
   }

   @Override
   public boolean containsKey(Object key) {
      return this.values.containsKey(key);
   }

   @Override
   public boolean containsValue(Object value) {
      return this.values.containsValue(value);
   }

   @Override
   public T get(Object key) {
      return this.values.get(key);
   }

   @Nullable
   public T put(String key, T value) {
      T v = this.values.put(key, value);
      if (value instanceof ComplexConfigValue) {
         ((ComplexConfigValue)value).setValue(this.configValue);
      }

      this.configValue.serializeAndInvokeCallbacks();
      return v;
   }

   @Override
   public T remove(Object key) {
      T result = this.values.remove(key);
      this.configValue.serializeAndInvokeCallbacks();
      return result;
   }

   @Override
   public void putAll(@NotNull Map<? extends String, ? extends T> m) {
      this.values.putAll(m);

      for (T value : m.values()) {
         if (value instanceof ComplexConfigValue) {
            ((ComplexConfigValue)value).setValue(this.configValue);
         }
      }

      this.configValue.serializeAndInvokeCallbacks();
   }

   @Override
   public void clear() {
      this.values.clear();
      this.configValue.serializeAndInvokeCallbacks();
   }

   @NotNull
   @Override
   public Set<String> keySet() {
      return this.values.keySet();
   }

   @NotNull
   @Override
   public Collection<T> values() {
      return this.values.values();
   }

   @NotNull
   @Override
   public Set<Entry<String, T>> entrySet() {
      return this.values.entrySet();
   }

   @Override
   public Class<T> getType() {
      return (Class<T>)this.defaultValue.getClass();
   }

   @Override
   public T getDefaultValue() {
      return this.defaultValue;
   }

   @Override
   public void grow() {
      if (this.defaultValue instanceof ValueListImpl) {
         this.values.put("", (T)((ValueListImpl)this.defaultValue).copy());
      } else if (this.defaultValue instanceof ValueMapImpl) {
         this.values.put("", (T)((ValueMapImpl)this.defaultValue).copy());
      } else {
         this.values.put("", this.defaultValue);
      }
   }

   @NotNull
   @Override
   public Iterator<Entry<String, T>> iterator() {
      return this.values.entrySet().iterator();
   }

   @Override
   public boolean equals(Object o) {
      if (this == o) {
         return true;
      } else if (o != null && this.getClass() == o.getClass()) {
         ValueMapImpl<?> valueMap = (ValueMapImpl<?>)o;
         return Objects.equals(this.defaultValue, valueMap.defaultValue) && Objects.equals(this.values, valueMap.values);
      } else {
         return false;
      }
   }

   @Override
   public int hashCode() {
      return Objects.hash(this.defaultValue, this.values, this.configValue);
   }
}
