package link.e4steam.internal.kaleido.lib.quiltconfig.impl.builders;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.values.TrackedValue;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.values.ValueMap;
import link.e4steam.internal.kaleido.lib.quiltconfig.impl.values.ValueMapImpl;

public class ValueMapBuilderImpl<T> implements ValueMap.Builder<T> {
   private final T defaultValue;
   private final Map<String, T> values = new LinkedHashMap<>();

   public ValueMapBuilderImpl(T defaultValue) {
      this.defaultValue = defaultValue;
   }

   @Override
   public ValueMap.Builder<T> put(String key, T value) {
      this.values.put(key, value);
      return this;
   }

   @Override
   public ValueMap<T> build() {
      return new ValueMapImpl<>(this.defaultValue, this.values);
   }

   public static class TrackedValueMapBuilderImpl<T> implements ValueMap.TrackedBuilder<T> {
      private final T defaultValue;
      private final Map<String, T> values = new LinkedHashMap<>();
      private final Function<ValueMap<T>, TrackedValue<ValueMap<T>>> trackedValueFactory;

      public TrackedValueMapBuilderImpl(T defaultValue, Function<ValueMap<T>, TrackedValue<ValueMap<T>>> trackedValueFactory) {
         this.defaultValue = defaultValue;
         this.trackedValueFactory = trackedValueFactory;
      }

      @Override
      public ValueMap.TrackedBuilder<T> put(String key, T value) {
         this.values.put(key, value);
         return this;
      }

      @Override
      public TrackedValue<ValueMap<T>> build() {
         return this.trackedValueFactory.apply(new ValueMapImpl<>(this.defaultValue, this.values));
      }
   }
}
