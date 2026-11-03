package link.e4steam.internal.kaleido.lib.nightconfig.core;

public enum EnumGetMethod {
   NAME,
   NAME_IGNORECASE,
   ORDINAL_OR_NAME,
   ORDINAL_OR_NAME_IGNORECASE;

   public boolean isCaseSensitive() {
      return this == NAME || this == ORDINAL_OR_NAME;
   }

   public boolean isOrdinalOk() {
      return this == ORDINAL_OR_NAME || this == ORDINAL_OR_NAME_IGNORECASE;
   }

   public <T extends Enum<T>> T get(Object value, Class<T> enumType) {
      if (value != null && value != NullObject.NULL_OBJECT) {
         Class<?> cls = value.getClass();
         if (enumType.isAssignableFrom(cls)) {
            return (T)value;
         } else if (cls == String.class) {
            String name = (String)value;
            if (this.isCaseSensitive()) {
               return Enum.valueOf(enumType, name);
            } else {
               for (T item : (Enum[])enumType.getEnumConstants()) {
                  if (item.name().equalsIgnoreCase(name)) {
                     return item;
                  }
               }

               String enumName = enumType.getCanonicalName();
               throw new IllegalArgumentException("No enum constant " + enumName + "." + name);
            }
         } else if (cls != Integer.class && cls != Short.class && cls != Byte.class) {
            String name = cls.getCanonicalName();
            throw new ClassCastException("Cannot convert a value of type " + name + " to an Enum");
         } else if (this.isOrdinalOk()) {
            return enumType.getEnumConstants()[(Integer)value];
         } else {
            throw new ClassCastException("Cannot convert an Integer to an Enum: disallowed by EnumGetMethod." + this);
         }
      } else {
         return null;
      }
   }

   public <T extends Enum<T>> boolean validate(Object value, Class<T> enumType) {
      if (value != null && value != NullObject.NULL_OBJECT) {
         Class<?> cls = value.getClass();
         if (enumType.isAssignableFrom(cls)) {
            return true;
         } else {
            if (cls == String.class) {
               String name = (String)value;
               if (this.isCaseSensitive()) {
                  for (T item : (Enum[])enumType.getEnumConstants()) {
                     if (item.name().equals(name)) {
                        return true;
                     }
                  }
               } else {
                  for (T itemx : (Enum[])enumType.getEnumConstants()) {
                     if (itemx.name().equalsIgnoreCase(name)) {
                        return true;
                     }
                  }
               }
            } else if (cls == Integer.class && this.isOrdinalOk()) {
               int idx = (Integer)value;
               return idx >= 0 && idx < ((Enum[])enumType.getEnumConstants()).length;
            }

            return false;
         }
      } else {
         return true;
      }
   }
}
