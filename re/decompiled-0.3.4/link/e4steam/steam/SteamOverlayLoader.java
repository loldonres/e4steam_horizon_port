package link.e4steam.steam;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

final class SteamOverlayLoader {
   private SteamOverlayLoader() {
   }

   static Optional<Path> findOverlayLibrary() {
      NativePlatform platform;
      try {
         platform = NativePlatform.normalize(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
      } catch (IOException var2) {
         return Optional.empty();
      }

      String home = System.getProperty("user.home", "").trim();
      return home.isEmpty() ? Optional.empty() : findOverlayLibrary(platform, Paths.get(home));
   }

   static Optional<Path> findOverlayLibrary(NativePlatform platform, Path home) {
      if (platform != null && home != null) {
         String libraryName = libraryName(platform);
         if (libraryName == null) {
            return Optional.empty();
         } else {
            for (Path root : candidateSteamRoots(platform, home.toAbsolutePath().normalize())) {
               Optional<Path> candidate = usableRegularFile(root.resolve(libraryName));
               if (candidate.isPresent()) {
                  return candidate;
               }
            }

            return Optional.empty();
         }
      } else {
         return Optional.empty();
      }
   }

   static List<Path> candidateSteamRoots(NativePlatform platform, Path home) {
      if (platform != null && home != null) {
         List<Path> roots = new ArrayList<>();
         if (platform.operatingSystem() == NativePlatform.OperatingSystem.MACOS) {
            roots.add(home.resolve("Library/Application Support/Steam/Steam.AppBundle/Steam/Contents/MacOS"));
            return Collections.unmodifiableList(roots);
         } else if (platform.operatingSystem() == NativePlatform.OperatingSystem.LINUX && platform.architecture() == NativePlatform.Architecture.X86_64) {
            roots.add(home.resolve(".steam/steam/ubuntu12_64"));
            roots.add(home.resolve(".steam/root/ubuntu12_64"));
            roots.add(home.resolve(".local/share/Steam/ubuntu12_64"));
            roots.add(home.resolve(".var/app/com.valvesoftware.Steam/.steam/steam/ubuntu12_64"));
            roots.add(home.resolve(".var/app/com.valvesoftware.Steam/.local/share/Steam/ubuntu12_64"));
            roots.add(home.resolve("snap/steam/common/.steam/steam/ubuntu12_64"));
            roots.add(home.resolve("snap/steam/common/.local/share/Steam/ubuntu12_64"));
            return Collections.unmodifiableList(roots);
         } else {
            return Collections.emptyList();
         }
      } else {
         return Collections.emptyList();
      }
   }

   private static String libraryName(NativePlatform platform) {
      if (platform.operatingSystem() == NativePlatform.OperatingSystem.LINUX) {
         return "gameoverlayrenderer.so";
      } else {
         return platform.operatingSystem() == NativePlatform.OperatingSystem.MACOS ? "gameoverlayrenderer.dylib" : null;
      }
   }

   private static Optional<Path> usableRegularFile(Path candidate) {
      try {
         Path real = candidate.toRealPath();
         return Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS) && Files.isReadable(real) ? Optional.of(real) : Optional.empty();
      } catch (SecurityException | IOException var2) {
         return Optional.empty();
      }
   }
}
