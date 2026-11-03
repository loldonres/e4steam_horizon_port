package link.e4steam.internal.api;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import link.e4steam.Agnos;
import link.e4steam.api.ApiErrorCode;
import link.e4steam.api.ApiResult;
import link.e4steam.api.addon.AddonId;
import link.e4steam.api.capability.Capabilities;
import link.e4steam.api.config.ConfigService;
import link.e4steam.api.storage.StorageService;

final class CoreConfigService implements ConfigService {
   private static final int MAGIC = 1161052998;
   private static final int FORMAT_VERSION = 1;
   private final AddonId owner;
   private final CoreCapabilityService capabilities;
   private final CoreStorageService persistence;
   private final Map<String, ConfigService.ConfigSchema> schemas = new LinkedHashMap<>();
   private final Map<String, ConfigService.ConfigSnapshot> snapshots = new LinkedHashMap<>();

   CoreConfigService(AddonId owner, CoreCapabilityService capabilities) {
      this(owner, capabilities, defaultRoot(owner));
   }

   CoreConfigService(AddonId owner, CoreCapabilityService capabilities, Path root) {
      this.owner = Objects.requireNonNull(owner, "owner");
      this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
      CoreCapabilityService storageCapabilities = new CoreCapabilityService(
         Collections.singleton(Capabilities.STORAGE_PRIVATE), Collections.singleton(Capabilities.STORAGE_PRIVATE)
      );
      this.persistence = new CoreStorageService(storageCapabilities, root);
   }

   @Override
   public synchronized ApiResult<ConfigService.ConfigSnapshot> open(ConfigService.ConfigSchema schema, ConfigService.ConfigScope scope) {
      if (!this.capabilities.has(Capabilities.CONFIG_READ)) {
         return this.denied("config.open");
      } else if (schema != null && scope != null && this.owned(schema.id())) {
         ConfigService.ConfigSchema existing = this.schemas.get(schema.id());
         if (existing != null && existing.version() > schema.version()) {
            return SafeApiErrors.failure(ApiErrorCode.INCOMPATIBLE_VERSION, "config.open", "SchemaDowngrade");
         } else {
            this.schemas.put(schema.id(), schema);
            String memoryKey = key(schema.id(), scope);
            ConfigService.ConfigSnapshot current = this.snapshots.get(memoryKey);
            if (current != null) {
               return current.version() > schema.version()
                  ? SafeApiErrors.failure(ApiErrorCode.INCOMPATIBLE_VERSION, "config.open", "SchemaDowngrade")
                  : ApiResult.success(current);
            } else {
               ConfigService.ConfigSnapshot loaded = null;
               if (scope != ConfigService.ConfigScope.SESSION) {
                  ApiResult<StorageService.StoredValue> stored = this.persistence
                     .get(storageKey(schema.id(), scope, false), StorageService.StorageScope.GLOBAL)
                     .toCompletableFuture()
                     .join();
                  if (stored.isSuccess()) {
                     try {
                        loaded = decode(stored.value().get().bytes());
                     } catch (RuntimeException | IOException var10) {
                        return SafeApiErrors.failure(ApiErrorCode.UNAVAILABLE, "config.open", "CorruptConfig");
                     }
                  } else if (!"NotFound".equals(stored.error().get().causeCategory())) {
                     return ApiResult.failure(stored.error().get());
                  }
               }

               if (loaded == null || loaded.schemaId().equals(schema.id()) && loaded.scope() == scope) {
                  if (loaded != null && loaded.version() > schema.version()) {
                     return SafeApiErrors.failure(ApiErrorCode.INCOMPATIBLE_VERSION, "config.open", "SchemaDowngrade");
                  } else {
                     ApiResult<ConfigService.ConfigSnapshot> normalized = this.normalize(schema, scope, loaded);
                     if (!normalized.isSuccess()) {
                        return normalized;
                     } else {
                        ConfigService.ConfigSnapshot snapshot = normalized.value().get();
                        if (scope != ConfigService.ConfigScope.SESSION
                           && (loaded == null || loaded.version() != schema.version() || !loaded.values().equals(snapshot.values()))) {
                           ApiResult<Boolean> persisted = this.persist(snapshot, false);
                           if (!persisted.isSuccess()) {
                              return ApiResult.failure(persisted.error().get());
                           }
                        }

                        this.snapshots.put(memoryKey, snapshot);
                        return ApiResult.success(snapshot);
                     }
                  }
               } else {
                  return SafeApiErrors.failure(ApiErrorCode.SECURITY_REJECTION, "config.open", "ConfigBinding");
               }
            }
         }
      } else {
         return SafeApiErrors.failure(ApiErrorCode.SECURITY_REJECTION, "config.open", "WrongOwnerNamespace");
      }
   }

