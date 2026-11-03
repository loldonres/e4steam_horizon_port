package link.e4steam.api.identity;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.regex.Pattern;
import link.e4steam.api.ApiResult;
import link.e4steam.api.ApiValidation;

public interface IdentityService {
   ApiResult<IdentityService.LocalIdentity> local();

   CompletionStage<ApiResult<IdentityService.RemoteIdentity>> remote(IdentityService.PeerId var1);

   CompletionStage<ApiResult<IdentityService.SteamProfile>> steamProfile(IdentityService.PeerId var1);

   public static enum FriendRelationship {
      FRIEND,
      BLOCKED,
      NONE,
      UNKNOWN;
   }

   public static final class LocalIdentity {
      private final IdentityService.MinecraftIdentity minecraft;

      public LocalIdentity(IdentityService.MinecraftIdentity minecraft) {
         this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
         if (!minecraft.local()) {
            throw new IllegalArgumentException("local identity must be local");
         }
      }

      public IdentityService.MinecraftIdentity minecraft() {
         return this.minecraft;
      }

      @Override
      public String toString() {
         return "LocalIdentity{" + this.minecraft + '}';
      }
   }

   public static final class MinecraftIdentity {
      private final UUID uuid;
      private final String displayName;
      private final boolean local;

      public MinecraftIdentity(UUID uuid, String displayName, boolean local) {
         this.uuid = Objects.requireNonNull(uuid, "uuid");
         this.displayName = ApiValidation.text(displayName, "displayName", 64);
         this.local = local;
      }

      public UUID uuid() {
         return this.uuid;
      }

      public String displayName() {
         return this.displayName;
      }

      public boolean local() {
         return this.local;
      }

      @Override
      public String toString() {
         return "MinecraftIdentity{uuid=" + this.uuid + ", local=" + this.local + '}';
      }
   }

   public static final class PeerId {
      private static final Pattern FORMAT = Pattern.compile("^[A-Za-z0-9_-]{8,96}$");
      private final String value;

      public PeerId(String value) {
         this.value = ApiValidation.identifier(value, "peerId", FORMAT);
      }

      public String value() {
         return this.value;
      }

      @Override
      public boolean equals(Object other) {
         return this == other || other instanceof IdentityService.PeerId && this.value.equals(((IdentityService.PeerId)other).value);
      }

      @Override
      public int hashCode() {
         return this.value.hashCode();
      }

      @Override
      public String toString() {
         return "PeerId{" + this.value + '}';
      }
   }

   public static final class PeerIdentity {
      private final IdentityService.PeerId peerId;
      private final IdentityService.MinecraftIdentity minecraft;

      public PeerIdentity(IdentityService.PeerId peerId, IdentityService.MinecraftIdentity minecraft) {
         this.peerId = Objects.requireNonNull(peerId, "peerId");
         this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
      }

      public IdentityService.PeerId peerId() {
         return this.peerId;
      }

      public IdentityService.MinecraftIdentity minecraft() {
         return this.minecraft;
      }
   }

   public static enum PresenceState {
      OFFLINE,
      ONLINE,
      BUSY,
      AWAY,
      SNOOZE,
      UNKNOWN;
   }

   public static final class RemoteIdentity {
      private final IdentityService.PeerIdentity peer;

      public RemoteIdentity(IdentityService.PeerIdentity peer) {
         this.peer = Objects.requireNonNull(peer, "peer");
         if (peer.minecraft().local()) {
            throw new IllegalArgumentException("remote identity cannot be local");
         }
      }

      public IdentityService.PeerIdentity peer() {
         return this.peer;
      }

      @Override
      public String toString() {
         return "RemoteIdentity{peer=" + this.peer.peerId() + '}';
      }
   }

   public static final class SteamProfile {
      private static final Pattern STEAM_ID = Pattern.compile("^[1-9][0-9]{0,19}$");
      private final String steamId64;
      private final String personaName;
      private final String avatarAsset;
      private final IdentityService.PresenceState presence;
      private final IdentityService.FriendRelationship relationship;

      public SteamProfile(
         String steamId64, String personaName, String avatarAsset, IdentityService.PresenceState presence, IdentityService.FriendRelationship relationship
      ) {
         this.steamId64 = ApiValidation.identifier(steamId64, "steamId64", STEAM_ID);
         this.personaName = ApiValidation.text(personaName, "personaName", 64);
         this.avatarAsset = ApiValidation.optionalText(avatarAsset, "avatarAsset", 512);
         this.presence = Objects.requireNonNull(presence, "presence");
         this.relationship = Objects.requireNonNull(relationship, "relationship");
      }

      public String steamId64() {
         return this.steamId64;
      }

      public String personaName() {
         return this.personaName;
      }

      public String avatarAsset() {
         return this.avatarAsset;
      }

      public IdentityService.PresenceState presence() {
         return this.presence;
      }

      public IdentityService.FriendRelationship relationship() {
         return this.relationship;
      }

      @Override
      public String toString() {
         return "SteamProfile{personalData=redacted, presence=" + this.presence + '}';
      }
   }
}
