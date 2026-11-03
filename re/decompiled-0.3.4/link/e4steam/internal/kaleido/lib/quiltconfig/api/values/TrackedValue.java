package link.e4steam.internal.kaleido.lib.quiltconfig.api.values;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.Constraint;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.metadata.MetadataContainerBuilder;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.metadata.MetadataType;
import link.e4steam.internal.kaleido.lib.quiltconfig.impl.builders.TrackedValueBuilderImpl;
import link.e4steam.internal.kaleido.lib.quiltconfig.impl.tree.TrackedValueImpl;
import link.e4steam.internal.kaleido.lib.quiltconfig.impl.util.ConfigUtils;
import link.e4steam.internal.kaleido.lib.quiltconfig.impl.values.ValueKeyImpl;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.ApiStatus.NonExtendable;

@NonExtendable
public interface TrackedValue<T> extends ValueTreeNode {
   @Override
   ValueKey key();

   T value();

   boolean isBeingOverridden();

   T getRealValue();

   T setValue(@NotNull T var1, boolean var2);

   default T setValue(@NotNull T newValue) {
      return this.setValue(newValue, true);
   }

   void setOverride(T var1);

   void removeOverride();

   T getDefaultValue();

   void registerCallback(TrackedValue.UpdateCallback<T> var1);

   @Override
   <M> M metadata(MetadataType<M, ?> var1);

   @Override
   <M> boolean hasMetadata(MetadataType<M, ?> var1);

   Iterable<Constraint<T>> constraints();

   Optional<Iterable<String>> checkForFailingConstraints(T var1);

   static <T> TrackedValue<T> create(@NotNull T defaultValue, @NotNull String key0, String... keys) {
      ConfigUtils.assertValueType(defaultValue);
      Objects.requireNonNull(key0);
      return new TrackedValueImpl<>(new ValueKeyImpl(key0, keys), defaultValue, new LinkedHashMap<>(0), new ArrayList<>(0), new ArrayList<>(0));
   }

   static <T> TrackedValue<T> create(@NotNull T defaultValue, String key0, Consumer<TrackedValue.Builder<T>> creator) {
      TrackedValueBuilderImpl<T> builder = new TrackedValueBuilderImpl<>(defaultValue, key0);
      creator.accept(builder);
      return builder.build();
   }

   void invokeCallbacks();

   void serializeAndInvokeCallbacks();

   @NonExtendable
   public interface Builder<T> extends MetadataContainerBuilder<TrackedValue.Builder<T>> {
      T getDefaultValue();

      TrackedValue.Builder<T> key(String var1);

      <M, B extends MetadataType.Builder<M>> TrackedValue.Builder<T> metadata(MetadataType<M, B> var1, Consumer<B> var2);

      TrackedValue.Builder<T> constraint(Constraint<T> var1);

      TrackedValue.Builder<T> callback(TrackedValue.UpdateCallback<T> var1);
   }

   public interface UpdateCallback<T> {
      void onUpdate(TrackedValue<T> var1);
   }
}
