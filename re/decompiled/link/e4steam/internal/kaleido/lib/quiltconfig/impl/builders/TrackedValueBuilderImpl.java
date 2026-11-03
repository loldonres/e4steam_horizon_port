package link.e4steam.internal.kaleido.lib.quiltconfig.impl.builders;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Map.Entry;
import java.util.function.Consumer;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.Constraint;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.metadata.MetadataType;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.values.TrackedValue;
import link.e4steam.internal.kaleido.lib.quiltconfig.impl.tree.TrackedValueImpl;
import link.e4steam.internal.kaleido.lib.quiltconfig.impl.values.ValueKeyImpl;

public class TrackedValueBuilderImpl<T> implements TrackedValue.Builder<T> {
   private final T defaultValue;
   private final Set<String> key = new LinkedHashSet<>();
   final Map<MetadataType<?, ?>, MetadataType.Builder<?>> metadata = new LinkedHashMap<>();
   private final List<TrackedValue.UpdateCallback<T>> callbacks = new ArrayList<>();
   private final List<Constraint<T>> constraints = new ArrayList<>();

   public TrackedValueBuilderImpl(T defaultValue, String key0) {
      Objects.requireNonNull(key0);
      this.defaultValue = defaultValue;
      this.key.add(key0);
   }

   @Override
   public T getDefaultValue() {
      return this.defaultValue;
   }

   @Override
   public TrackedValue.Builder<T> key(String key) {
      this.key.add(key);
      return this;
   }

   @Override
   public <M, B extends MetadataType.Builder<M>> TrackedValue.Builder<T> metadata(MetadataType<M, B> type, Consumer<B> builderConsumer) {
      builderConsumer.accept((B)this.metadata.computeIfAbsent(type, t -> type.newBuilder()));
      return this;
   }

   @Override
   public TrackedValue.Builder<T> constraint(Constraint<T> constraint) {
      this.constraints.add(constraint);
      return this;
   }

   @Override
   public TrackedValue.Builder<T> callback(TrackedValue.UpdateCallback<T> callback) {
      this.callbacks.add(callback);
      return this;
   }

   public TrackedValue<T> build() {
      Map<MetadataType<?, ?>, Object> metadata = new LinkedHashMap<>();

      for (Entry<MetadataType<?, ?>, MetadataType.Builder<?>> entry : this.metadata.entrySet()) {
         metadata.put(entry.getKey(), entry.getValue().build());
      }

      return new TrackedValueImpl<>(new ValueKeyImpl(this.key.toArray(new String[0])), this.defaultValue, metadata, this.callbacks, this.constraints);
   }
}
