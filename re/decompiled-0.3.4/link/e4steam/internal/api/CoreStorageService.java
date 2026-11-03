package link.e4steam.internal.api;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import link.e4steam.Agnos;
import link.e4steam.api.ApiErrorCode;
import link.e4steam.api.ApiResult;
import link.e4steam.api.addon.AddonId;
import link.e4steam.api.capability.Capabilities;
import link.e4steam.api.storage.StorageService;

final class CoreStorageService implements StorageService {
   private static final int MAGIC = 1161057092;
   private static final int FORMAT_VERSION = 1;
   private static final int MAX_FILE_OVERHEAD = 512;
   private static final long MAX_BYTES = 16777216L;
   private static final int MAX_ENTRIES = 1000;
   private final CoreCapabilityService capabilities;
   private final Path root;
   private final Map<StorageService.StorageScope, Map<StorageService.StorageKey, StorageService.StoredValue>> data = new EnumMap<>(
      StorageService.StorageScope.class
   );
   private final Set<StorageService.StorageScope> loaded = EnumSet.noneOf(StorageService.StorageScope.class);

   CoreStorageService(AddonId owner, CoreCapabilityService capabilities) {
      this(capabilities, defaultRoot(owner));
   }

   CoreStorageService(CoreCapabilityService capabilities, Path root) {
      this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
      this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();

      for (StorageService.StorageScope scope : StorageService.StorageScope.values()) {
         this.data.put(scope, new LinkedHashMap<>());
      }
   }

   @Override
   public synchronized CompletionStage<ApiResult<StorageService.StoredValue>> get(StorageService.StorageKey key, StorageService.StorageScope scope) {
      if (!this.allowed()) {
         return completed(this.denied("storage.get"));
      } else if (key != null && scope != null) {
         ApiResult<Boolean> ready = this.load(scope);
         if (!ready.isSuccess()) {
            return completed(ApiResult.failure(ready.error().get()));
         } else {
            StorageService.StoredValue value = this.data.get(scope).get(key);
            return completed(value == null ? SafeApiErrors.failure(ApiErrorCode.UNAVAILABLE, "storage.get", "NotFound") : ApiResult.success(copy(value)));
         }
      } else {
         return completed(invalid("storage.get"));
      }
   }

   @Override
   public synchronized CompletionStage<ApiResult<StorageService.QuotaSnapshot>> put(
      StorageService.StorageKey key, StorageService.StorageScope scope, StorageService.StoredValue value
   ) {
      if (!this.allowed()) {
         return completed(this.denied("storage.put"));
      } else if (key != null && scope != null && value != null) {
         ApiResult<Boolean> ready = this.load(scope);
         if (!ready.isSuccess()) {
            return completed(ApiResult.failure(ready.error().get()));
         } else {
            Map<StorageService.StorageKey, StorageService.StoredValue> map = this.data.get(scope);
            StorageService.StoredValue old = map.get(key);
            long bytes = used(map) - (old == null ? 0L : old.size()) + value.size();
            int entries = map.size() + (old == null ? 1 : 0);
            if (bytes <= 16777216L && entries <= 1000) {
               try {
                  Path directory = this.scopeDirectory(scope);
                  atomicWrite(directory, fileName(key), encode(key, value));
                  map.put(key, copy(value));
                  return completed(ApiResult.success(quotaValue(map)));
               } catch (IOException var11) {
                  return completed(ioFailure("storage.put", var11));
               }
            } else {
               return completed(SafeApiErrors.failure(ApiErrorCode.QUEUE_FULL, "storage.put", "Quota"));
            }
         }
      } else {
         return completed(invalid("storage.put"));
      }
   }