   @Override
   public synchronized ApiResult<ConfigService.ConfigSnapshot> snapshot(String schemaId, ConfigService.ConfigScope scope) {
      if (!this.capabilities.has(Capabilities.CONFIG_READ)) {
         return this.denied("config.snapshot");
      } else if (schemaId != null && scope != null && this.owned(schemaId)) {
         ConfigService.ConfigSnapshot snapshot = this.snapshots.get(key(schemaId, scope));
         return snapshot == null ? SafeApiErrors.failure(ApiErrorCode.UNAVAILABLE, "config.snapshot", "NotOpen") : ApiResult.success(snapshot);
      } else {
         return SafeApiErrors.failure(ApiErrorCode.SECURITY_REJECTION, "config.snapshot", "WrongOwnerNamespace");
      }
   }

   @Override
   public synchronized CompletionStage<ApiResult<ConfigService.ConfigSnapshot>> update(ConfigService.ConfigTransaction transaction) {
      if (!this.capabilities.has(Capabilities.CONFIG_WRITE)) {
         return completed(this.denied("config.update"));
      } else if (transaction != null && this.owned(transaction.schemaId())) {
         ConfigService.ConfigSchema schema = this.schemas.get(transaction.schemaId());
         ConfigService.ConfigSnapshot current = this.snapshots.get(key(transaction.schemaId(), transaction.scope()));
         if (schema != null && current != null) {
            if (schema.version() != transaction.expectedVersion()) {
               return completed(SafeApiErrors.failure(ApiErrorCode.INCOMPATIBLE_VERSION, "config.update", "VersionMismatch"));
            } else {
               Map<String, ConfigService.ConfigKey> keys = schemaKeys(schema);
               LinkedHashMap<String, ConfigService.ConfigValue> next = new LinkedHashMap<>(current.values());

               for (Entry<String, ConfigService.ConfigValue> entry : transaction.replacements().entrySet()) {
                  ConfigService.ConfigKey configKey = keys.get(entry.getKey());
                  if (configKey == null || configKey.type() != entry.getValue().type()) {
                     return completed(SafeApiErrors.failure(ApiErrorCode.INVALID_ARGUMENT, "config.update", "KeyOrType"));
                  }

                  next.put(entry.getKey(), entry.getValue());
               }

               ConfigService.ConfigSnapshot updated = new ConfigService.ConfigSnapshot(schema.id(), schema.version(), transaction.scope(), next);
               if (transaction.scope() != ConfigService.ConfigScope.SESSION) {
                  ApiResult<Boolean> backup = this.persist(current, true);
                  if (!backup.isSuccess()) {
                     return completed(ApiResult.failure(backup.error().get()));
                  }

                  ApiResult<Boolean> persisted = this.persist(updated, false);
                  if (!persisted.isSuccess()) {
                     return completed(ApiResult.failure(persisted.error().get()));
                  }
               }

               this.snapshots.put(key(schema.id(), transaction.scope()), updated);
               return completed(ApiResult.success(updated));
            }
         } else {
            return completed(SafeApiErrors.failure(ApiErrorCode.UNAVAILABLE, "config.update", "NotOpen"));
         }
      } else {
         return completed(SafeApiErrors.failure(ApiErrorCode.SECURITY_REJECTION, "config.update", "WrongOwnerNamespace"));
      }
   }

   private ApiResult<ConfigService.ConfigSnapshot> normalize(
      ConfigService.ConfigSchema schema, ConfigService.ConfigScope scope, ConfigService.ConfigSnapshot loaded
   ) {
      LinkedHashMap<String, ConfigService.ConfigValue> values = new LinkedHashMap<>();
      if (loaded != null) {
         values.putAll(loaded.values());
      }

      Map<String, ConfigService.ConfigKey> known = schemaKeys(schema);

      for (Entry<String, ConfigService.ConfigValue> entry : values.entrySet()) {
         ConfigService.ConfigKey key = known.get(entry.getKey());
         if (key != null && key.type() != entry.getValue().type()) {
            return SafeApiErrors.failure(ApiErrorCode.INCOMPATIBLE_VERSION, "config.migrate", "ConfigTypeChanged");
         }
      }

      for (ConfigService.ConfigKey key : schema.keys()) {
         values.putIfAbsent(key.name(), key.defaultValue());
      }

      return ApiResult.success(new ConfigService.ConfigSnapshot(schema.id(), schema.version(), scope, values));
   }

   private ApiResult<Boolean> persist(ConfigService.ConfigSnapshot snapshot, boolean backup) {
      try {
         byte[] encoded = encode(snapshot);
         ApiResult<StorageService.QuotaSnapshot> stored = this.persistence
            .put(
               storageKey(snapshot.schemaId(), snapshot.scope(), backup),
               StorageService.StorageScope.GLOBAL,
               new StorageService.StoredValue(StorageService.StorageFormat.BINARY, 1, encoded)
            )
            .toCompletableFuture()
            .join();
         return stored.isSuccess() ? ApiResult.success(Boolean.TRUE) : ApiResult.failure(stored.error().get());
      } catch (IOException var5) {
         return SafeApiErrors.failure(ApiErrorCode.UNAVAILABLE, "config.persist", "ConfigIo");
      }
   }

