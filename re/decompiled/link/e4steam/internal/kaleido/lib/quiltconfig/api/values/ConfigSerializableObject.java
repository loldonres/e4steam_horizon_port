package link.e4steam.internal.kaleido.lib.quiltconfig.api.values;

public interface ConfigSerializableObject<T> extends ComplexConfigValue {
   @Override
   default void setValue(TrackedValue<?> value) {
   }

   ConfigSerializableObject<T> convertFrom(T var1);

   T getRepresentation();
}
