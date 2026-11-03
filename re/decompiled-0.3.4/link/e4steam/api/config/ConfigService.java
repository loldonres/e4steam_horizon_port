package link.e4steam.api.config;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Map.Entry;
import java.util.concurrent.CompletionStage;
import java.util.regex.Pattern;
import link.e4steam.api.ApiResult;
import link.e4steam.api.ApiValidation;

public interface ConfigService {
   ApiResult<ConfigService.ConfigSnapshot> open(ConfigService.ConfigSchema var1, ConfigService.ConfigScope var2);

   ApiResult<ConfigService.ConfigSnapshot> snapshot(String var1, ConfigService.ConfigScope var2);

   CompletionStage<ApiResult<ConfigService.ConfigSnapshot>> update(ConfigService.ConfigTransaction var1);

   static Map<String, ConfigService.ConfigValue> immutable(Map<String, ConfigService.ConfigValue> values) {
      if (values != null && values.size() <= 256) {
         LinkedHashMap<String, ConfigService.ConfigValue> copy = new LinkedHashMap<>();

         for (Entry<String, ConfigService.ConfigValue> entry : values.entrySet()) {
            String key = ApiValidation.text(entry.getKey(), "key", 64);
            ApiValidation.rejectSensitiveName(key, "key");
            copy.put(key, Objects.requireNonNull(entry.getValue(), "value"));
         }

         return Collections.unmodifiableMap(copy);
      } else {
         throw new IllegalArgumentException("invalid values");
      }
   }

   public static final class ConfigKey {
      private static final Pattern FORMAT = Pattern.compile("^[a-z][a-z0-9_.-]{0,63}$");
      private final String name;
      private final ConfigService.ValueType type;
      private final ConfigService.ConfigValue defaultValue;
      private final boolean sensitive;

      public ConfigKey(String name, ConfigService.ValueType type, ConfigService.ConfigValue defaultValue, boolean sensitive) {
         this.name = ApiValidation.identifier(name, "config key", FORMAT);
         ApiValidation.rejectSensitiveName(this.name, "config key");
         this.type = Objects.requireNonNull(type, "type");
         this.defaultValue = Objects.requireNonNull(defaultValue, "defaultValue");
         if (type != defaultValue.type()) {
            throw new IllegalArgumentException("default type mismatch");
         } else {
            this.sensitive = sensitive;
         }
      }

      public String name() {
         return this.name;
      }

      public ConfigService.ValueType type() {
         return this.type;
      }

      public ConfigService.ConfigValue defaultValue() {
         return this.defaultValue;
      }

      public boolean sensitive() {
         return this.sensitive;
      }
   }

   public static final class ConfigSchema {
      private final String id;
      private final int version;
      private final List<ConfigService.ConfigKey> keys;

      public ConfigSchema(String id, int version, List<ConfigService.ConfigKey> keys) {
         this.id = ApiValidation.text(id, "schema id", 96);
         if (version < 1) {
            throw new IllegalArgumentException("invalid version");
         } else {
            this.version = version;
            this.keys = ApiValidation.immutableList(keys, 256, "keys");
            HashSet<String> names = new HashSet<>();

            for (ConfigService.ConfigKey key : this.keys) {
               if (!names.add(key.name())) {
                  throw new IllegalArgumentException("duplicate config key");
               }
            }
         }
      }

      public String id() {
         return this.id;
      }

      public int version() {
         return this.version;
      }

      public List<ConfigService.ConfigKey> keys() {
         return this.keys;
      }
   }

   public static enum ConfigScope {
      GLOBAL,
      PROFILE,
      WORLD,
      SESSION;
   }

   public static final class ConfigSnapshot {
      private final String schemaId;
      private final int version;
      private final ConfigService.ConfigScope scope;
      private final Map<String, ConfigService.ConfigValue> values;

      public ConfigSnapshot(String schemaId, int version, ConfigService.ConfigScope scope, Map<String, ConfigService.ConfigValue> values) {
         this.schemaId = ApiValidation.text(schemaId, "schemaId", 96);
         if (version < 1) {
            throw new IllegalArgumentException("invalid version");
         } else {
            this.version = version;
            this.scope = Objects.requireNonNull(scope, "scope");
            this.values = ConfigService.immutable(values);
         }
      }

      public String schemaId() {
         return this.schemaId;
      }

      public int version() {
         return this.version;
      }

      public ConfigService.ConfigScope scope() {
         return this.scope;
      }

      public Map<String, ConfigService.ConfigValue> values() {
         return this.values;
      }

      @Override
      public String toString() {
         return "ConfigSnapshot{schema='" + this.schemaId + "', version=" + this.version + ", keys=" + this.values.keySet() + '}';
      }
   }

   public static final class ConfigTransaction {
      private final String schemaId;
      private final int expectedVersion;
      private final ConfigService.ConfigScope scope;
      private final Map<String, ConfigService.ConfigValue> replacements;

      public ConfigTransaction(String schemaId, int expectedVersion, ConfigService.ConfigScope scope, Map<String, ConfigService.ConfigValue> replacements) {
         this.schemaId = ApiValidation.text(schemaId, "schemaId", 96);
         if (expectedVersion < 1) {
            throw new IllegalArgumentException("invalid expectedVersion");
         } else {
            this.expectedVersion = expectedVersion;
            this.scope = Objects.requireNonNull(scope, "scope");
            this.replacements = ConfigService.immutable(replacements);
         }
      }

      public String schemaId() {
         return this.schemaId;
      }

      public int expectedVersion() {
         return this.expectedVersion;
      }

      public ConfigService.ConfigScope scope() {
         return this.scope;
      }

      public Map<String, ConfigService.ConfigValue> replacements() {
         return this.replacements;
      }
   }

   public static final class ConfigValue {
      private final ConfigService.ValueType type;
      private final String encoded;

      private ConfigValue(ConfigService.ValueType type, String encoded) {
         this.type = type;
         this.encoded = encoded;
      }

      public static ConfigService.ConfigValue text(String value) {
         return new ConfigService.ConfigValue(ConfigService.ValueType.STRING, ApiValidation.text(value, "value", 4096));
      }

      public static ConfigService.ConfigValue integer(int value) {
         return new ConfigService.ConfigValue(ConfigService.ValueType.INTEGER, Integer.toString(value));
      }

      public static ConfigService.ConfigValue longValue(long value) {
         return new ConfigService.ConfigValue(ConfigService.ValueType.LONG, Long.toString(value));
      }

      public static ConfigService.ConfigValue decimal(double value) {
         if (!Double.isNaN(value) && !Double.isInfinite(value)) {
            return new ConfigService.ConfigValue(ConfigService.ValueType.DOUBLE, Double.toString(value));
         } else {
            throw new IllegalArgumentException("value must be finite");
         }
      }

      public static ConfigService.ConfigValue bool(boolean value) {
         return new ConfigService.ConfigValue(ConfigService.ValueType.BOOLEAN, Boolean.toString(value));
      }

      public ConfigService.ValueType type() {
         return this.type;
      }

      public String encoded() {
         return this.encoded;
      }

      @Override
      public String toString() {
         return this.type == ConfigService.ValueType.STRING ? "STRING{redacted-from-toString}" : this.type + ":" + this.encoded;
      }
   }

   public interface Migration {
      ApiResult<ConfigService.ConfigSnapshot> migrate(ConfigService.ConfigSnapshot var1);
   }

   public static enum ValueType {
      STRING,
      INTEGER,
      LONG,
      DOUBLE,
      BOOLEAN;
   }
}
