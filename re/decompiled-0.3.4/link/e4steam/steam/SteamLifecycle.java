package link.e4steam.steam;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

final class SteamLifecycle implements AutoCloseable {
   private final SteamApi api;
   private final SteamClientHealthMonitor healthMonitor;
   private SteamNativeLibraryLoader nativeLoader;
   private SteamProcessGuard.Lease processLease;
   private boolean librariesLoaded;
   private boolean initialized;

   SteamLifecycle(SteamApi api) {
      this(api, SteamClientHealthMonitor.forCurrentProcess());
   }

   SteamLifecycle(SteamApi api, SteamClientHealthMonitor healthMonitor) {
      this.api = api;
      this.healthMonitor = healthMonitor;
   }

   void start() throws IOException {
      if (!this.initialized) {
         SteamProcessGuard.Lease acquired = SteamProcessGuard.acquire(SteamProcessGuard.Context.CLIENT);

         try {
            this.processLease = acquired;
            if (!this.librariesLoaded) {
               this.nativeLoader = new SteamNativeLibraryLoader();
               if (!this.api.loadLibraries(this.nativeLoader)) {
                  throw new IOException("Could not load Steam native libraries: " + this.nativeLoader.failureDescription(), this.nativeLoader.failureCause());
               }

               this.librariesLoaded = true;
            }

            if (!this.api.init()) {
               throw new IOException(initializationFailureMessage(System.getProperty("os.name", "")));
            }
         } catch (IOException var3) {
            this.processLease = null;
            acquired.close();
            throw var3;
         } catch (Exception var4) {
            this.processLease = null;
            acquired.close();
            throw new IOException("SteamAPI_Init failed: " + var4.getMessage(), var4);
         }

         this.initialized = true;
         this.healthMonitor.reset();
      }
   }

   static String initializationFailureMessage(String osName) {
      String normalized = osName == null ? "" : osName.trim().toLowerCase(Locale.ROOT);
      String prefix = "SteamAPI_Init failed. Start Steam and sign in before launching Minecraft. ";
      if (normalized.contains("win")) {
         return prefix + "Run Steam and Minecraft at the same privilege level; do not run only one of them as administrator";
      } else if (normalized.contains("linux") || normalized.contains("nix") || normalized.contains("nux")) {
         return prefix + "Run Steam and the launcher as the same desktop user; a sandboxed launcher must be allowed to access the Steam installation";
      } else {
         return !normalized.contains("mac") && !normalized.contains("darwin")
            ? prefix + "Run Steam and Minecraft as the same operating-system user"
            : prefix + "Run Steam and the launcher as the same macOS user";
      }
   }

   void runCallbacks() {
      if (!this.initialized) {
         throw new IllegalStateException("Steam lifecycle is not running");
      } else {
         this.api.runCallbacks();
      }
   }

   boolean isRunning() {
      return this.initialized && this.api.isInitialized();
   }

   boolean isHealthy(long nowMillis) {
      return this.isRunning() && this.healthMonitor.isHealthy(nowMillis, this.api::isNativeSteamClientRunning);
   }

   Path steamApiPath() {
      if (this.initialized && this.nativeLoader != null) {
         return this.nativeLoader.steamApiPath();
      } else {
         throw new IllegalStateException("Steam lifecycle is not running");
      }
   }

   @Override
   public void close() {
      this.healthMonitor.reset();
      if (this.initialized) {
         this.initialized = false;
         this.api.shutdown();
      }

      SteamProcessGuard.Lease lease = this.processLease;
      this.processLease = null;
      if (lease != null) {
         lease.close();
      }
   }
}
