package link.e4steam.steam;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;

final class SteamClientHealthMonitor {
   static final long DEFAULT_PROBE_INTERVAL_MILLIS = 5000L;
   static final int DEFAULT_FAILURE_THRESHOLD = 3;
   private final boolean nativeProbeEnabled;
   private final long probeIntervalMillis;
   private final int failureThreshold;
   private boolean probeScheduled;
   private long nextProbeMillis;
   private int consecutiveFailures;

   SteamClientHealthMonitor(boolean nativeProbeEnabled, long probeIntervalMillis, int failureThreshold) {
      if (probeIntervalMillis <= 0L) {
         throw new IllegalArgumentException("probeIntervalMillis must be positive");
      } else if (failureThreshold <= 0) {
         throw new IllegalArgumentException("failureThreshold must be positive");
      } else {
         this.nativeProbeEnabled = nativeProbeEnabled;
         this.probeIntervalMillis = probeIntervalMillis;
         this.failureThreshold = failureThreshold;
      }
   }

   static SteamClientHealthMonitor forCurrentProcess() {
      boolean flatpakMarker = false;
      Map<String, String> environment = Collections.emptyMap();

      try {
         flatpakMarker = Files.exists(Paths.get("/.flatpak-info"));
      } catch (SecurityException var4) {
      }

      try {
         environment = System.getenv();
      } catch (SecurityException var3) {
      }

      return new SteamClientHealthMonitor(shouldUseNativeProbe(System.getProperty("os.name", ""), environment, flatpakMarker), 5000L, 3);
   }

   static boolean shouldUseNativeProbe(String osName, Map<String, String> environment, boolean flatpakMarker) {
      String normalizedOs = osName == null ? "" : osName.trim().toLowerCase(Locale.ROOT);
      if (normalizedOs.contains("mac") || normalizedOs.contains("darwin")) {
         return false;
      } else {
         return !flatpakMarker
               && !hasValue(environment, "FLATPAK_ID")
               && !"flatpak".equalsIgnoreCase(value(environment, "container"))
               && !hasValue(environment, "SNAP")
               && !hasValue(environment, "SNAP_NAME")
            ? normalizedOs.contains("win") || normalizedOs.contains("linux") || normalizedOs.contains("nix") || normalizedOs.contains("nux")
            : false;
      }
   }

   private static boolean hasValue(Map<String, String> environment, String name) {
      return !value(environment, name).trim().isEmpty();
   }

   private static String value(Map<String, String> environment, String name) {
      if (environment == null) {
         return "";
      } else {
         String value = environment.get(name);
         return value == null ? "" : value;
      }
   }

   boolean isHealthy(long nowMillis, BooleanSupplier nativeProbe) {
      if (!this.nativeProbeEnabled) {
         return true;
      } else if (!this.probeScheduled) {
         this.probeScheduled = true;
         this.nextProbeMillis = saturatedAdd(nowMillis, this.probeIntervalMillis);
         return true;
      } else if (nowMillis < this.nextProbeMillis) {
         return true;
      } else {
         this.nextProbeMillis = saturatedAdd(nowMillis, this.probeIntervalMillis);
         if (nativeProbe.getAsBoolean()) {
            this.consecutiveFailures = 0;
            return true;
         } else {
            this.consecutiveFailures++;
            return this.consecutiveFailures < this.failureThreshold;
         }
      }
   }

   void reset() {
      this.probeScheduled = false;
      this.nextProbeMillis = 0L;
      this.consecutiveFailures = 0;
   }

   private static long saturatedAdd(long left, long right) {
      return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
   }
}
