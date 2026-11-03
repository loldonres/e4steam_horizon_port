package link.e4steam.internal.kaleido.lib.quiltconfig.impl.util;

import link.e4steam.internal.kaleido.lib.quiltconfig.api.exceptions.TrackedValueException;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.values.CompoundConfigValue;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.values.ConfigSerializableObject;

public final class ConfigUtils {
   private static final Class<?>[] VALID_VALUE_CLASSES = new Class[]{
      int.class, Integer.class, long.class, Long.class, float.class, Float.class, double.class, Double.class, boolean.class, Boolean.class, String.class
   };

   public static void assertValueType(Object object) {
      if (object == null) {
         throw new TrackedValueException("Cannot create value with null default value");
      } else if (!isValidValue(object)) {
         throw new TrackedValueException("Cannot create value of type '" + object.getClass() + "'");
      }
   }

   public static boolean isValidValue(Object object) {
      if (object == null) {
         return false;
      } else if (object instanceof ConfigSerializableObject) {
         return true;
      } else if (!(object instanceof CompoundConfigValue)) {
         return isValidValueClass(object.getClass());
      } else {
         while (object instanceof CompoundConfigValue) {
            object = ((CompoundConfigValue)object).getDefaultValue();
         }

         return isValidValue(object);
      }
   }

   public static boolean isValidValueClass(Class<?> valueClass) {
      if (valueClass.isEnum()) {
         return true;
      } else {
         for (Class<?> clazz : VALID_VALUE_CLASSES) {
            if (clazz == valueClass) {
               return true;
            }
         }

         return false;
      }
   }
}