   @Override
   public synchronized CompletionStage<ApiResult<Boolean>> delete(StorageService.StorageKey key, StorageService.StorageScope scope) {
      if (!this.allowed()) {
         return completed(this.denied("storage.delete"));
      } else if (key != null && scope != null) {
         ApiResult<Boolean> ready = this.load(scope);
         if (!ready.isSuccess()) {
            return completed(ApiResult.failure(ready.error().get()));
         } else {
            try {
               Path target = confined(this.scopeDirectory(scope), fileName(key));
               if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                  requireRegularOwnedFile(target);
               }

               boolean removed = Files.deleteIfExists(target);
               this.data.get(scope).remove(key);
               return completed(ApiResult.success(removed));
            } catch (IOException var6) {
               return completed(ioFailure("storage.delete", var6));
            }
         }
      } else {
         return completed(invalid("storage.delete"));
      }
   }

   @Override
   public synchronized CompletionStage<ApiResult<List<StorageService.StorageKey>>> keys(StorageService.StorageScope scope, int limit, String cursor) {
      if (!this.allowed()) {
         return completed(this.denied("storage.keys"));
      } else if (scope != null && limit >= 1 && limit <= 100) {
         String after;
         try {
            after = cursor != null && !cursor.trim().isEmpty() ? new StorageService.StorageKey(cursor).value() : "";
         } catch (RuntimeException var10) {
            return completed(invalid("storage.keys"));
         }

         ApiResult<Boolean> ready = this.load(scope);
         if (!ready.isSuccess()) {
            return completed(ApiResult.failure(ready.error().get()));
         } else {
            ArrayList<StorageService.StorageKey> values = new ArrayList<>(this.data.get(scope).keySet());
            values.sort(Comparator.comparing(StorageService.StorageKey::value));
            ArrayList<StorageService.StorageKey> page = new ArrayList<>();

            for (StorageService.StorageKey value : values) {
               if (after.isEmpty() || value.value().compareTo(after) > 0) {
                  page.add(value);
                  if (page.size() == limit) {
                     break;
                  }
               }
            }

            return completed(ApiResult.success(Collections.unmodifiableList(page)));
         }
      } else {
         return completed(invalid("storage.keys"));
      }
   }

   @Override
   public synchronized ApiResult<StorageService.QuotaSnapshot> quota(StorageService.StorageScope scope) {
      if (!this.allowed()) {
         return this.denied("storage.quota");
      } else if (scope == null) {
         return invalid("storage.quota");
      } else {
         ApiResult<Boolean> ready = this.load(scope);
         return ready.isSuccess() ? ApiResult.success(quotaValue(this.data.get(scope))) : ApiResult.failure(ready.error().get());
      }
   }

   private ApiResult<Boolean> load(StorageService.StorageScope scope) {
      if (this.loaded.contains(scope)) {
         return ApiResult.success(Boolean.TRUE);
      } else {
         Map<StorageService.StorageKey, StorageService.StoredValue> values = this.data.get(scope);
         values.clear();

         try {
            Path directory = this.scopeDirectory(scope);

            try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.e4s")) {
               for (Path file : files) {
                  if (values.size() >= 1000) {
                     throw new IOException("storage entry limit exceeded");
                  }

                  requireRegularOwnedFile(file);
                  long size = Files.size(file);
                  if (size < 1L || size > 4194816L) {
                     throw new IOException("invalid storage file size");
                  }

                  CoreStorageService.StoredRecord record = decode(Files.readAllBytes(file));
                  if (!file.getFileName().toString().equals(fileName(record.key))) {
                     throw new IOException("storage filename does not match its key");
                  }

                  if (values.put(record.key, record.value) != null) {
                     throw new IOException("duplicate storage key");
                  }
               }
            }

            if (used(values) > 16777216L) {
               throw new IOException("storage byte quota exceeded");
            } else {
               this.loaded.add(scope);
               return ApiResult.success(Boolean.TRUE);
            }
         } catch (RuntimeException | IOException var12) {
            values.clear();
            return ioFailure("storage.load", var12);
         }
      }
   }

   private Path scopeDirectory(StorageService.StorageScope scope) throws IOException {
      Path parent = ensureOwnedDirectory(this.root.getParent(), this.root.getFileName().toString());
      return ensureOwnedDirectory(parent, scope.name().toLowerCase(Locale.ROOT));
   }

   private static Path defaultRoot(AddonId owner) {
      if (owner == null) {
         throw new NullPointerException("owner");
      } else {
         String safeOwner = owner.value().replace(':', '_');
         return Agnos.configDir().resolve("e4steam-addon-data").resolve(safeOwner).resolve("storage");
      }
   }

   private static Path ensureOwnedDirectory(Path parent, String child) throws IOException {
      if (parent == null) {
         throw new IOException("storage parent is unavailable");
      } else {
         Path normalizedParent = parent.toAbsolutePath().normalize();
         Files.createDirectories(normalizedParent);
         Path directory = confined(normalizedParent, child);
         if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(directory);
            restrict(directory);
         }

         BasicFileAttributes attributes = Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
         if (attributes.isDirectory() && !attributes.isSymbolicLink() && !attributes.isOther()) {
            return directory;
         } else {
            throw new IOException("storage directory is not an owned directory");
         }
      }
   }

   private static void requireRegularOwnedFile(Path file) throws IOException {
      BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (attributes.isRegularFile() && !attributes.isSymbolicLink() && !attributes.isOther()) {
         try {
            Object links = Files.getAttribute(file, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
            if (links instanceof Number && ((Number)links).longValue() != 1L) {
               throw new IOException("storage hard links are forbidden");
            }
         } catch (UnsupportedOperationException var3) {
         }
      } else {
         throw new IOException("storage entry is not a regular file");
      }
   }

   private static Path confined(Path parent, String child) throws IOException {
      Path target = parent.resolve(child).toAbsolutePath().normalize();
      if (!target.startsWith(parent.toAbsolutePath().normalize())) {
         throw new IOException("storage path escaped");
      } else {
         return target;
      }
   }

   private static byte[] encode(StorageService.StorageKey key, StorageService.StoredValue value) throws IOException {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream(value.size() + 128);

      try (DataOutputStream output = new DataOutputStream(bytes)) {
         output.writeInt(1161057092);
         output.writeByte(1);
         output.writeUTF(key.value());
         output.writeByte(value.format().ordinal());
         output.writeInt(value.schemaVersion());
         byte[] payload = value.bytes();
         output.writeInt(payload.length);
         output.write(payload);
      }

      return bytes.toByteArray();
   }

   private static CoreStorageService.StoredRecord decode(byte[] encoded) throws IOException {
      try {
         CoreStorageService.StoredRecord var7;
         try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (input.readInt() != 1161057092 || input.readUnsignedByte() != 1) {
               throw new IOException("invalid storage header");
            }

            StorageService.StorageKey key = new StorageService.StorageKey(input.readUTF());
            int format = input.readUnsignedByte();
            if (format >= StorageService.StorageFormat.values().length) {
               throw new IOException("invalid storage format");
            }

            int schemaVersion = input.readInt();
            int length = input.readInt();
            if (schemaVersion < 1 || length < 0 || length > 4194304 || length > input.available()) {
               throw new IOException("invalid storage bounds");
            }

            byte[] payload = new byte[length];
            input.readFully(payload);
            if (input.available() != 0) {
               throw new IOException("trailing storage data");
            }

            var7 = new CoreStorageService.StoredRecord(
               key, new StorageService.StoredValue(StorageService.StorageFormat.values()[format], schemaVersion, payload)
            );
         }

         return var7;
      } catch (IllegalArgumentException var10) {
         throw new IOException("invalid storage record", var10);
      }
   }

   private static void atomicWrite(Path directory, String name, byte[] encoded) throws IOException {
      Path target = confined(directory, name);
      Path temporary = Files.createTempFile(directory, ".e4steam-", ".tmp");
      boolean moved = false;

      try {
         restrict(temporary);
         Files.write(temporary, encoded, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

         try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
            channel.force(true);
         }

         try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
         } catch (AtomicMoveNotSupportedException var15) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
         }

         moved = true;
         requireRegularOwnedFile(target);
      } finally {
         if (!moved) {
            Files.deleteIfExists(temporary);
         }
      }
   }

   private static void restrict(Path path) throws IOException {
      try {
         Set<PosixFilePermission> permissions = EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
         if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
         }

         Files.setPosixFilePermissions(path, permissions);
      } catch (UnsupportedOperationException var2) {
      }
   }

   private static String fileName(StorageService.StorageKey key) {
      try {
         byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.value().getBytes(StandardCharsets.UTF_8));
         StringBuilder value = new StringBuilder(68);

         for (byte item : digest) {
            value.append(String.format(Locale.ROOT, "%02x", item & 255));
         }

         return value.append(".e4s").toString();
      } catch (NoSuchAlgorithmException var7) {
         throw new IllegalStateException("SHA-256 is unavailable", var7);
      }
   }

   private boolean allowed() {
      return this.capabilities.has(Capabilities.STORAGE_PRIVATE);
   }

   private <T> ApiResult<T> denied(String operation) {
      return SafeApiErrors.failure(ApiErrorCode.CAPABILITY_DENIED, operation, "PolicyDenied");
   }

   private static <T> ApiResult<T> invalid(String operation) {
      return SafeApiErrors.failure(ApiErrorCode.INVALID_ARGUMENT, operation, "Validation");
   }

   private static <T> ApiResult<T> ioFailure(String operation, Throwable failure) {
      return SafeApiErrors.failure(ApiErrorCode.UNAVAILABLE, operation, failure instanceof SecurityException ? "StorageSecurity" : "StorageIo");
   }

   private static StorageService.StoredValue copy(StorageService.StoredValue value) {
      return new StorageService.StoredValue(value.format(), value.schemaVersion(), value.bytes());
   }

   private static long used(Map<StorageService.StorageKey, StorageService.StoredValue> map) {
      long total = 0L;

      for (StorageService.StoredValue value : map.values()) {
         total += value.size();
      }

      return total;
   }

   private static StorageService.QuotaSnapshot quotaValue(Map<StorageService.StorageKey, StorageService.StoredValue> map) {
      return new StorageService.QuotaSnapshot(used(map), 16777216L, map.size(), 1000);
   }

   private static <T> CompletionStage<T> completed(T value) {
      return CompletableFuture.completedFuture(value);
   }

   private static final class StoredRecord {
      private final StorageService.StorageKey key;
      private final StorageService.StoredValue value;

      private StoredRecord(StorageService.StorageKey key, StorageService.StoredValue value) {
         this.key = key;
         this.value = value;
      }
   }
}
