package link.e4steam.internal.kaleido.lib.nightconfig.core;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import link.e4steam.internal.kaleido.lib.nightconfig.core.utils.StringUtils;

public interface UnmodifiableConfig {
   default <T> T get(String path) {
      return this.get(StringUtils.split(path, '.'));
   }

   default <T> T get(List<String> path) {
      Object raw = this.getRaw(path);
      return (T)(raw == NullObject.NULL_OBJECT ? null : raw);
   }

   default <T> T getRaw(String path) {
      return this.getRaw(StringUtils.split(path, '.'));
   }

   <T> T getRaw(List<String> var1);

   default <T> Optional<T> getOptional(String path) {
      return this.getOptional(StringUtils.split(path, '.'));
   }

   default <T> Optional<T> getOptional(List<String> path) {
      return Optional.ofNullable(this.get(path));
   }

   default <T> T getOrElse(String path, T defaultValue) {
      return this.getOrElse(StringUtils.split(path, '.'), defaultValue);
   }

   default <T> T getOrElse(List<String> path, T defaultValue) {
      T value = this.getRaw(path);
      return value != null && value != NullObject.NULL_OBJECT ? value : defaultValue;
   }

   default <T> T getOrElse(List<String> path, Supplier<T> defaultValueSupplier) {
      T value = this.getRaw(path);
      return value != null && value != NullObject.NULL_OBJECT ? value : defaultValueSupplier.get();
   }

   default <T> T getOrElse(String path, Supplier<T> defaultValueSupplier) {
      return this.getOrElse(StringUtils.split(path, '.'), defaultValueSupplier);
   }

   default <T extends Enum<T>> T getEnum(String path, Class<T> enumType, EnumGetMethod method) {
      return this.getEnum(StringUtils.split(path, '.'), enumType, method);
   }

   default <T extends Enum<T>> T getEnum(String path, Class<T> enumType) {
      return this.getEnum(StringUtils.split(path, '.'), enumType, EnumGetMethod.NAME_IGNORECASE);
   }

   default <T extends Enum<T>> T getEnum(List<String> path, Class<T> enumType, EnumGetMethod method) {
      Object value = this.getRaw(path);
      return method.get(value, enumType);
   }

   default <T extends Enum<T>> T getEnum(List<String> path, Class<T> enumType) {
      return this.getEnum(path, enumType, EnumGetMethod.NAME_IGNORECASE);
   }

   default <T extends Enum<T>> Optional<T> getOptionalEnum(String path, Class<T> enumType, EnumGetMethod method) {
      return this.getOptionalEnum(StringUtils.split(path, '.'), enumType, method);
   }

   default <T extends Enum<T>> Optional<T> getOptionalEnum(String path, Class<T> enumType) {
      return this.getOptionalEnum(path, enumType, EnumGetMethod.NAME_IGNORECASE);
   }

   default <T extends Enum<T>> Optional<T> getOptionalEnum(List<String> path, Class<T> enumType, EnumGetMethod method) {
      return Optional.ofNullable(this.getEnum(path, enumType, method));
   }

   default <T extends Enum<T>> Optional<T> getOptionalEnum(List<String> path, Class<T> enumType) {
      return this.getOptionalEnum(path, enumType, EnumGetMethod.NAME_IGNORECASE);
   }

   default <T extends Enum<T>> T getEnumOrElse(String path, T defaultValue, EnumGetMethod method) {
      return this.getEnumOrElse(StringUtils.split(path, '.'), defaultValue, method);
   }

   default <T extends Enum<T>> T getEnumOrElse(String path, T defaultValue) {
      return this.getEnumOrElse(path, defaultValue, EnumGetMethod.NAME_IGNORECASE);
   }

   default <T extends Enum<T>> T getEnumOrElse(List<String> path, T defaultValue, EnumGetMethod method) {
      T value = this.getEnum(path, defaultValue.getDeclaringClass(), method);
      return value == null ? defaultValue : value;
   }

   default <T extends Enum<T>> T getEnumOrElse(List<String> path, T defaultValue) {
      return this.getEnumOrElse(path, defaultValue, EnumGetMethod.NAME_IGNORECASE);
   }

