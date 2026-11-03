package link.e4steam.internal.api;

import java.util.Collections;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import link.e4steam.api.ApiErrorCode;
import link.e4steam.api.ApiResult;
import link.e4steam.api.ApiValidation;
import link.e4steam.api.Registration;
import link.e4steam.api.capability.Capabilities;
import link.e4steam.api.dedicated.DedicatedServerService;
import link.e4steam.api.identity.IdentityService;
import link.e4steam.api.lobby.LobbyService;
import link.e4steam.api.localization.LocalizationService;
import link.e4steam.api.session.SessionService;
import link.e4steam.api.world.WorldSettingsService;
import link.e4steam.internal.dedicated.DedicatedServerController;

final class CoreStateServices {
   private CoreStateServices() {
   }

   static IdentityService identities(CoreCapabilityService capabilities, CoreSessionRegistry sessions) {
      return new IdentityService() {
         @Override
         public ApiResult<IdentityService.LocalIdentity> local() {
            if (!capabilities.has(Capabilities.IDENTITY_MINECRAFT_READ)) {
               return CoreStateServices.denied("identity.local");
            } else {
               IdentityService.LocalIdentity identity = sessions.localIdentity();
               return identity == null ? CoreStateServices.unavailable("identity.local") : ApiResult.success(identity);
            }
         }

         @Override
         public CompletionStage<ApiResult<IdentityService.RemoteIdentity>> remote(IdentityService.PeerId peerId) {
            if (!capabilities.has(Capabilities.IDENTITY_MINECRAFT_READ)) {
               return CoreStateServices.completed(CoreStateServices.denied("identity.remote"));
            } else if (peerId == null) {
               return CoreStateServices.completed(SafeApiErrors.failure(ApiErrorCode.INVALID_ARGUMENT, "identity.remote", "Validation"));
            } else {
               IdentityService.RemoteIdentity identity = sessions.remoteIdentity(peerId);
               return CoreStateServices.completed(identity == null ? CoreStateServices.unavailable("identity.remote") : ApiResult.success(identity));
            }
         }

         @Override
         public CompletionStage<ApiResult<IdentityService.SteamProfile>> steamProfile(IdentityService.PeerId peerId) {
            boolean allowed = capabilities.has(Capabilities.IDENTITY_STEAM_PROFILE_READ) || capabilities.has(Capabilities.STEAM_PROFILE_READ);
            return CoreStateServices.completed(
               allowed ? CoreStateServices.unavailable("identity.steam-profile") : CoreStateServices.denied("identity.steam-profile")
            );
         }
      };
   }

   static SessionService sessions(CoreCapabilityService capabilities, CoreSessionRegistry sessions) {
      return new SessionService() {
         @Override
         public ApiResult<SessionService.SessionSnapshot> snapshot() {
            return capabilities.has(Capabilities.SESSION_OBSERVE) ? sessions.snapshot() : CoreStateServices.denied("session.snapshot");
         }

         @Override
         public CompletionStage<ApiResult<SessionService.PeerPage>> peers(SessionService.SessionId id, String cursor, int limit) {
            return capabilities.has(Capabilities.SESSION_OBSERVE)
               ? sessions.peers(id, cursor, limit)
               : CoreStateServices.completed(CoreStateServices.denied("session.peers"));
         }

         @Override
         public CompletionStage<ApiResult<SessionService.SessionSnapshot>> disconnect(SessionService.SessionId id, String reason) {
            if (!capabilities.has(Capabilities.SESSION_CONTROL)) {
               return CoreStateServices.completed(CoreStateServices.denied("session.disconnect"));
            } else {
               try {
                  String checked = ApiValidation.text(reason, "reason", 96);
                  ApiValidation.rejectSensitiveName(checked, "reason");
               } catch (RuntimeException var4) {
                  return CoreStateServices.completed(SafeApiErrors.failure(ApiErrorCode.INVALID_ARGUMENT, "session.disconnect", "Reason"));
               }

               return sessions.disconnect(id);
            }
         }

         @Override
         public CompletionStage<ApiResult<SessionService.SessionSnapshot>> readiness() {
            return capabilities.has(Capabilities.SESSION_OBSERVE)
               ? sessions.readiness()
               : CoreStateServices.completed(CoreStateServices.denied("session.readiness"));
         }

         @Override
         public ApiResult<Registration> registerResource(SessionService.SessionId id, Registration resource) {
            return capabilities.has(Capabilities.SESSION_OBSERVE) ? sessions.register(id, resource) : CoreStateServices.denied("session.resource");
         }
      };
   }