   private static byte[] encode(ConfigService.ConfigSnapshot snapshot) throws IOException {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();

      try (DataOutputStream output = new DataOutputStream(bytes)) {
         output.writeInt(1161052998);
         output.writeByte(1);
         output.writeUTF(snapshot.schemaId());
         output.writeInt(snapshot.version());
         output.writeByte(snapshot.scope().ordinal());
         output.writeInt(snapshot.values().size());

         for (Entry<String, ConfigService.ConfigValue> entry : snapshot.values().entrySet()) {
            output.writeUTF(entry.getKey());
            output.writeByte(entry.getValue().type().ordinal());
            output.writeUTF(entry.getValue().encoded());
         }
      }

      return bytes.toByteArray();
   }

   private static ConfigService.ConfigSnapshot decode(byte[] encoded) throws IOException {
      try {
         ConfigService.ConfigSnapshot var14;
         try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (input.readInt() != 1161052998 || input.readUnsignedByte() != 1) {
               throw new IOException("invalid config header");
            }

            String schemaId = input.readUTF();
            int schemaVersion = input.readInt();
            int scopeIndex = input.readUnsignedByte();
            if (scopeIndex >= ConfigService.ConfigScope.values().length) {
               throw new IOException("invalid config scope");
            }

            int count = input.readInt();
            if (count < 0 || count > 256) {
               throw new IOException("invalid config entry count");
            }

            LinkedHashMap<String, ConfigService.ConfigValue> values = new LinkedHashMap<>();

            for (int index = 0; index < count; index++) {
               String name = input.readUTF();
               int typeIndex = input.readUnsignedByte();
               if (typeIndex >= ConfigService.ValueType.values().length) {
                  throw new IOException("invalid config type");
               }

               ConfigService.ConfigValue value = decodeValue(ConfigService.ValueType.values()[typeIndex], input.readUTF());
               if (values.put(name, value) != null) {
                  throw new IOException("duplicate config key");
               }
            }

            if (input.available() != 0) {
               throw new IOException("trailing config data");
            }

            var14 = new ConfigService.ConfigSnapshot(schemaId, schemaVersion, ConfigService.ConfigScope.values()[scopeIndex], values);
         }

         return var14;
      } catch (IllegalArgumentException var13) {
         throw new IOException("invalid config value", var13);
      }
   }

   private static ConfigService.ConfigValue decodeValue(ConfigService.ValueType type, String encoded) {
      switch (type) {
         case STRING:
            return ConfigService.ConfigValue.text(encoded);
         case INTEGER:
            return ConfigService.ConfigValue.integer(Integer.parseInt(encoded));
         case LONG:
            return ConfigService.ConfigValue.longValue(Long.parseLong(encoded));
         case DOUBLE:
            return ConfigService.ConfigValue.decimal(Double.parseDouble(encoded));
         case BOOLEAN:
            if (!"true".equals(encoded) && !"false".equals(encoded)) {
               throw new IllegalArgumentException("invalid boolean");
            }

            return ConfigService.ConfigValue.bool(Boolean.parseBoolean(encoded));
         default:
            throw new IllegalArgumentException("invalid config type");
      }
   }

   private static Map<String, ConfigService.ConfigKey> schemaKeys(ConfigService.ConfigSchema schema) {
      LinkedHashMap<String, ConfigService.ConfigKey> values = new LinkedHashMap<>();

      for (ConfigService.ConfigKey key : schema.keys()) {
         values.put(key.name(), key);
      }

      return values;
   }

   private boolean owned(String id) {
      return id.startsWith(this.owner.value().replace(':', '-') + ":");
   }

   private static StorageService.StorageKey storageKey(String schemaId, ConfigService.ConfigScope scope, boolean backup) {
      return new StorageService.StorageKey((backup ? "backup/" : "current/") + digest(schemaId + "|" + scope.name()));
   }

   private static String digest(String value) {
      try {
         byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
         StringBuilder text = new StringBuilder(64);

         for (byte item : digest) {
            text.append(String.format(Locale.ROOT, "%02x", item & 255));
         }

         return text.toString();
      } catch (NoSuchAlgorithmException var7) {
         throw new IllegalStateException("SHA-256 is unavailable", var7);
      }
   }

   private static Path defaultRoot(AddonId owner) {
      return Agnos.configDir().resolve("e4steam-addon-data").resolve(owner.value().replace(':', '_')).resolve("config");
   }

   private static String key(String id, ConfigService.ConfigScope scope) {
      return id + "|" + scope.name();
   }

   private static <T> CompletionStage<T> completed(T value) {
      return CompletableFuture.completedFuture(value);
   }

   private <T> ApiResult<T> denied(String operation) {
      return SafeApiErrors.failure(ApiErrorCode.CAPABILITY_DENIED, operation, "PolicyDenied");
   }
}
