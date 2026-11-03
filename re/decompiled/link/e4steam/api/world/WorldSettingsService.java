package link.e4steam.api.world;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Map.Entry;
import java.util.concurrent.CompletionStage;
import java.util.regex.Pattern;
import link.e4steam.api.ApiResult;
import link.e4steam.api.ApiValidation;

public interface WorldSettingsService {
   ApiResult<WorldSettingsService.WorldSettingsSchema> schema();

   ApiResult<WorldSettingsService.WorldSettingsSnapshot> snapshot();

   CompletionStage<ApiResult<WorldSettingsService.WorldSettingsPlan>> plan(WorldSettingsService.WorldSettingsProposal var1);

   CompletionStage<ApiResult<WorldSettingsService.WorldSettingsSnapshot>> apply(WorldSettingsService.WorldSettingsPlan var1, boolean var2);

   static Map<WorldSettingsService.WorldSettingKey, WorldSettingsService.WorldSettingValue> immutable(
      Map<WorldSettingsService.WorldSettingKey, WorldSettingsService.WorldSettingValue> values
   ) {
      if (values != null && values.size() <= 256) {
         LinkedHashMap<WorldSettingsService.WorldSettingKey, WorldSettingsService.WorldSettingValue> copy = new LinkedHashMap<>();

         for (Entry<WorldSettingsService.WorldSettingKey, WorldSettingsService.WorldSettingValue> entry : values.entrySet()) {
            WorldSettingsService.WorldSettingKey key = Objects.requireNonNull(entry.getKey(), "key");
            WorldSettingsService.WorldSettingValue value = Objects.requireNonNull(entry.getValue(), "value");
            if (key.type() != value.type()) {
               throw new IllegalArgumentException("setting type mismatch");
            }

            copy.put(key, value);
         }

         return Collections.unmodifiableMap(copy);
      } else {
         throw new IllegalArgumentException("invalid settings map");
      }
   }

   public static enum ApplyTiming {
      IMMEDIATE,
      NEXT_WORLD_START,
      RESTART_REQUIRED;
   }

   public static enum SettingType {
      STRING,
      INTEGER,
      BOOLEAN,
      ENUM;
   }

   public static final class WorldSettingKey {
      private static final Pattern FORMAT = Pattern.compile("^[a-z][a-z0-9_.-]{0,31}:[a-z][a-z0-9_.-]{0,63}$");
      private final String value;
      private final WorldSettingsService.SettingType type;

      public WorldSettingKey(String value, WorldSettingsService.SettingType type) {
         this.value = ApiValidation.identifier(value, "worldSettingKey", FORMAT);
         this.type = Objects.requireNonNull(type, "type");
         String normalized = this.value.toLowerCase(Locale.ROOT);
         String[] forbidden = new String[]{
            "online-mode", "authentication", "owner", "hostidentity", "server-ip", "server-port", "bind", "appid", "steam", "ban-disable", "ratelimit-disable"
         };

         for (String candidate : forbidden) {
            if (normalized.contains(candidate)) {
               throw new IllegalArgumentException("security/network world setting is forbidden");
            }
         }
      }

      public String value() {
         return this.value;
      }

      public WorldSettingsService.SettingType type() {
         return this.type;
      }

      @Override
      public boolean equals(Object other) {
         return this == other
            || other instanceof WorldSettingsService.WorldSettingKey
               && this.value.equals(((WorldSettingsService.WorldSettingKey)other).value)
               && this.type == ((WorldSettingsService.WorldSettingKey)other).type;
      }

      @Override
      public int hashCode() {
         return 31 * this.value.hashCode() + this.type.hashCode();
      }

      @Override
      public String toString() {
         return this.value;
      }
   }

   public static final class WorldSettingRule {
      private final WorldSettingsService.WorldSettingKey key;
      private final WorldSettingsService.ApplyTiming timing;
      private final boolean confirmationRequired;
      private final List<String> allowedValues;

      public WorldSettingRule(
         WorldSettingsService.WorldSettingKey key, WorldSettingsService.ApplyTiming timing, boolean confirmationRequired, List<String> allowedValues
      ) {
         this.key = Objects.requireNonNull(key, "key");
         this.timing = Objects.requireNonNull(timing, "timing");
         this.confirmationRequired = confirmationRequired;
         this.allowedValues = ApiValidation.immutableList(allowedValues, 128, "allowedValues");
      }

      public WorldSettingsService.WorldSettingKey key() {
         return this.key;
      }

      public WorldSettingsService.ApplyTiming timing() {
         return this.timing;
      }

      public boolean confirmationRequired() {
         return this.confirmationRequired;
      }

      public List<String> allowedValues() {
         return this.allowedValues;
      }
   }

