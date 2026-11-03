package link.e4steam.internal.kaleido.lib.quiltconfig.api.values;

import java.util.Map;
import java.util.Map.Entry;
import link.e4steam.internal.kaleido.lib.quiltconfig.impl.builders.ValueMapBuilderImpl;
import link.e4steam.internal.kaleido.lib.quiltconfig.impl.util.ConfigUtils;
import org.jetbrains.annotations.ApiStatus.NonExtendable;

@NonExtendable
public interface ValueMap<T> extends Iterable<Entry<String, T>>, Map<String, T>, CompoundConfigValue<T> {
   static <T> ValueMap.Builder<T> builder(T defaultValue) {
      ConfigUtils.assertValueType(defaultValue);
      return new ValueMapBuilderImpl<>(defaultValue);
   }

   @NonExtendable
   public interface Builder<T> {
      ValueMap.Builder<T> put(String var1, T var2);

      ValueMap<T> build();
   }

   public interface TrackedBuilder<T> {
      ValueMap.TrackedBuilder<T> put(String var1, T var2);

      TrackedValue<ValueMap<T>> build();
   }
}
