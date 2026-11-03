package link.e4steam.steam;

import com.codedisaster.steamworks.SteamLibraryLoader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import link.e4steam.HexCodec;

final class SteamNativeLibraryLoader implements SteamLibraryLoader {
   private static final String CACHE_DIRECTORY = ".e4steam-steam-natives";
   private static final int MAX_NATIVE_LIBRARY_BYTES = 67108864;
   private static final Object MATERIALIZATION_LOCK = new Object();
   private static final Set<String> ALLOWED_LIBRARY_NAMES = Collections.unmodifiableSet(
      new HashSet<>(
         Arrays.asList(
            "steam_api64.dll",
            "steamworks4j64.dll",
            "steamworks4j-server64.dll",
            "libsteam_api.so",
            "libsteamworks4j.so",
            "libsteamworks4j-server.so",
            "libsteam_api.dylib",
            "libsteamworks4j.dylib",
            "libsteamworks4j-server.dylib"
         )
      )
   );
   private static final Map<String, SteamNativeLibraryLoader.KnownNative> KNOWN_NATIVES;
   private final Map<String, SteamNativeLibraryLoader.VerifiedLibrary> libraries;
   private volatile Throwable failureCause;
   private volatile String failedLibrary;

   SteamNativeLibraryLoader() throws IOException {
      SteamNativeLibraryLoader.NativeNames names = nativeNames(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
      byte[] steamApi = readBundledLibrary(names.steamApi());
      byte[] steamworks4j = readBundledLibrary(names.steamworks4j());
      byte[] steamworks4jServer = readBundledLibrary(names.steamworks4jServer());
      validateKnownNative(names.steamApi(), steamApi);
      validateKnownNative(names.steamworks4j(), steamworks4j);
      validateKnownNative(names.steamworks4jServer(), steamworks4jServer);
      validateBundledBinary(names, steamApi);
      validateBundledBinary(names, steamworks4j);
      validateBundledBinary(names, steamworks4jServer);
      String fingerprint = fingerprint(names, steamApi, steamworks4j, steamworks4jServer);
      Path cache = createCacheDirectory(names.platformDirectory() + "-" + fingerprint);
      SteamNativeLibraryLoader.VerifiedLibrary steamApiLibrary = materialize(cache, names.steamApi(), steamApi);
      SteamNativeLibraryLoader.VerifiedLibrary steamworks4jLibrary = materialize(cache, names.steamworks4j(), steamworks4j);
      SteamNativeLibraryLoader.VerifiedLibrary steamworks4jServerLibrary = materialize(cache, names.steamworks4jServer(), steamworks4jServer);
      Map<String, SteamNativeLibraryLoader.VerifiedLibrary> resolvedLibraries = new HashMap<>();
      resolvedLibraries.put("steam_api", steamApiLibrary);
      resolvedLibraries.put("steamworks4j", steamworks4jLibrary);
      resolvedLibraries.put("steamworks4j-server", steamworks4jServerLibrary);
      this.libraries = Collections.unmodifiableMap(resolvedLibraries);
   }

   @Override
   public boolean loadLibrary(String libraryName) {
      SteamNativeLibraryLoader.VerifiedLibrary library = this.libraries.get(libraryName);
      if (library == null) {
         this.failedLibrary = "unexpected library";
         this.failureCause = safeFailure("Steam requested a native library outside the allowlist", null);
         return false;
      } else {
         try {
            validateForLoad(library);
            System.load(library.path().toString());
            return true;
         } catch (UnsatisfiedLinkError | SecurityException | IOException var4) {
            this.failedLibrary = library.path().getFileName().toString();
            this.failureCause = safeFailure("Verified native library could not be loaded", var4);
            return false;
         }
      }
   }

   Throwable failureCause() {
      return this.failureCause;
   }

   String failureDescription() {
      Throwable cause = this.failureCause;
      return cause == null
         ? "unknown native loading error"
         : (this.failedLibrary == null ? "native library" : this.failedLibrary) + " (" + cause.getMessage() + ")";
   }

   Path steamApiPath() {
      SteamNativeLibraryLoader.VerifiedLibrary library = this.libraries.get("steam_api");

      try {
         validateForLoad(library);
      } catch (IOException var3) {
         throw new IllegalStateException("Verified Steam native cache entry became unsafe");
      }

      return library.path();
   }

   static SteamNativeLibraryLoader.NativeNames nativeNames(String osName, String architecture) throws IOException {
      NativePlatform platform = NativePlatform.normalize(osName, architecture);
      if (platform.operatingSystem() == NativePlatform.OperatingSystem.WINDOWS) {
         return new SteamNativeLibraryLoader.NativeNames(platform, "steam_api64.dll", "steamworks4j64.dll", "steamworks4j-server64.dll");
      } else {
         return platform.operatingSystem() == NativePlatform.OperatingSystem.LINUX
            ? new SteamNativeLibraryLoader.NativeNames(platform, "libsteam_api.so", "libsteamworks4j.so", "libsteamworks4j-server.so")
            : new SteamNativeLibraryLoader.NativeNames(platform, "libsteam_api.dylib", "libsteamworks4j.dylib", "libsteamworks4j-server.dylib");
      }
   }

   static void validateBundledBinary(SteamNativeLibraryLoader.NativeNames names, byte[] content) throws IOException {
      if (content != null && content.length >= 8) {
         NativePlatform platform = names.platform();
         if (platform.operatingSystem() == NativePlatform.OperatingSystem.WINDOWS) {
            if (content[0] != 77 || content[1] != 90) {
               throw new IOException("Invalid Windows native binary");
            }
         } else if (platform.operatingSystem() == NativePlatform.OperatingSystem.LINUX) {
            if ((content[0] & 255) != 127 || content[1] != 69 || content[2] != 76 || content[3] != 70) {
               throw new IOException("Invalid Linux native binary");
            }
         } else if (!containsMachOSlice(content, platform.architecture())) {
            throw new IOException("macOS native binary does not contain the active JVM architecture");
         }
      } else {
         throw new IOException("Native binary header is truncated");
      }
   }

   static void validateKnownNative(String resourceName, byte[] content) throws IOException {
      SteamNativeLibraryLoader.KnownNative known = KNOWN_NATIVES.get(resourceName);
      if (known != null && content != null && content.length == known.size) {
         String actual = HexCodec.encode(sha256(content), 0, 32);
         if (!actual.equals(known.sha256)) {
            throw new IOException("Bundled Steam native hash does not match the pinned manifest");
         }
      } else {
         throw new IOException("Bundled Steam native does not match the pinned manifest");
      }
   }

   private static boolean containsMachOSlice(byte[] content, NativePlatform.Architecture architecture) throws IOException {
      int magic = readBigEndianInt(content, 0);
      int expectedCpu = architecture == NativePlatform.Architecture.X86_64 ? 16777223 : 16777228;
      if (magic != -889275714 && magic != -889275713) {
         int littleMagic = readLittleEndianInt(content, 0);
         if (littleMagic == -17958193) {
            return readLittleEndianInt(content, 4) == expectedCpu;
         } else {
            return magic == -17958193 ? readBigEndianInt(content, 4) == expectedCpu : false;
         }
      } else {
         int count = readBigEndianInt(content, 4);
         if (count >= 1 && count <= 32) {
            int stride = magic == -889275714 ? 20 : 32;
            if (content.length < 8 + count * stride) {
               throw new IOException("Truncated Mach-O slice table");
            } else {
               for (int index = 0; index < count; index++) {
                  if (readBigEndianInt(content, 8 + index * stride) == expectedCpu) {
                     return true;
                  }
               }

               return false;
            }
         } else {
            throw new IOException("Invalid Mach-O slice count");
         }
      }
   }

   private static int readBigEndianInt(byte[] content, int offset) throws IOException {
      if (offset >= 0 && offset <= content.length - 4) {
         return (content[offset] & 0xFF) << 24 | (content[offset + 1] & 0xFF) << 16 | (content[offset + 2] & 0xFF) << 8 | content[offset + 3] & 0xFF;
      } else {
         throw new IOException("Truncated native header");
      }
   }

   private static int readLittleEndianInt(byte[] content, int offset) throws IOException {
      if (offset >= 0 && offset <= content.length - 4) {
         return content[offset] & 0xFF | (content[offset + 1] & 0xFF) << 8 | (content[offset + 2] & 0xFF) << 16 | (content[offset + 3] & 0xFF) << 24;
      } else {
         throw new IOException("Truncated native header");
      }
   }

   private static Path createCacheDirectory(String versionDirectory) throws IOException {
      String userHomeValue = System.getProperty("user.home", "").trim();
      if (userHomeValue.isEmpty()) {
         throw new IOException("A private user home is required for the Steam native cache");
      } else {
         Path userHome = Paths.get(userHomeValue).toAbsolutePath().normalize();
         validateDirectoryChain(userHome);
         UserPrincipal owner = currentProcessOwner(userHome);
         Path root = ensurePrivateChildDirectory(userHome, ".e4steam-steam-natives", owner);
         validateOwnedDirectory(root, owner);
         return ensurePrivateChildDirectory(root, versionDirectory, owner);
      }
   }

   static Path createPrivateCacheDirectory(Path parent, String child) throws IOException {
      Path safeParent;
      try {
         safeParent = parent.toRealPath();
      } catch (IOException var4) {
         throw new IOException("Could not resolve the native cache parent safely");
      }

      validateDirectoryChain(safeParent);
      return ensurePrivateChildDirectory(safeParent, child, currentProcessOwner(safeParent));
   }

   private static Path ensurePrivateChildDirectory(Path parent, String child, UserPrincipal expectedOwner) throws IOException {
      if (!child.isEmpty() && !child.contains("/") && !child.contains("\\")) {
         validateDirectoryType(parent);
         Path directory = parent.resolve(child).normalize();
         if (!directory.getParent().equals(parent)) {
            throw new IOException("Native cache directory escaped its owner-controlled parent");
         } else {
            if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
               validateOwnedDirectory(directory, expectedOwner);
            } else {
               try {
                  Files.createDirectory(directory);
               } catch (IOException var5) {
                  if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                     throw new IOException("Could not create the private native cache directory");
                  }
               }

               validateOwnedDirectory(directory, expectedOwner);
            }

            enforcePrivateDirectoryPermissions(directory);
            validateOwnedDirectory(directory, expectedOwner);
            return directory;
         }
      } else {
         throw new IOException("Invalid native cache directory name");
      }
   }

   private static byte[] readBundledLibrary(String resourceName) throws IOException {
      byte[] var3;
      try (InputStream stream = SteamNativeLibraryLoader.class.getResourceAsStream("/" + resourceName)) {
         if (stream == null) {
            throw new IOException("Bundled Steam native library is missing: " + resourceName);
         }

         byte[] content = readBounded(stream, 67108864);
         if (content.length == 0) {
            throw new IOException("Bundled Steam native library is empty: " + resourceName);
         }

         var3 = content;
      }

      return var3;
   }

   private static byte[] readBounded(InputStream stream, int maximumBytes) throws IOException {
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int total = 0;

      int read;
      while ((read = stream.read(buffer)) >= 0) {
         if (read != 0) {
            total += read;
            if (total > maximumBytes) {
               throw new IOException("Bundled Steam native library exceeds the size limit");
            }

            output.write(buffer, 0, read);
         }
      }

      return output.toByteArray();
   }

   static SteamNativeLibraryLoader.VerifiedLibrary materialize(Path directory, String fileName, byte[] expected) throws IOException {
      if (!ALLOWED_LIBRARY_NAMES.contains(fileName)) {
         throw new IOException("Native library filename is outside the platform allowlist");
      } else if (expected.length != 0 && expected.length <= 67108864) {
         Path normalizedDirectory = directory.toAbsolutePath().normalize();
         validateDirectoryChain(normalizedDirectory);
         UserPrincipal owner = readOwner(normalizedDirectory);
         validateOwnedDirectory(normalizedDirectory, owner);
         Path target = normalizedDirectory.resolve(fileName).normalize();
         if (!target.getParent().equals(normalizedDirectory)) {
            throw new IOException("Native library filename escaped the cache directory");
         } else {
            byte[] expectedHash = sha256(expected);
            synchronized (MATERIALIZATION_LOCK) {
               return materializeWithProcessLock(normalizedDirectory, target, fileName, expected, expectedHash, owner);
            }
         }
      } else {
         throw new IOException("Bundled Steam native library has an invalid size");
      }
   }

   private static SteamNativeLibraryLoader.VerifiedLibrary materializeWithProcessLock(
      Path directory, Path target, String fileName, byte[] expected, byte[] expectedHash, UserPrincipal owner
   ) throws IOException {
      Path lockPath = directory.resolve(fileName + ".lock").normalize();
      if (!lockPath.getParent().equals(directory)) {
         throw new IOException("Native cache lock escaped its owner-controlled directory");
      } else {
         Set<OpenOption> lockOptions = new HashSet<>();
         lockOptions.add(StandardOpenOption.CREATE);
         lockOptions.add(StandardOpenOption.WRITE);
         lockOptions.add(LinkOption.NOFOLLOW_LINKS);

         SteamNativeLibraryLoader.VerifiedLibrary var10;
         try (
            FileChannel lockChannel = FileChannel.open(lockPath, lockOptions);
            FileLock ignored = lockChannel.lock();
         ) {
            validateRegularOwnedFile(lockPath, owner);
            enforcePrivateFilePermissions(lockPath);
            var10 = materializeLocked(target, expected, expectedHash, owner);
         }

         return var10;
      }
   }

   private static SteamNativeLibraryLoader.VerifiedLibrary materializeLocked(Path target, byte[] expected, byte[] expectedHash, UserPrincipal owner) throws IOException {
      if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
         validateNativeFile(target, expected.length, expectedHash, owner);
         enforcePrivateFilePermissions(target);
         return new SteamNativeLibraryLoader.VerifiedLibrary(target, (long)expected.length, expectedHash, owner);
      } else {
         Path temporary = Files.createTempFile(target.getParent(), target.getFileName().toString() + ".", ".tmp");

         try {
            validateRegularOwnedFile(temporary, owner);
            writeNoFollow(temporary, expected);
            validateNativeFile(temporary, expected.length, expectedHash, owner);
            enforcePrivateFilePermissions(temporary);

            try {
               try {
                  Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
               } catch (AtomicMoveNotSupportedException var10) {
                  Files.move(temporary, target);
               }
            } catch (IOException var11) {
               if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                  throw new IOException("Could not publish the verified native cache entry");
               }

               validateNativeFile(target, expected.length, expectedHash, owner);
            }
         } finally {
            Files.deleteIfExists(temporary);
         }

         validateNativeFile(target, expected.length, expectedHash, owner);
         enforcePrivateFilePermissions(target);
         return new SteamNativeLibraryLoader.VerifiedLibrary(target, (long)expected.length, expectedHash, owner);
      }
   }

   private static void writeNoFollow(Path target, byte[] content) throws IOException {
      Set<OpenOption> options = new HashSet<>();
      options.add(StandardOpenOption.WRITE);
      options.add(StandardOpenOption.TRUNCATE_EXISTING);
      options.add(LinkOption.NOFOLLOW_LINKS);

      try (SeekableByteChannel channel = Files.newByteChannel(target, options)) {
         ByteBuffer buffer = ByteBuffer.wrap(content);

         while (buffer.hasRemaining()) {
            channel.write(buffer);
         }
      }
   }

   static void validateForLoad(SteamNativeLibraryLoader.VerifiedLibrary library) throws IOException {
      if (library == null) {
         throw new IOException("Native library is unavailable");
      } else {
         validateDirectoryChain(library.path().getParent());
         validateNativeFile(library.path(), library.size(), library.sha256(), library.owner());
      }
   }

   private static void validateDirectoryChain(Path directory) throws IOException {
      Path absolute = directory.toAbsolutePath().normalize();
      Path current = absolute.getRoot();
      if (current == null) {
         throw new IOException("Native cache path is not absolute");
      } else {
         validateDirectoryType(current);

         for (Path component : absolute) {
            current = current.resolve(component);
            validateDirectoryType(current);
         }
      }
   }

   private static void validateDirectoryType(Path directory) throws IOException {
      BasicFileAttributes attributes;
      try {
         attributes = Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      } catch (IOException var3) {
         throw new IOException("Native cache path contains an unavailable component");
      }

      if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()) {
         throw new IOException("Native cache path contains a link or non-directory component");
      }
   }

   private static void validateOwnedDirectory(Path directory, UserPrincipal expectedOwner) throws IOException {
      validateDirectoryType(directory);
      validateOwner(directory, expectedOwner);
   }

   private static void validateRegularOwnedFile(Path path, UserPrincipal expectedOwner) throws IOException {
      BasicFileAttributes attributes;
      try {
         attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      } catch (IOException var4) {
         throw new IOException("Native cache entry could not be inspected safely");
      }

      if (attributes.isRegularFile() && !attributes.isSymbolicLink() && !attributes.isOther()) {
         validateOwner(path, expectedOwner);
         rejectHardLinks(path);
      } else {
         throw new IOException("Native cache entry is not a regular no-follow file");
      }
   }

   private static void validateNativeFile(Path path, long expectedSize, byte[] expectedHash, UserPrincipal expectedOwner) throws IOException {
      validateRegularOwnedFile(path, expectedOwner);
      BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (attributes.size() == expectedSize && expectedSize <= 67108864L) {
         byte[] actualHash = hashNoFollow(path, expectedSize);
         if (!MessageDigest.isEqual(actualHash, expectedHash)) {
            throw new IOException("Native cache entry hash does not match the bundled library");
         } else {
            validateRegularOwnedFile(path, expectedOwner);
         }
      } else {
         throw new IOException("Native cache entry size does not match the bundled library");
      }
   }

   private static byte[] hashNoFollow(Path path, long expectedSize) throws IOException {
      MessageDigest digest = newSha256();
      Set<OpenOption> options = new HashSet<>();
      options.add(StandardOpenOption.READ);
      options.add(LinkOption.NOFOLLOW_LINKS);
      long total = 0L;
      ByteBuffer buffer = ByteBuffer.allocate(8192);

      int read;
      try (SeekableByteChannel channel = Files.newByteChannel(path, options)) {
         while ((read = channel.read(buffer)) >= 0) {
            if (read != 0) {
               total += read;
               if (total > expectedSize || total > 67108864L) {
                  throw new IOException("Native cache entry changed while being verified");
               }

               buffer.flip();
               digest.update(buffer);
               buffer.clear();
            }
         }
      }

      if (total != expectedSize) {
         throw new IOException("Native cache entry changed while being verified");
      } else {
         return digest.digest();
      }
   }

   private static void rejectHardLinks(Path path) throws IOException {
      try {
         Object links = Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
         if (links instanceof Number && ((Number)links).longValue() != 1L) {
            throw new IOException("Native cache entry has unexpected hard links");
         }
      } catch (UnsupportedOperationException var2) {
      }
   }

   private static UserPrincipal readOwner(Path path) throws IOException {
      try {
         return Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
      } catch (UnsupportedOperationException var2) {
         return null;
      }
   }

   private static UserPrincipal currentProcessOwner(Path controlledParent) throws IOException {
      Path probe;
      try {
         probe = Files.createTempFile(controlledParent, ".e4steam-owner-", ".tmp");
      } catch (IOException var7) {
         throw new IOException("Could not verify ownership of the native cache parent");
      }

      UserPrincipal exception;
      try {
         exception = readOwner(probe);
      } finally {
         Files.deleteIfExists(probe);
      }

      return exception;
   }

   private static void validateOwner(Path path, UserPrincipal expectedOwner) throws IOException {
      if (expectedOwner != null) {
         UserPrincipal actualOwner = readOwner(path);
         if (actualOwner == null || !expectedOwner.equals(actualOwner)) {
            throw new IOException("Native cache entry is owned by another account");
         }
      }
   }

   private static void enforcePrivateDirectoryPermissions(Path directory) throws IOException {
      PosixFileAttributeView view = Files.getFileAttributeView(directory, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
      if (view != null) {
         view.setPermissions(EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
      }
   }

   private static void enforcePrivateFilePermissions(Path target) throws IOException {
      PosixFileAttributeView view = Files.getFileAttributeView(target, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
      if (view != null) {
         view.setPermissions(EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
      }
   }

   static IOException safeFailure(String message, Throwable cause) {
      String type = cause == null ? "rejected" : cause.getClass().getSimpleName();
      return new IOException(message + ": " + type);
   }

   private static byte[] sha256(byte[] content) throws IOException {
      MessageDigest digest = newSha256();
      return digest.digest(content);
   }

   private static MessageDigest newSha256() throws IOException {
      try {
         return MessageDigest.getInstance("SHA-256");
      } catch (NoSuchAlgorithmException var1) {
         throw new IOException("SHA-256 is unavailable");
      }
   }

   private static String fingerprint(SteamNativeLibraryLoader.NativeNames names, byte[] steamApi, byte[] steamworks4j, byte[] steamworks4jServer) throws IOException {
      MessageDigest digest = newSha256();
      digest.update(names.platformDirectory().getBytes(StandardCharsets.US_ASCII));
      digest.update(steamApi);
      digest.update(steamworks4j);
      digest.update(steamworks4jServer);
      return HexCodec.encode(digest.digest(), 0, 12);
   }

   static {
      Map<String, SteamNativeLibraryLoader.KnownNative> known = new HashMap<>();
      known.put("steam_api64.dll", new SteamNativeLibraryLoader.KnownNative(319584L, "e082bf5c9f881c822b1540a76b74f9d15e18019a73ebd206a559595badcb7f65"));
      known.put("steamworks4j64.dll", new SteamNativeLibraryLoader.KnownNative(310784L, "5b7e1860775001b47efcd0378c3de8dec4d633967b1c70b5e469f7488539deaa"));
      known.put(
         "steamworks4j-server64.dll", new SteamNativeLibraryLoader.KnownNative(152064L, "0c7e676870bcf33d4a4b5269931d670bed2890e39e115656dfbd680533ec4b29")
      );
      known.put("libsteam_api.so", new SteamNativeLibraryLoader.KnownNative(388288L, "0f2c41c20644503c17e13498203986493332fc8296dbd78493bc1fed352ec0cc"));
      known.put("libsteamworks4j.so", new SteamNativeLibraryLoader.KnownNative(310528L, "4d255292c9ee257087053f8b1c0f0ab5a8f831193d3e6f37a10e5c046d6e1886"));
      known.put(
         "libsteamworks4j-server.so", new SteamNativeLibraryLoader.KnownNative(92704L, "268d16c2fac4beda0f649ac5809a557cce50fd7662ad3de5733f8bd84a12afab")
      );
      known.put("libsteam_api.dylib", new SteamNativeLibraryLoader.KnownNative(415344L, "d828cbd6365daa2b16d3bd7dc2969c60e97f497c0996a6def0b7b174a7e19953"));
      known.put("libsteamworks4j.dylib", new SteamNativeLibraryLoader.KnownNative(681280L, "f596487a0edce464c21c551c1a29a29aedaf4965d0a21b6d58ab3a55bce5c97e"));
      known.put(
         "libsteamworks4j-server.dylib", new SteamNativeLibraryLoader.KnownNative(251248L, "2204191a95ca5d9a9da3f46cb420fc9cec9a68c92d7184e7d9e77ecf9807fad7")
      );
      KNOWN_NATIVES = Collections.unmodifiableMap(known);
   }

   private static final class KnownNative {
      private final long size;
      private final String sha256;

      private KnownNative(long size, String sha256) {
         this.size = size;
         this.sha256 = sha256;
      }
   }

   static final class NativeNames {
      private final NativePlatform platform;
      private final String steamApi;
      private final String steamworks4j;
      private final String steamworks4jServer;

      NativeNames(NativePlatform platform, String steamApi, String steamworks4j, String steamworks4jServer) {
         this.platform = platform;
         this.steamApi = steamApi;
         this.steamworks4j = steamworks4j;
         this.steamworks4jServer = steamworks4jServer;
      }

      NativePlatform platform() {
         return this.platform;
      }

      String platformDirectory() {
         return this.platform.directoryName();
      }

      String steamApi() {
         return this.steamApi;
      }

      String steamworks4j() {
         return this.steamworks4j;
      }

      String steamworks4jServer() {
         return this.steamworks4jServer;
      }
   }

   static final class VerifiedLibrary {
      private final Path path;
      private final long size;
      private final byte[] sha256;
      private final UserPrincipal owner;

      private VerifiedLibrary(Path path, long size, byte[] sha256, UserPrincipal owner) {
         this.path = path;
         this.size = size;
         this.sha256 = (byte[])sha256.clone();
         this.owner = owner;
      }

      Path path() {
         return this.path;
      }

      long size() {
         return this.size;
      }

      byte[] sha256() {
         return (byte[])this.sha256.clone();
      }

      UserPrincipal owner() {
         return this.owner;
      }
   }
}
