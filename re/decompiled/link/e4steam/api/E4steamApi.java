package link.e4steam.api;

import link.e4steam.api.access.AccessService;
import link.e4steam.api.addon.AddonService;
import link.e4steam.api.capability.CapabilityService;
import link.e4steam.api.command.CommandService;
import link.e4steam.api.config.ConfigService;
import link.e4steam.api.dedicated.DedicatedServerService;
import link.e4steam.api.diagnostics.DiagnosticsService;
import link.e4steam.api.directory.PublicDirectoryService;
import link.e4steam.api.event.EventService;
import link.e4steam.api.identity.IdentityService;
import link.e4steam.api.lobby.LobbyService;
import link.e4steam.api.localization.LocalizationService;
import link.e4steam.api.logging.SafeLogger;
import link.e4steam.api.modpack.ModpackService;
import link.e4steam.api.network.NetworkService;
import link.e4steam.api.runtime.RuntimeService;
import link.e4steam.api.scheduler.SchedulerService;
import link.e4steam.api.session.SessionService;
import link.e4steam.api.skin.SkinService;
import link.e4steam.api.storage.StorageService;
import link.e4steam.api.udp.UdpService;
import link.e4steam.api.ui.UiService;
import link.e4steam.api.world.WorldSettingsService;

public interface E4steamApi {
   ApiVersion apiVersion();

   RuntimeService runtime();

   AddonService addons();

   CapabilityService capabilities();

   EventService events();

   default IdentityService identities() {
      return ApiServiceKeys.require(this.services(), ApiServiceKeys.IDENTITIES);
   }

   default SessionService sessions() {
      return ApiServiceKeys.require(this.services(), ApiServiceKeys.SESSIONS);
   }

   default DedicatedServerService dedicatedServers() {
      return ApiServiceKeys.require(this.services(), ApiServiceKeys.DEDICATED);
   }

   default AccessService access() {
      return ApiServiceKeys.require(this.services(), ApiServiceKeys.ACCESS);
   }

   default LobbyService lobbies() {
      return ApiServiceKeys.require(this.services(), ApiServiceKeys.LOBBIES);
   }

   default NetworkService network() {
      return ApiServiceKeys.require(this.services(), ApiServiceKeys.NETWORK);
   }

   default UdpService udp() {
      return ApiServiceKeys.require(this.services(), ApiServiceKeys.UDP);
   }

   default UiService ui() {
      return ApiServiceKeys.require(this.services(), ApiServiceKeys.UI);
   }

   default CommandService commands() {
      return ApiServiceKeys.require(this.services(), ApiServiceKeys.COMMANDS);
   }

   default ConfigService config() {
      return ApiServiceKeys.require(this.services(), ApiServiceKeys.CONFIG);
   }

   default StorageService storage() {
      return ApiServiceKeys.require(this.services(), ApiServiceKeys.STORAGE);
   }

   default WorldSettingsService worldSettings() {
      return ApiServiceKeys.require(this.services(), ApiServiceKeys.WORLD_SETTINGS);
   }

   default ModpackService modpacks() {
      return ApiServiceKeys.require(this.services(), ApiServiceKeys.MODPACKS);
   }

   default SkinService skins() {
      return ApiServiceKeys.require(this.services(), ApiServiceKeys.SKINS);
   }

   default DiagnosticsService diagnostics() {
      return ApiServiceKeys.require(this.services(), ApiServiceKeys.DIAGNOSTICS);
   }

   default LocalizationService localization() {
      return ApiServiceKeys.require(this.services(), ApiServiceKeys.LOCALIZATION);
   }

   default SafeLogger logger() {
      return ApiServiceKeys.require(this.services(), ApiServiceKeys.LOGGER);
   }

   default PublicDirectoryService publicDirectory() {
      return ApiServiceKeys.require(this.services(), ApiServiceKeys.PUBLIC_DIRECTORY);
   }

   SchedulerService scheduler();

   ServiceRegistry services();
}