   default <T extends Enum<T>> T getEnumOrElse(String path, Class<T> enumType, EnumGetMethod method, Supplier<T> defaultValueSupplier) {
      return this.getEnumOrElse(StringUtils.split(path, '.'), enumType, method, defaultValueSupplier);
   }

   default <T extends Enum<T>> T getEnumOrElse(String path, Class<T> enumType, Supplier<T> defaultValueSupplier) {
      return this.getEnumOrElse(path, enumType, EnumGetMethod.NAME_IGNORECASE, defaultValueSupplier);
   }

   default <T extends Enum<T>> T getEnumOrElse(List<String> path, Class<T> enumType, EnumGetMethod method, Supplier<T> defaultValueSupplier) {
      T value = this.getEnum(path, enumType, method);
      return value == null ? defaultValueSupplier.get() : value;
   }

   default <T extends Enum<T>> T getEnumOrElse(List<String> path, Class<T> enumType, Supplier<T> defaultValueSupplier) {
      return this.getEnumOrElse(path, enumType, EnumGetMethod.NAME_IGNORECASE, defaultValueSupplier);
   }

   default int getInt(String path) {
      return this.<Number>get(path).intValue();
   }

   default int getInt(List<String> path) {
      return this.<Number>getRaw(path).intValue();
   }

   default OptionalInt getOptionalInt(String path) {
      return this.getOptionalInt(StringUtils.split(path, '.'));
   }

   default OptionalInt getOptionalInt(List<String> path) {
      Number n = this.get(path);
      return n == null ? OptionalInt.empty() : OptionalInt.of(n.intValue());
   }

   default int getIntOrElse(String path, int defaultValue) {
      return this.getIntOrElse(StringUtils.split(path, (char)46), defaultValue);
   }

   default int getIntOrElse(List<String> path, int defaultValue) {
      Number n = this.get(path);
      return n == null ? defaultValue : n.intValue();
   }

   default int getIntOrElse(String path, IntSupplier defaultValueSupplier) {
      return this.getIntOrElse(StringUtils.split(path, (char)46), defaultValueSupplier);
   }

   default int getIntOrElse(List<String> path, IntSupplier defaultValueSupplier) {
      Number n = this.get(path);
      return n == null ? defaultValueSupplier.getAsInt() : n.intValue();
   }

   default long getLong(String path) {
      return this.<Number>getRaw(path).longValue();
   }

   default long getLong(List<String> path) {
      return this.<Number>getRaw(path).longValue();
   }

   default OptionalLong getOptionalLong(String path) {
      return this.getOptionalLong(StringUtils.split(path, '.'));
   }

   default OptionalLong getOptionalLong(List<String> path) {
      Number n = this.get(path);
      return n == null ? OptionalLong.empty() : OptionalLong.of(n.longValue());
   }

   default long getLongOrElse(String path, long defaultValue) {
      return this.getLongOrElse(StringUtils.split(path, '.'), defaultValue);
   }

   default long getLongOrElse(List<String> path, long defaultValue) {
      Number n = this.get(path);
      return n == null ? defaultValue : n.longValue();
   }

   default long getLongOrElse(String path, LongSupplier defaultValueSupplier) {
      return this.getLongOrElse(StringUtils.split(path, '.'), defaultValueSupplier);
   }

   default long getLongOrElse(List<String> path, LongSupplier defaultValueSupplier) {
      Number n = this.get(path);
      return n == null ? defaultValueSupplier.getAsLong() : n.longValue();
   }

   default byte getByte(String path) {
      return this.<Number>getRaw(path).byteValue();
   }

   default byte getByte(List<String> path) {
      return this.<Number>getRaw(path).byteValue();
   }

   default byte getByteOrElse(String path, byte defaultValue) {
      return this.getByteOrElse(StringUtils.split(path, '.'), defaultValue);
   }

   default byte getByteOrElse(List<String> path, byte defaultValue) {
      Number n = this.get(path);
      return n == null ? defaultValue : n.byteValue();
   }

   default short getShort(String path) {
      return this.<Number>getRaw(path).shortValue();
   }

   default short getShort(List<String> path) {
      return this.<Number>getRaw(path).shortValue();
   }

   default short getShortOrElse(String path, short defaultValue) {
      return this.getShortOrElse(StringUtils.split(path, '.'), defaultValue);
   }

