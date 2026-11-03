package link.e4steam.api.storage;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.regex.Pattern;
import link.e4steam.api.ApiResult;
import link.e4steam.api.ApiValidation;

public interface StorageService {
   CompletionStage<ApiResult<StorageService.StoredValue>> get(StorageService.StorageKey var1, StorageService.StorageScope var2);

   CompletionStage<ApiResult<StorageService.QuotaSnapshot>> put(
      StorageService.StorageKey var1, StorageService.StorageScope var2, StorageService.StoredValue var3
   );

   CompletionStage<ApiResult<Boolean>> delete(StorageService.StorageKey var1, StorageService.StorageScope var2);

   CompletionStage<ApiResult<List<StorageService.StorageKey>>> keys(StorageService.StorageScope var1, int var2, String var3);

   ApiResult<StorageService.QuotaSnapshot> quota(StorageService.StorageScope var1);

   public static final class QuotaSnapshot {
      private final long usedBytes;
      private final long maximumBytes;
      private final int entries;
      private final int maximumEntries;

      public QuotaSnapshot(long usedBytes, long maximumBytes, int entries, int maximumEntries) {
         if (usedBytes >= 0L && maximumBytes >= 0L && usedBytes <= maximumBytes && entries >= 0 && maximumEntries >= 0 && entries <= maximumEntries) {
            this.usedBytes = usedBytes;
            this.maximumBytes = maximumBytes;
            this.entries = entries;
            this.maximumEntries = maximumEntries;
         } else {
            throw new IllegalArgumentException("invalid quota");
         }
      }

      public long usedBytes() {
         return this.usedBytes;
      }

      public long maximumBytes() {
         return this.maximumBytes;
      }

      public int entries() {
         return this.entries;
      }

      public int maximumEntries() {
         return this.maximumEntries;
      }
   }

   public static enum StorageFormat {
      BINARY,
      UTF8,
      JSON;
   }

   public static final class StorageKey {
      private static final Pattern FORMAT = Pattern.compile("^[a-z0-9][a-z0-9_.-]{0,63}(?:/[a-z0-9][a-z0-9_.-]{0,63}){0,7}$");
      private final String value;

      public StorageKey(String value) {
         this.value = ApiValidation.identifier(value, "storageKey", FORMAT);
         ApiValidation.rejectSensitiveName(this.value, "storageKey");
         if (this.value.contains("..")) {
            throw new IllegalArgumentException("path traversal is forbidden");
         }
      }

      public String value() {
         return this.value;
      }

      @Override
      public boolean equals(Object other) {
         return this == other || other instanceof StorageService.StorageKey && this.value.equals(((StorageService.StorageKey)other).value);
      }

      @Override
      public int hashCode() {
         return this.value.hashCode();
      }

      @Override
      public String toString() {
         return this.value;
      }
   }

   public static enum StorageScope {
      GLOBAL,
      WORLD;
   }

   public static final class StoredValue {
      private final StorageService.StorageFormat format;
      private final int schemaVersion;
      private final byte[] bytes;

      public StoredValue(StorageService.StorageFormat format, int schemaVersion, byte[] bytes) {
         this.format = Objects.requireNonNull(format, "format");
         if (schemaVersion < 1) {
            throw new IllegalArgumentException("invalid schemaVersion");
         } else {
            this.schemaVersion = schemaVersion;
            this.bytes = ApiValidation.bytes(bytes, 4194304, "bytes");
         }
      }

      public StorageService.StorageFormat format() {
         return this.format;
      }

      public int schemaVersion() {
         return this.schemaVersion;
      }

      public byte[] bytes() {
         return (byte[])this.bytes.clone();
      }

      public int size() {
         return this.bytes.length;
      }

      @Override
      public String toString() {
         return "StoredValue{format=" + this.format + ", version=" + this.schemaVersion + ", bytes=" + this.bytes.length + '}';
      }
   }
}
