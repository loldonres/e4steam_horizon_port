package link.e4steam.api;

import link.e4steam.api.access.AccessService;
import link.e4steam.api.command.CommandService;
import link.e4steam.api.config.ConfigService;
import link.e4steam.api.dedicated.DedicatedServerService;
import link.e4steam.api.diagnostics.DiagnosticsService;
import link.e4steam.api.directory.PublicDirectoryService;
import link.e4steam.api.identity.IdentityService;
import link.e4steam.api.lobby.LobbyService;
import link.e4steam.api.localization.LocalizationService;
import link.e4steam.api.logging.SafeLogger;
import link.e4steam.api.modpack.ModpackService;
import link.e4steam.api.network.NetworkService;
import link.e4steam.api.session.SessionService;
import link.e4steam.api.skin.SkinService;
import link.e4steam.api.storage.StorageService;
import link.e4steam.api.udp.UdpService;
import link.e4steam.api.ui.UiService;
import link.e4steam.api.world.WorldSettingsService;

public final class ApiServiceKeys {
   public static final ServiceKey<IdentityService> IDENTITIES = key("identities", IdentityService.class);
   public static final ServiceKey<SessionService> SESSIONS = key("sessions", SessionService.class);
   public static final ServiceKey<DedicatedServerService> DEDICATED = key("dedicated", DedicatedServerService.class);
   public static final ServiceKey<AccessService> ACCESS = key("access", AccessService.class);
   public static final ServiceKey<LobbyService> LOBBIES = key("lobbies", LobbyService.class);
   public static final ServiceKey<NetworkService> NETWORK = key("network", NetworkService.class);
   public static final ServiceKey<UdpService> UDP = key("udp", UdpService.class);
   public static final ServiceKey<UiService> UI = key("ui", UiService.class);
   public static final ServiceKey<CommandService> COMMANDS = key("commands", CommandService.class);
   public static final ServiceKey<ConfigService> CONFIG = key("config", ConfigService.class);
   public static final ServiceKey<StorageService> STORAGE = key("storage", StorageService.class);
   public static final ServiceKey<WorldSettingsService> WORLD_SETTINGS = key("world_settings", WorldSettingsService.class);
   public static final ServiceKey<ModpackService> MODPACKS = key("modpacks", ModpackService.class);
   public static final ServiceKey<SkinService> SKINS = key("skins", SkinService.class);
   public static final ServiceKey<DiagnosticsService> DIAGNOSTICS = key("diagnostics", DiagnosticsService.class);
   public static final ServiceKey<LocalizationService> LOCALIZATION = key("localization", LocalizationService.class);
   public static final ServiceKey<SafeLogger> LOGGER = key("logger", SafeLogger.class);
   public static final ServiceKey<PublicDirectoryService> PUBLIC_DIRECTORY = key("public_directory", PublicDirectoryService.class);

   private ApiServiceKeys() {
   }

   public static <T> T require(ServiceRegistry registry, ServiceKey<T> key) {
      if (registry != null && key != null) {
         return registry.find(key).orElseThrow(() -> new IllegalStateException("API service unavailable: " + key.id()));
      } else {
         throw new NullPointerException("service");
      }
   }

   private static <T> ServiceKey<T> key(String path, Class<T> type) {
      return new ServiceKey<>("e4steam:" + path, type);
   }
}