   default short getShortOrElse(List<String> path, short defaultValue) {
      Number n = this.get(path);
      return n == null ? defaultValue : n.shortValue();
   }

   default char getChar(String path) {
      return (char)this.getInt(path);
   }

   default char getChar(List<String> path) {
      Object value = this.getRaw(path);
      if (value instanceof Number) {
         return (char)((Number)value).intValue();
      } else {
         return value instanceof CharSequence ? ((CharSequence)value).charAt(0) : (Character)value;
      }
   }

   default char getCharOrElse(String path, char defaultValue) {
      return this.getCharOrElse(StringUtils.split(path, '.'), defaultValue);
   }

   default char getCharOrElse(List<String> path, char defaultValue) {
      Object value = this.getRaw(path);
      if (value == null || value == NullObject.NULL_OBJECT) {
         return defaultValue;
      } else if (value instanceof Number) {
         return (char)((Number)value).intValue();
      } else {
         return value instanceof CharSequence ? ((CharSequence)value).charAt(0) : (Character)value;
      }
   }

   default boolean contains(String path) {
      return this.contains(StringUtils.split(path, '.'));
   }

   boolean contains(List<String> var1);

   default boolean isNull(String path) {
      return this.isNull(StringUtils.split(path, '.'));
   }

   default boolean isNull(List<String> path) {
      return this.getRaw(path) == NullObject.NULL_OBJECT;
   }

   int size();

   default boolean isEmpty() {
      return this.size() == 0;
   }

   @Deprecated
   Map<String, Object> valueMap();

   Set<? extends UnmodifiableConfig.Entry> entrySet();

   ConfigFormat<?> configFormat();

   default <T> T apply(String path) {
      return this.get(path);
   }

   default <T> T apply(List<String> path) {
      return this.get(path);
   }

   public interface Entry {
      String getKey();

      <T> T getRawValue();

      default <T> T getValue() {
         Object raw = this.getRawValue();
         return (T)(raw == NullObject.NULL_OBJECT ? null : raw);
      }

      default boolean isNull() {
         return this.getRawValue() == NullObject.NULL_OBJECT;
      }

      default <T> Optional<T> getOptional() {
         return Optional.ofNullable(this.getValue());
      }

      default <T> T getOrElse(T defaultValue) {
         T value = this.getRawValue();
         return value != null && value != NullObject.NULL_OBJECT ? value : defaultValue;
      }

      default int getInt() {
         return this.<Number>getRawValue().intValue();
      }

      default OptionalInt getOptionalInt() {
         Number value = this.getRawValue();
         return value == null ? OptionalInt.empty() : OptionalInt.of(value.intValue());
      }

      default int getIntOrElse(int defaultValue) {
         Number value = this.getRawValue();
         return value == null ? defaultValue : value.intValue();
      }

      default long getLong() {
         return this.<Number>getRawValue().longValue();
      }

      default OptionalLong getOptionalLong() {
         Number value = this.getRawValue();
         return value == null ? OptionalLong.empty() : OptionalLong.of(value.longValue());
      }

      default long getLongOrElse(long defaultValue) {
         Number value = this.getRawValue();
         return value == null ? defaultValue : value.longValue();
      }

      default byte getByte() {
         return this.<Number>getRawValue().byteValue();
      }

      default byte getByteOrElse(byte defaultValue) {
         Number value = this.getRawValue();
         return value == null ? defaultValue : value.byteValue();
      }

      default short getShort() {
         return this.<Number>getRawValue().shortValue();
      }

      default short getShortOrElse(short defaultValue) {
         Number value = this.getRawValue();
         return value == null ? defaultValue : value.shortValue();
      }

      default char getChar() {
         Object value = this.getRawValue();
         if (value instanceof Number) {
            return (char)((Number)value).intValue();
         } else {
            return value instanceof CharSequence ? ((CharSequence)value).charAt(0) : (Character)value;
         }
      }

      default char getCharOrElse(char defaultValue) {
         Object value = this.getRawValue();
         if (value == null) {
            return defaultValue;
         } else if (value instanceof Number) {
            return (char)((Number)value).intValue();
         } else {
            return value instanceof CharSequence ? ((CharSequence)value).charAt(0) : (Character)value;
         }
      }
   }
}