   static DedicatedServerService dedicated(CoreCapabilityService capabilities) {
      DedicatedServerController controller = DedicatedServerController.current();
      final DedicatedServerService delegate = controller == null ? null : controller.service();
      return new DedicatedServerService() {
         @Override
         public ApiResult<DedicatedServerService.DedicatedServerSnapshot> snapshot() {
            if (!capabilities.has(Capabilities.DEDICATED_OBSERVE)) {
               return CoreStateServices.denied("dedicated.snapshot");
            } else {
               return delegate == null ? CoreStateServices.unsupported("dedicated.snapshot") : delegate.snapshot();
            }
         }

         @Override
         public ApiResult<DedicatedServerService.DedicatedConfigSnapshot> config() {
            if (!capabilities.has(Capabilities.DEDICATED_OBSERVE)) {
               return CoreStateServices.denied("dedicated.config");
            } else {
               return delegate == null ? CoreStateServices.unsupported("dedicated.config") : delegate.config();
            }
         }

         @Override
         public CompletionStage<ApiResult<DedicatedServerService.DedicatedServerSnapshot>> readiness() {
            if (!capabilities.has(Capabilities.DEDICATED_OBSERVE)) {
               return CoreStateServices.completed(CoreStateServices.denied("dedicated.readiness"));
            } else {
               return delegate == null ? CoreStateServices.completed(CoreStateServices.unsupported("dedicated.readiness")) : delegate.readiness();
            }
         }

         @Override
         public CompletionStage<ApiResult<DedicatedServerService.DedicatedServerSnapshot>> drain(String reason) {
            if (!capabilities.has(Capabilities.DEDICATED_ADMIN)) {
               return CoreStateServices.completed(CoreStateServices.denied("dedicated.drain"));
            } else {
               return delegate == null ? CoreStateServices.completed(CoreStateServices.unsupported("dedicated.drain")) : delegate.drain(reason);
            }
         }

         @Override
         public CompletionStage<ApiResult<DedicatedServerService.PublicationPlan>> proposePublication(DedicatedServerService.PublicationProposal proposal) {
            if (!capabilities.has(Capabilities.DEDICATED_PUBLICATION_PROPOSE)) {
               return CoreStateServices.completed(CoreStateServices.denied("dedicated.publication"));
            } else {
               return delegate == null
                  ? CoreStateServices.completed(ApiResult.success(new DedicatedServerService.PublicationPlan(false, "public-provider-not-active")))
                  : delegate.proposePublication(proposal);
            }
         }
      };
   }

   static LobbyService lobbies(CoreCapabilityService capabilities) {
      return new LobbyService() {
         @Override
         public CompletionStage<ApiResult<LobbyService.LobbySnapshot>> create(LobbyService.CreateRequest request) {
            return CoreStateServices.completed(
               capabilities.has(Capabilities.LOBBY_CREATE) ? CoreStateServices.unavailable("lobby.create") : CoreStateServices.denied("lobby.create")
            );
         }

         @Override
         public CompletionStage<ApiResult<LobbyService.LobbySnapshot>> join(LobbyService.LobbyId id) {
            return CoreStateServices.completed(CoreStateServices.unavailable("lobby.join"));
         }

         @Override
         public CompletionStage<ApiResult<Boolean>> leave(LobbyService.LobbyId id) {
            return CoreStateServices.completed(CoreStateServices.unavailable("lobby.leave"));
         }

         @Override
         public CompletionStage<ApiResult<LobbyService.LobbySnapshot>> updateMetadata(LobbyService.LobbyId id, LobbyService.Metadata metadata) {
            return CoreStateServices.completed(
               capabilities.has(Capabilities.LOBBY_METADATA_WRITE)
                  ? CoreStateServices.unavailable("lobby.metadata")
                  : CoreStateServices.denied("lobby.metadata")
            );
         }

         @Override
         public CompletionStage<ApiResult<LobbyService.SearchPage>> search(LobbyService.SearchQuery query) {
            return CoreStateServices.completed(
               capabilities.has(Capabilities.LOBBY_SEARCH) ? CoreStateServices.unavailable("lobby.search") : CoreStateServices.denied("lobby.search")
            );
         }
      };
   }

