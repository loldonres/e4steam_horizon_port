package link.e4steam.internal.api;

import java.util.Set;
import link.e4steam.api.ApiConstants;
import link.e4steam.api.ApiServiceKeys;
import link.e4steam.api.ApiVersion;
import link.e4steam.api.E4steamApi;
import link.e4steam.api.ResourceScope;
import link.e4steam.api.ServiceRegistry;
import link.e4steam.api.addon.AddonDescriptor;
import link.e4steam.api.addon.AddonService;
import link.e4steam.api.capability.CapabilityId;
import link.e4steam.api.capability.CapabilityService;
import link.e4steam.api.event.EventService;
import link.e4steam.api.runtime.RuntimeService;
import link.e4steam.api.scheduler.SchedulerService;

final class ScopedE4steamApi implements E4steamApi {
   private final CoreRuntimeService runtime;
   private final AddonService addons;
   private final CoreCapabilityService capabilities;
   private final EventService events;
   private final SchedulerService scheduler;
   private final CoreServiceRegistry services;

   ScopedE4steamApi(AddonDescriptor descriptor, Set<CapabilityId> granted, ResourceScope resources, CoreApiPlatform platform) {
      this.runtime = platform.runtime();
      this.addons = platform.addons();
      this.capabilities = new CoreCapabilityService(descriptor.requestedCapabilities(), granted);
      this.events = platform.events().scoped(resources);
      this.scheduler = new ScopedSchedulerService(platform.scheduler(), resources);
      CoreContributionRegistry contributions = platform.contributions();
      this.services = new CoreServiceRegistry()
         .add(ApiServiceKeys.IDENTITIES, CoreStateServices.identities(this.capabilities, platform.sessions()))
         .add(ApiServiceKeys.SESSIONS, CoreStateServices.sessions(this.capabilities, platform.sessions()))
         .add(ApiServiceKeys.DEDICATED, CoreStateServices.dedicated(this.capabilities))
         .add(ApiServiceKeys.ACCESS, new CoreAccessService(descriptor.id(), this.capabilities, contributions, resources, platform.scheduler()))
         .add(ApiServiceKeys.LOBBIES, new CoreLobbyService(descriptor.id(), this.capabilities, platform.scheduler(), resources))
         .add(ApiServiceKeys.NETWORK, new CoreNetworkService(descriptor.id(), this.capabilities, contributions, resources, platform.network()))
         .add(ApiServiceKeys.UDP, new CoreUdpService(descriptor.id(), this.capabilities, contributions, resources))
         .add(ApiServiceKeys.UI, new CoreUiService(descriptor.id(), this.capabilities, contributions, resources, platform.environment().mode()))
         .add(ApiServiceKeys.COMMANDS, new CoreCommandService(descriptor.id(), this.capabilities, contributions, resources))
         .add(ApiServiceKeys.CONFIG, new CoreConfigService(descriptor.id(), this.capabilities))
         .add(ApiServiceKeys.STORAGE, new CoreStorageService(descriptor.id(), this.capabilities))
         .add(ApiServiceKeys.WORLD_SETTINGS, CoreStateServices.world(this.capabilities))
         .add(ApiServiceKeys.MODPACKS, CoreProviderServices.modpacks(descriptor.id(), this.capabilities, contributions, resources, platform.scheduler()))
         .add(ApiServiceKeys.SKINS, CoreProviderServices.skins(descriptor.id(), this.capabilities, contributions, resources, platform.scheduler()))
         .add(ApiServiceKeys.DIAGNOSTICS, CoreProviderServices.diagnostics(descriptor.id(), this.capabilities, contributions, resources, platform.scheduler()))
         .add(ApiServiceKeys.LOCALIZATION, CoreStateServices.localization())
         .add(ApiServiceKeys.LOGGER, new CoreSafeLogger(descriptor.id()))
         .add(ApiServiceKeys.PUBLIC_DIRECTORY, new CorePublicDirectoryService(this.capabilities, descriptor.id().value()));
   }

   @Override
   public ApiVersion apiVersion() {
      return ApiConstants.API_VERSION;
   }

   @Override
   public RuntimeService runtime() {
      return this.runtime;
   }

   @Override
   public AddonService addons() {
      return this.addons;
   }

   @Override
   public CapabilityService capabilities() {
      return this.capabilities;
   }

   @Override
   public EventService events() {
      return this.events;
   }

   @Override
   public SchedulerService scheduler() {
      return this.scheduler;
   }

   @Override
   public ServiceRegistry services() {
      return this.services;
   }
}
