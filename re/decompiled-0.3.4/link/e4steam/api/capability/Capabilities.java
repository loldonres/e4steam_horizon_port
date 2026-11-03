package link.e4steam.api.capability;

public final class Capabilities {
   public static final CapabilityId SESSION_OBSERVE = id("session.observe");
   public static final CapabilityId SESSION_CONTROL = id("session.control");
   public static final CapabilityId DEDICATED_OBSERVE = id("dedicated.observe");
   public static final CapabilityId DEDICATED_ADMIN = id("dedicated.admin");
   public static final CapabilityId DEDICATED_PUBLICATION_PROPOSE = id("dedicated.publication.propose");
   public static final CapabilityId IDENTITY_MINECRAFT_READ = id("identity.minecraft.read");
   public static final CapabilityId IDENTITY_STEAM_PROFILE_READ = id("identity.steam.profile.read");
   public static final CapabilityId LOBBY_CREATE = id("lobby.create");
   public static final CapabilityId LOBBY_SEARCH = id("lobby.search");
   public static final CapabilityId LOBBY_METADATA_WRITE = id("lobby.metadata.write");
   public static final CapabilityId ACCESS_MODE_REGISTER = id("access.mode.register");
   public static final CapabilityId ACCESS_POLICY_EVALUATE = id("access.policy.evaluate");
   public static final CapabilityId NETWORK_CHANNEL_REGISTER = id("network.channel.register");
   public static final CapabilityId UDP_PROVIDER_REGISTER = id("udp.provider.register");
   public static final CapabilityId UI_CONTRIBUTE = id("ui.contribute");
   public static final CapabilityId COMMANDS_REGISTER = id("commands.register");
   public static final CapabilityId CONFIG_READ = id("config.read");
   public static final CapabilityId CONFIG_WRITE = id("config.write");
   public static final CapabilityId STORAGE_PRIVATE = id("storage.private");
   public static final CapabilityId WORLD_SETTINGS_READ = id("world.settings.read");
   public static final CapabilityId WORLD_SETTINGS_PROPOSE = id("world.settings.propose");
   public static final CapabilityId MODPACK_INSPECT = id("modpack.inspect");
   public static final CapabilityId MODPACK_STAGE = id("modpack.stage");
   public static final CapabilityId SKINS_PROVIDE = id("skins.provide");
   public static final CapabilityId DIAGNOSTICS_CONTRIBUTE = id("diagnostics.contribute");
   public static final CapabilityId STEAM_PROFILE_READ = id("steam.profile.read");
   public static final CapabilityId DIRECTORY_PUBLICATION = id("directory.publication");
   public static final CapabilityId DIRECTORY_ATTESTATION = id("directory.attestation");
   public static final CapabilityId DIRECTORY_JOIN = id("directory.join");

   private Capabilities() {
   }

   private static CapabilityId id(String value) {
      return new CapabilityId(value);
   }
}