   static WorldSettingsService world(CoreCapabilityService capabilities) {
      return new WorldSettingsService() {
         private final WorldSettingsService.WorldSettingsSchema schema = new WorldSettingsService.WorldSettingsSchema(1, Collections.emptyList());
         private final WorldSettingsService.WorldSettingsSnapshot snapshot = new WorldSettingsService.WorldSettingsSnapshot(1, Collections.emptyMap());

         @Override
         public ApiResult<WorldSettingsService.WorldSettingsSchema> schema() {
            return capabilities.has(Capabilities.WORLD_SETTINGS_READ) ? ApiResult.success(this.schema) : CoreStateServices.denied("world.schema");
         }

         @Override
         public ApiResult<WorldSettingsService.WorldSettingsSnapshot> snapshot() {
            return capabilities.has(Capabilities.WORLD_SETTINGS_READ) ? ApiResult.success(this.snapshot) : CoreStateServices.denied("world.snapshot");
         }

         @Override
         public CompletionStage<ApiResult<WorldSettingsService.WorldSettingsPlan>> plan(WorldSettingsService.WorldSettingsProposal proposal) {
            return !capabilities.has(Capabilities.WORLD_SETTINGS_PROPOSE)
               ? CoreStateServices.completed(CoreStateServices.denied("world.plan"))
               : CoreStateServices.completed(
                  ApiResult.success(
                     new WorldSettingsService.WorldSettingsPlan(
                        "plan-disabled-adapter",
                        proposal == null ? Collections.emptyMap() : proposal.changes(),
                        WorldSettingsService.ApplyTiming.NEXT_WORLD_START,
                        true
                     )
                  )
               );
         }

         @Override
         public CompletionStage<ApiResult<WorldSettingsService.WorldSettingsSnapshot>> apply(WorldSettingsService.WorldSettingsPlan plan, boolean confirmed) {
            return CoreStateServices.completed(CoreStateServices.unsupported("world.apply"));
         }
      };
   }

   static LocalizationService localization() {
      return new LocalizationService() {
         @Override
         public LocalizationService.LocaleSnapshot locale() {
            String current = Locale.getDefault().toLanguageTag();
            if (current == null || current.isEmpty() || "und".equals(current)) {
               current = "en-US";
            }

            return new LocalizationService.LocaleSnapshot(current, "en-US");
         }

         @Override
         public ApiResult<String> resolve(LocalizationService.LocalizedMessage message) {
            if (message == null) {
               return SafeApiErrors.failure(ApiErrorCode.INVALID_ARGUMENT, "localization.resolve", "Validation");
            } else {
               String value = message.fallback();

               for (LocalizationService.MessageArgument argument : message.arguments()) {
                  value = value.replace("{" + argument.name() + "}", argument.value());
               }

               return ApiResult.success(value);
            }
         }
      };
   }

   private static <T> ApiResult<T> denied(String operation) {
      return SafeApiErrors.failure(ApiErrorCode.CAPABILITY_DENIED, operation, "PolicyDenied");
   }

   private static <T> ApiResult<T> unavailable(String operation) {
      return SafeApiErrors.failure(ApiErrorCode.UNAVAILABLE, operation, "NoActiveContext");
   }

   private static <T> ApiResult<T> unsupported(String operation) {
      return SafeApiErrors.failure(ApiErrorCode.UNSUPPORTED, operation, "BackendUnavailable");
   }

   private static <T> CompletionStage<T> completed(T value) {
      return CompletableFuture.completedFuture(value);
   }
}
