package link.e4steam.internal.kaleido.lib.quiltconfig.api.metadata;

import java.lang.reflect.Type;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.values.ConfigSerializableObject;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.values.TrackedValue;

public final class MetadataType<T, B extends MetadataType.Builder<T>> {
   private final Supplier<Optional<T>> defaultValueSupplier;
   private final Function<Type, Optional<T>> trackedValueDefaultValueSupplier;
   private final Supplier<B> builderSupplier;
   private final boolean inherited;

   private MetadataType(
      Supplier<Optional<T>> defaultValueSupplier, Function<Type, Optional<T>> trackedValueDefaultValueSupplier, Supplier<B> builderSupplier, boolean inherited
   ) {
      this.defaultValueSupplier = defaultValueSupplier;
      this.trackedValueDefaultValueSupplier = trackedValueDefaultValueSupplier;
      this.builderSupplier = builderSupplier;
      this.inherited = inherited;
   }

   public Optional<T> getDefaultValue(MetadataContainer container) {
      if (container instanceof TrackedValue) {
         Object defaultValue = ((TrackedValue)container).getDefaultValue();
         if (defaultValue instanceof ConfigSerializableObject) {
            defaultValue = ((ConfigSerializableObject)defaultValue).getRepresentation();
         }

         return this.trackedValueDefaultValueSupplier.apply(defaultValue.getClass());
      } else {
         return this.defaultValueSupplier.get();
      }
   }

   public B newBuilder() {
      return this.builderSupplier.get();
   }

   public boolean isInherited() {
      return this.inherited;
   }

   public static <T, B extends MetadataType.Builder<T>> MetadataType<T, B> create(
      Supplier<Optional<T>> defaultSupplier, Function<Type, Optional<T>> trackedValueDefaultFunction, Supplier<B> builderSupplier
   ) {
      return new MetadataType<>(defaultSupplier, trackedValueDefaultFunction, builderSupplier, false);
   }

   public static <T, B extends MetadataType.Builder<T>> MetadataType<T, B> create(Supplier<Optional<T>> defaultSupplier, Supplier<B> builderSupplier) {
      return create(defaultSupplier, builderSupplier, false);
   }

   public static <T, B extends MetadataType.Builder<T>> MetadataType<T, B> create(Supplier<B> builderSupplier) {
      return create(builderSupplier, false);
   }

   public static <T, B extends MetadataType.Builder<T>> MetadataType<T, B> create(
      Supplier<Optional<T>> defaultSupplier, Function<Type, Optional<T>> trackedValueDefaultFunction, Supplier<B> builderSupplier, boolean inherited
   ) {
      return new MetadataType<>(defaultSupplier, trackedValueDefaultFunction, builderSupplier, inherited);
   }

   public static <T, B extends MetadataType.Builder<T>> MetadataType<T, B> create(
      Supplier<Optional<T>> defaultSupplier, Supplier<B> builderSupplier, boolean inherited
   ) {
      return new MetadataType<>(defaultSupplier, t -> defaultSupplier.get(), builderSupplier, inherited);
   }

   public static <T, B extends MetadataType.Builder<T>> MetadataType<T, B> create(Supplier<B> builderSupplier, boolean inherited) {
      return create(Optional::empty, builderSupplier, inherited);
   }

   public interface Builder<T> {
      T build();
   }
}
