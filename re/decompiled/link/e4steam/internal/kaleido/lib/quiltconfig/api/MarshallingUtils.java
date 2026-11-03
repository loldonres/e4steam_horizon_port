package link.e4steam.internal.kaleido.lib.quiltconfig.api;

import java.util.List;
import java.util.function.BiFunction;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.exceptions.ConfigParseException;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.values.ConfigSerializableObject;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.values.ValueList;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.values.ValueMap;

public final class MarshallingUtils {
   private MarshallingUtils() {
   }

   private static <M, L> Object coerce(
      Object object, Object to, BiFunction<M, ValueMap<?>, ValueMap<?>> valueMapCreator, BiFunction<L, ValueList<?>, ValueList<?>> valueListCreator
   ) {
      if (to instanceof Integer) {
         return ((Number)object).intValue();
      } else if (to instanceof Long) {
         return ((Number)object).longValue();
      } else if (to instanceof Float) {
         return ((Number)object).floatValue();
      } else if (to instanceof Double) {
         return ((Number)object).doubleValue();
      } else if (to instanceof String) {
         return object;
      } else if (to instanceof Boolean) {
         return object;
      } else if (to instanceof ConfigSerializableObject) {
         return ((ConfigSerializableObject)to)
            .convertFrom(coerce(object, ((ConfigSerializableObject)to).getRepresentation(), valueMapCreator, valueListCreator));
      } else if (to instanceof ValueMap) {
         return valueMapCreator.apply((M)object, (ValueMap<?>)to);
      } else if (to instanceof ValueList) {
         return valueListCreator.apply((L)object, (ValueList<?>)to);
      } else if (to.getClass().isEnum()) {
         for (Object o : to.getClass().getEnumConstants()) {
            if (((Enum)o).name().equalsIgnoreCase((String)object)) {
               return o;
            }
         }

         throw new ConfigParseException("Unexpected value '" + object + "' for enum class '" + to.getClass() + "'");
      } else {
         throw new ConfigParseException("Unexpected value type: " + to.getClass());
      }
   }

   public static <M, L> Object coerce(Object object, Object to, MarshallingUtils.ValueMapCreator<M> creator) {
      MarshallingUtils.MapCoercer<M> mapCoercer = new MarshallingUtils.MapCoercer<>(creator);
      return coerce(object, to, mapCoercer, new MarshallingUtils.ListCoercer(mapCoercer));
   }

   private static final class ListCoercer<M> implements BiFunction<List<?>, ValueList<?>, ValueList<?>> {
      private final BiFunction<M, ValueMap<?>, ValueMap<?>> valueMapCreator;

      private ListCoercer(BiFunction<M, ValueMap<?>, ValueMap<?>> valueMapCreator) {
         this.valueMapCreator = valueMapCreator;
      }

      public ValueList<?> apply(List<?> list, ValueList<?> defaultValue) {
         Object[] values = list.toArray();

         for (int i = 0; i < values.length; i++) {
            values[i] = MarshallingUtils.coerce(values[i], defaultValue.getDefaultValue(), this.valueMapCreator, this);
         }

         return ValueList.create(defaultValue.getDefaultValue(), values);
      }
   }

   private static final class MapCoercer<M> implements BiFunction<M, ValueMap<?>, ValueMap<?>> {
      private final MarshallingUtils.ValueMapCreator<M> creator;

      private MapCoercer(MarshallingUtils.ValueMapCreator<M> creator) {
         this.creator = creator;
      }

      public ValueMap<?> apply(M object, ValueMap<?> defaultValue) {
         ValueMap.Builder builder = ValueMap.builder(defaultValue.getDefaultValue());
         this.creator.create(object, (key, value) -> builder.put(key, MarshallingUtils.coerce(value, defaultValue.getDefaultValue(), this.creator)));
         return builder.build();
      }
   }

   public interface MapEntryConsumer {
      void put(String var1, Object var2);
   }

   public interface ValueMapCreator<M> {
      void create(M var1, MarshallingUtils.MapEntryConsumer var2);
   }
}
