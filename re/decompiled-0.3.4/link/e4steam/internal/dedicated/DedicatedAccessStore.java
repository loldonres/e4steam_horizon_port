package link.e4steam.internal.dedicated;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

final class DedicatedAccessStore {
   private static final int MAX_ENTRIES = 4096;
   private final Object lock = new Object();
   private final Path path;
   private final LinkedHashSet<Long> whitelist = new LinkedHashSet<>();
   private final LinkedHashSet<Long> bans = new LinkedHashSet<>();
   private final LinkedHashSet<UUID> whitelistUuids = new LinkedHashSet<>();
   private final LinkedHashSet<UUID> bannedUuids = new LinkedHashSet<>();

   DedicatedAccessStore(Path path) {
      this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
      this.load();
   }

   boolean whitelisted(long steamId) {
      synchronized (this.lock) {
         return this.whitelist.contains(requireSteamId(steamId));
      }
   }

   boolean banned(long steamId) {
      synchronized (this.lock) {
         return this.bans.contains(requireSteamId(steamId));
      }
   }

   boolean whitelisted(UUID uuid) {
      synchronized (this.lock) {
         return this.whitelistUuids.contains(requireUuid(uuid));
      }
   }

   boolean banned(UUID uuid) {
      synchronized (this.lock) {
         return this.bannedUuids.contains(requireUuid(uuid));
      }
   }

   boolean setWhitelisted(long steamId, boolean present) {
      synchronized (this.lock) {
         boolean changed = update(this.whitelist, requireSteamId(steamId), present);
         if (changed) {
            this.save();
         }

         return changed;
      }
   }

   boolean setBanned(long steamId, boolean present) {
      synchronized (this.lock) {
         boolean changed = update(this.bans, requireSteamId(steamId), present);
         if (changed) {
            this.save();
         }

         return changed;
      }
   }

   boolean setWhitelisted(UUID uuid, boolean present) {
      synchronized (this.lock) {
         boolean changed = update(this.whitelistUuids, requireUuid(uuid), present);
         if (changed) {
            this.save();
         }

         return changed;
      }
   }

   boolean setBanned(UUID uuid, boolean present) {
      synchronized (this.lock) {
         boolean changed = update(this.bannedUuids, requireUuid(uuid), present);
         if (changed) {
            this.save();
         }

         return changed;
      }
   }

   int whitelistSize() {
      synchronized (this.lock) {
         return this.whitelist.size();
      }
   }

   int banSize() {
      synchronized (this.lock) {
         return this.bans.size();
      }
   }

   private void load() {
      synchronized (this.lock) {
         if (Files.exists(this.path, LinkOption.NOFOLLOW_LINKS)) {
            ensureSafeRegularFile(this.path);

            try {
               List<String> lines = Files.readAllLines(this.path, StandardCharsets.US_ASCII);
               if (lines.size() > 16400) {
                  throw new IllegalStateException("Dedicated access file is too large");
               }

               for (String line : lines) {
                  String value = line.trim();
                  if (!value.isEmpty() && !value.startsWith("#")) {
                     int separator = value.indexOf(61);
                     if (separator < 1 || separator == value.length() - 1) {
                        throw new IllegalStateException("Invalid dedicated access entry");
                     }

                     String kind = value.substring(0, separator);
                     String identity = value.substring(separator + 1);
                     if (!"allow".equals(kind) && !"ban".equals(kind)) {
                        if (!"allow-uuid".equals(kind) && !"ban-uuid".equals(kind)) {
                           throw new IllegalStateException("Unknown dedicated access entry");
                        }

                        UUID uuid;
                        try {
                           uuid = UUID.fromString(identity);
                        } catch (IllegalArgumentException var13) {
                           throw new IllegalStateException("Invalid dedicated access UUID", var13);
                        }

                        Set<UUID> target = "allow-uuid".equals(kind) ? this.whitelistUuids : this.bannedUuids;
                        addLoaded(target, uuid);
                     } else {
                        long steamId;
                        try {
                           steamId = Long.parseUnsignedLong(identity);
                        } catch (NumberFormatException var14) {
                           throw new IllegalStateException("Invalid dedicated access SteamID", var14);
                        }

                        requireSteamId(steamId);
                        Set<Long> target = "allow".equals(kind) ? this.whitelist : this.bans;
                        addLoaded(target, steamId);
                     }
                  }
               }
            } catch (IOException var15) {
               throw new IllegalStateException("Could not read dedicated access policy", var15);
            }
         }
      }
   }

   private void save() {
      Path parent = this.path.getParent();
      if (parent == null) {
         throw new IllegalStateException("Dedicated access path has no parent");
      } else {
         try {
            Files.createDirectories(parent);
            if (Files.isSymbolicLink(parent)) {
               throw new SecurityException("Dedicated access directory cannot be a symbolic link");
            } else {
               if (Files.exists(this.path, LinkOption.NOFOLLOW_LINKS)) {
                  ensureSafeRegularFile(this.path);
               }

               Path temporary = Files.createTempFile(parent, ".e4steam-access-", ".tmp");

               try {
                  applyOwnerOnlyPermissions(temporary);
                  ArrayList<String> lines = new ArrayList<>();
                  lines.add("# e4steam dedicated access policy v1");

                  for (Long value : this.whitelist) {
                     lines.add("allow=" + Long.toUnsignedString(value));
                  }

                  for (Long value : this.bans) {
                     lines.add("ban=" + Long.toUnsignedString(value));
                  }

                  for (UUID value : this.whitelistUuids) {
                     lines.add("allow-uuid=" + value);
                  }

                  for (UUID value : this.bannedUuids) {
                     lines.add("ban-uuid=" + value);
                  }

                  Files.write(temporary, lines, StandardCharsets.US_ASCII, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

                  try {
                     Files.move(temporary, this.path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                  } catch (AtomicMoveNotSupportedException var10) {
                     Files.move(temporary, this.path, StandardCopyOption.REPLACE_EXISTING);
                  }

                  applyOwnerOnlyPermissions(this.path);
               } finally {
                  Files.deleteIfExists(temporary);
               }
            }
         } catch (IOException var12) {
            throw new IllegalStateException("Could not persist dedicated access policy", var12);
         }
      }
   }

   private static <T> boolean update(Set<T> values, T identity, boolean present) {
      if (present) {
         if (values.size() >= 4096 && !values.contains(identity)) {
            throw new IllegalStateException("Dedicated access policy is full");
         } else {
            return values.add(identity);
         }
      } else {
         return values.remove(identity);
      }
   }

   private static <T> void addLoaded(Set<T> target, T value) {
      if (target.size() >= 4096 || !target.add(value)) {
         throw new IllegalStateException("Duplicate or excessive dedicated access entry");
      }
   }

   private static long requireSteamId(long value) {
      if (value == 0L) {
         throw new IllegalArgumentException("SteamID must be non-zero");
      } else {
         return value;
      }
   }

   private static UUID requireUuid(UUID value) {
      return Objects.requireNonNull(value, "uuid");
   }

   private static void ensureSafeRegularFile(Path value) {
      if (Files.isSymbolicLink(value) || !Files.isRegularFile(value, LinkOption.NOFOLLOW_LINKS)) {
         throw new SecurityException("Dedicated access policy must be a regular file");
      }
   }

   private static void applyOwnerOnlyPermissions(Path value) {
      try {
         Files.setPosixFilePermissions(value, new LinkedHashSet<>(Arrays.asList(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)));
      } catch (IOException | UnsupportedOperationException var2) {
      }
   }
}
