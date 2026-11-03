package link.e4steam.steam;

import java.io.IOException;
import java.util.Locale;

final class NativePlatform {
   private final NativePlatform.OperatingSystem operatingSystem;
   private final NativePlatform.Architecture architecture;

   private NativePlatform(NativePlatform.OperatingSystem operatingSystem, NativePlatform.Architecture architecture) {
      this.operatingSystem = operatingSystem;
      this.architecture = architecture;
   }

   static NativePlatform normalize(String osName, String architecture) throws IOException {
      String os = normalizeAlias(osName);
      String arch = normalizeAlias(architecture);
      NativePlatform.OperatingSystem operatingSystem;
      if (isFamily(os, "windows")) {
         operatingSystem = NativePlatform.OperatingSystem.WINDOWS;
      } else if (isFamily(os, "linux")) {
         operatingSystem = NativePlatform.OperatingSystem.LINUX;
      } else {
         if (!isFamily(os, "mac os") && !isFamily(os, "macos") && !isFamily(os, "os x") && !isFamily(os, "darwin")) {
            throw new IOException("Unsupported operating system family");
         }

         operatingSystem = NativePlatform.OperatingSystem.MACOS;
      }

      NativePlatform.Architecture normalizedArchitecture;
      if (!arch.equals("amd64") && !arch.equals("x86_64") && !arch.equals("x64")) {
         if (!arch.equals("arm64") && !arch.equals("aarch64")) {
            throw new IOException("Unsupported 64-bit Steam native architecture");
         }

         normalizedArchitecture = NativePlatform.Architecture.ARM64;
      } else {
         normalizedArchitecture = NativePlatform.Architecture.X86_64;
      }

      if (operatingSystem != NativePlatform.OperatingSystem.MACOS && normalizedArchitecture != NativePlatform.Architecture.X86_64) {
         throw new IOException("This operating system currently requires an x86-64 Java runtime");
      } else {
         return new NativePlatform(operatingSystem, normalizedArchitecture);
      }
   }

   NativePlatform.OperatingSystem operatingSystem() {
      return this.operatingSystem;
   }

   NativePlatform.Architecture architecture() {
      return this.architecture;
   }

   String directoryName() {
      String os = this.operatingSystem == NativePlatform.OperatingSystem.WINDOWS
         ? "windows"
         : (this.operatingSystem == NativePlatform.OperatingSystem.LINUX ? "linux" : "macos");
      return os + "-" + (this.architecture == NativePlatform.Architecture.X86_64 ? "x64" : "arm64");
   }

   private static String normalizeAlias(String value) {
      return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('-', '_').replaceAll("\\s+", " ");
   }

   private static boolean isFamily(String normalized, String family) {
      return normalized.equals(family) || normalized.startsWith(family + " ");
   }

   static enum Architecture {
      X86_64,
      ARM64;
   }

   static enum OperatingSystem {
      WINDOWS,
      LINUX,
      MACOS;
   }
}
