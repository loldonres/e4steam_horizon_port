package link.e4steam.internal.kaleido.lib.quiltconfig.api.values;

import org.jetbrains.annotations.ApiStatus.NonExtendable;

@NonExtendable
public interface CompoundConfigValue<T> extends ComplexConfigValue {
   Class<T> getType();

   T getDefaultValue();

   Iterable<T> values();

   void grow();
}
