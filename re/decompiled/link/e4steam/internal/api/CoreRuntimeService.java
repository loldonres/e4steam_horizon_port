package link.e4steam.internal.api;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import link.e4steam.E4steamConstants;
import link.e4steam.api.ApiConstants;
import link.e4steam.api.ApiResult;
import link.e4steam.api.dedicated.DedicatedServerService;
import link.e4steam.api.runtime.Architecture;
import link.e4steam.api.runtime.CompatibilityFlag;
import link.e4steam.api.runtime.LifecyclePhase;
import link.e4steam.api.runtime.Platform;
import link.e4steam.api.runtime.RuntimeMode;
import link.e4steam.api.runtime.RuntimeService;
import link.e4steam.api.runtime.RuntimeSnapshot;
import link.e4steam.api.runtime.SteamRuntimeState;
import link.e4steam.api.runtime.TransportCapability;
import link.e4steam.internal.dedicated.DedicatedServerController;
import link.e4steam.steam.SteamClientApiBridge;

final class CoreRuntimeService implements RuntimeService {
   private final RuntimeEnvironment environment;
   private final CompletableFuture<RuntimeSnapshot> readiness = new CompletableFuture<>();
   private volatile LifecyclePhase phase = LifecyclePhase.BOOTSTRAP;

   CoreRuntimeService(RuntimeEnvironment environment) {
      this.environment = environment;
   }

   @Override
   public RuntimeSnapshot snapshot() {
      SteamRuntimeState steamState;
      String failureCategory;
      if (this.environment.mode() == RuntimeMode.DEDICATED_SERVER) {
         DedicatedServerController controller = DedicatedServerController.current();
         ApiResult<DedicatedServerService.DedicatedServerSnapshot> result = controller == null ? null : controller.service().snapshot();
         DedicatedServerService.DedicatedServerSnapshot dedicated = result != null && result.value().isPresent() ? result.value().get() : null;
         DedicatedServerService.DedicatedServerState dedicatedState = dedicated == null ? DedicatedServerService.DedicatedServerState.OFF : dedicated.state();
         steamState = dedicatedSteamState(dedicatedState);
         failureCategory = dedicated == null ? "DEDICATED_BACKEND_UNAVAILABLE" : dedicated.failureCategory();
      } else {
         String status = SteamClientApiBridge.statusCode();

         try {
            steamState = SteamRuntimeState.valueOf(status.equals("RUNNING") ? "READY" : status);
         } catch (IllegalArgumentException var7) {
            steamState = SteamRuntimeState.FAILED;
         }

         failureCategory = SteamClientApiBridge.failureCategory();
      }

      Set<TransportCapability> transports;
      if (steamState != SteamRuntimeState.READY) {
         transports = Collections.emptySet();
      } else if (this.environment.mode() == RuntimeMode.DEDICATED_SERVER) {
         transports = Collections.unmodifiableSet(EnumSet.of(TransportCapability.RELIABLE_STREAM));
      } else {
         transports = Collections.unmodifiableSet(
            EnumSet.of(TransportCapability.RELIABLE_STREAM, TransportCapability.DATAGRAM, TransportCapability.LOBBY_INVITES)
         );
      }

      EnumSet<CompatibilityFlag> flags = EnumSet.of(CompatibilityFlag.LOADER_ADAPTER_PRESENT);
      if (this.environment.mode() != RuntimeMode.DEDICATED_SERVER && steamClientBackendAvailable(platform(), architecture())) {
         flags.add(CompatibilityFlag.STEAM_CLIENT_BACKEND_AVAILABLE);
      }

      if (this.environment.mode() == RuntimeMode.DEDICATED_SERVER && steamGameServerBackendAvailable(platform(), architecture())) {
         flags.add(CompatibilityFlag.DEDICATED_BACKEND_AVAILABLE);
      }

      if (this.environment.experimental()) {
         flags.add(CompatibilityFlag.EXPERIMENTAL_COMBINATION);
      }

      return new RuntimeSnapshot(
         ApiConstants.API_VERSION,
         E4steamConstants.modVersion(),
         4,
         platform(),
         architecture(),
         this.environment.mode(),
         this.environment.loader(),
         this.environment.minecraftVersion(),
         steamState,
         this.phase,
         transports,
         flags,
         failureCategory
      );
   }

   @Override
   public CompletionStage<RuntimeSnapshot> readiness() {
      return this.readiness.thenApply(snapshot -> (RuntimeSnapshot)snapshot);
   }

   void phase(LifecyclePhase next) {
      this.phase = Objects.requireNonNull(next, "next");
      RuntimeSnapshot snapshot = this.snapshot();
      if (snapshot.steamState() == SteamRuntimeState.READY && !this.readiness.isDone()) {
         this.readiness.complete(snapshot);
      }
   }

   void refreshReadiness() {
      RuntimeSnapshot snapshot = this.snapshot();
      if (snapshot.steamState() == SteamRuntimeState.READY && !this.readiness.isDone()) {
         this.readiness.complete(snapshot);
      }
   }

   private static Platform platform() {
      String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
      if (os.contains("windows")) {
         return Platform.WINDOWS;
      } else if (os.contains("linux")) {
         return Platform.LINUX;
      } else {
         return !os.contains("mac") && !os.contains("darwin") ? Platform.UNKNOWN : Platform.MACOS;
      }
   }

   private static Architecture architecture() {
      String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
      if (arch.equals("amd64") || arch.equals("x86_64") || arch.equals("x64")) {
         return Architecture.X86_64;
      } else {
         return !arch.equals("aarch64") && !arch.equals("arm64") ? Architecture.UNKNOWN : Architecture.ARM64;
      }
   }

   private static boolean steamClientBackendAvailable(Platform platform, Architecture architecture) {
      return platform != Platform.WINDOWS && platform != Platform.LINUX
         ? platform == Platform.MACOS && (architecture == Architecture.X86_64 || architecture == Architecture.ARM64)
         : architecture == Architecture.X86_64;
   }

   private static boolean steamGameServerBackendAvailable(Platform platform, Architecture architecture) {
      return steamClientBackendAvailable(platform, architecture);
   }

   private static SteamRuntimeState dedicatedSteamState(DedicatedServerService.DedicatedServerState state) {
      switch (state) {
         case OFF:
            return SteamRuntimeState.NEW;
         case CONFIG_VALIDATED:
         case NATIVES_READY:
         case STEAM_INITIALIZING:
         case STEAM_LOGGING_ON:
            return SteamRuntimeState.STARTING;
         case TRANSPORT_READY:
         case MINECRAFT_READY:
         case ACCEPTING:
            return SteamRuntimeState.READY;
         case DRAINING:
            return SteamRuntimeState.STOPPING;
         case STOPPED:
            return SteamRuntimeState.STOPPED;
         case FAILED:
         default:
            return SteamRuntimeState.FAILED;
      }
   }
}
