package link.e4steam.internal.kaleido.lib.quiltconfig.api.values;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import link.e4steam.internal.kaleido.lib.quiltconfig.impl.util.ConfigUtils;
import link.e4steam.internal.kaleido.lib.quiltconfig.impl.values.ValueListImpl;
import org.jetbrains.annotations.ApiStatus.NonExtendable;

@NonExtendable
public interface ValueList<T> extends List<T>, CompoundConfigValue<T> {
   @SafeVarargs
   static <T> ValueList<T> create(T defaultValue, T... values) {
      ConfigUtils.assertValueType(defaultValue);
      return new ValueListImpl<>(defaultValue, new ArrayList<>(Arrays.asList(values)));
   }
}
