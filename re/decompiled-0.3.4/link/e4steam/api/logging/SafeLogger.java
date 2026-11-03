package link.e4steam.api.logging;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Map.Entry;
import link.e4steam.api.ApiResult;
import link.e4steam.api.ApiValidation;

public interface SafeLogger {
   ApiResult<Boolean> log(SafeLogger.Level var1, String var2, Map<String, SafeLogger.SafeValue> var3);

   static Map<String, SafeLogger.SafeValue> fields(Map<String, SafeLogger.SafeValue> fields) {
      if (fields != null && fields.size() <= 256) {
         LinkedHashMap<String, SafeLogger.SafeValue> copy = new LinkedHashMap<>();

         for (Entry<String, SafeLogger.SafeValue> entry : fields.entrySet()) {
            String key = ApiValidation.text(entry.getKey(), "log field", 64);
            ApiValidation.rejectSensitiveName(key, "log field");
            copy.put(key, Objects.requireNonNull(entry.getValue(), "log value"));
         }

         return Collections.unmodifiableMap(copy);
      } else {
         throw new IllegalArgumentException("invalid safe fields");
      }
   }

   public static enum Level {
      DEBUG,
      INFO,
      WARN,
      ERROR;
   }

   public static final class SafeValue {
      private final SafeLogger.SafeValue.Type type;
      private final String encoded;

      private SafeValue(SafeLogger.SafeValue.Type type, String encoded) {
         this.type = type;
         this.encoded = encoded;
      }

      public static SafeLogger.SafeValue text(String value) {
         String checked = ApiValidation.text(value, "log value", 512);
         if (looksSensitive(checked)) {
            throw new IllegalArgumentException("log value appears sensitive");
         } else {
            return new SafeLogger.SafeValue(SafeLogger.SafeValue.Type.TEXT, checked);
         }
      }

      public static SafeLogger.SafeValue integer(long value) {
         return new SafeLogger.SafeValue(SafeLogger.SafeValue.Type.INTEGER, Long.toString(value));
      }

      public static SafeLogger.SafeValue decimal(double value) {
         if (!Double.isNaN(value) && !Double.isInfinite(value)) {
            return new SafeLogger.SafeValue(SafeLogger.SafeValue.Type.DECIMAL, Double.toString(value));
         } else {
            throw new IllegalArgumentException("value must be finite");
         }
      }

      public static SafeLogger.SafeValue bool(boolean value) {
         return new SafeLogger.SafeValue(SafeLogger.SafeValue.Type.BOOLEAN, Boolean.toString(value));
      }

      public SafeLogger.SafeValue.Type type() {
         return this.type;
      }

      public String encoded() {
         return this.encoded;
      }

      @Override
      public String toString() {
         return this.type == SafeLogger.SafeValue.Type.TEXT ? "TEXT{redacted-from-toString}" : this.type + ":" + this.encoded;
      }

      private static boolean looksSensitive(String value) {
         String lower = value.toLowerCase(Locale.ROOT);
         return lower.contains("bearer ")
            || lower.contains("password=")
            || lower.contains("token=")
            || lower.contains("ticket=")
            || lower.contains("secret=")
            || lower.contains("cookie=")
            || lower.contains("authorization=")
            || lower.contains("joinaddress=");
      }

      public static enum Type {
         TEXT,
         INTEGER,
         DECIMAL,
         BOOLEAN;
      }
   }
}