   public static final class WorldSettingValue {
      private final WorldSettingsService.SettingType type;
      private final String encoded;

      private WorldSettingValue(WorldSettingsService.SettingType type, String encoded) {
         this.type = type;
         this.encoded = encoded;
      }

      public static WorldSettingsService.WorldSettingValue text(String value) {
         return new WorldSettingsService.WorldSettingValue(WorldSettingsService.SettingType.STRING, ApiValidation.text(value, "value", 256));
      }

      public static WorldSettingsService.WorldSettingValue enumValue(String value) {
         return new WorldSettingsService.WorldSettingValue(WorldSettingsService.SettingType.ENUM, ApiValidation.text(value, "value", 64));
      }

      public static WorldSettingsService.WorldSettingValue integer(int value) {
         return new WorldSettingsService.WorldSettingValue(WorldSettingsService.SettingType.INTEGER, Integer.toString(value));
      }

      public static WorldSettingsService.WorldSettingValue bool(boolean value) {
         return new WorldSettingsService.WorldSettingValue(WorldSettingsService.SettingType.BOOLEAN, Boolean.toString(value));
      }

      public WorldSettingsService.SettingType type() {
         return this.type;
      }

      public String encoded() {
         return this.encoded;
      }

      @Override
      public String toString() {
         return this.type != WorldSettingsService.SettingType.STRING && this.type != WorldSettingsService.SettingType.ENUM
            ? this.encoded
            : this.type + "{redacted-from-toString}";
      }
   }

   public static final class WorldSettingsPlan {
      private final String planId;
      private final Map<WorldSettingsService.WorldSettingKey, WorldSettingsService.WorldSettingValue> changes;
      private final WorldSettingsService.ApplyTiming timing;
      private final boolean confirmationRequired;

      public WorldSettingsPlan(
         String planId,
         Map<WorldSettingsService.WorldSettingKey, WorldSettingsService.WorldSettingValue> changes,
         WorldSettingsService.ApplyTiming timing,
         boolean confirmationRequired
      ) {
         this.planId = ApiValidation.text(planId, "planId", 96);
         ApiValidation.rejectSensitiveName(this.planId, "planId");
         this.changes = WorldSettingsService.immutable(changes);
         this.timing = Objects.requireNonNull(timing, "timing");
         this.confirmationRequired = confirmationRequired;
      }

      public String planId() {
         return this.planId;
      }

      public Map<WorldSettingsService.WorldSettingKey, WorldSettingsService.WorldSettingValue> changes() {
         return this.changes;
      }

      public WorldSettingsService.ApplyTiming timing() {
         return this.timing;
      }

      public boolean confirmationRequired() {
         return this.confirmationRequired;
      }

      @Override
      public String toString() {
         return "WorldSettingsPlan{id='" + this.planId + "', keys=" + this.changes.keySet() + ", timing=" + this.timing + '}';
      }
   }

   public static final class WorldSettingsProposal {
      private final Map<WorldSettingsService.WorldSettingKey, WorldSettingsService.WorldSettingValue> changes;

      public WorldSettingsProposal(Map<WorldSettingsService.WorldSettingKey, WorldSettingsService.WorldSettingValue> changes) {
         this.changes = WorldSettingsService.immutable(changes);
      }

      public Map<WorldSettingsService.WorldSettingKey, WorldSettingsService.WorldSettingValue> changes() {
         return this.changes;
      }
   }

   public static final class WorldSettingsSchema {
      private final int version;
      private final List<WorldSettingsService.WorldSettingRule> rules;

      public WorldSettingsSchema(int version, List<WorldSettingsService.WorldSettingRule> rules) {
         if (version < 1) {
            throw new IllegalArgumentException("invalid version");
         } else {
            this.version = version;
            this.rules = ApiValidation.immutableList(rules, 256, "rules");
         }
      }

      public int version() {
         return this.version;
      }

      public List<WorldSettingsService.WorldSettingRule> rules() {
         return this.rules;
      }
   }

   public static final class WorldSettingsSnapshot {
      private final int schemaVersion;
      private final Map<WorldSettingsService.WorldSettingKey, WorldSettingsService.WorldSettingValue> values;

      public WorldSettingsSnapshot(int schemaVersion, Map<WorldSettingsService.WorldSettingKey, WorldSettingsService.WorldSettingValue> values) {
         if (schemaVersion < 1) {
            throw new IllegalArgumentException("invalid schemaVersion");
         } else {
            this.schemaVersion = schemaVersion;
            this.values = WorldSettingsService.immutable(values);
         }
      }

      public int schemaVersion() {
         return this.schemaVersion;
      }

      public Map<WorldSettingsService.WorldSettingKey, WorldSettingsService.WorldSettingValue> values() {
         return this.values;
      }
   }
}
