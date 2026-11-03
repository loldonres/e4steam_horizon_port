package link.e4steam.api.dedicated;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.regex.Pattern;
import link.e4steam.api.ApiResult;
import link.e4steam.api.ApiValidation;

public interface DedicatedServerService {
   ApiResult<DedicatedServerService.DedicatedServerSnapshot> snapshot();

   ApiResult<DedicatedServerService.DedicatedConfigSnapshot> config();

   CompletionStage<ApiResult<DedicatedServerService.DedicatedServerSnapshot>> readiness();

   CompletionStage<ApiResult<DedicatedServerService.DedicatedServerSnapshot>> drain(String var1);

   CompletionStage<ApiResult<DedicatedServerService.PublicationPlan>> proposePublication(DedicatedServerService.PublicationProposal var1);

   public static enum DedicatedAccessMode {
      PRIVATE,
      WHITELIST,
      UNLISTED,
      CUSTOM;
   }

   public static final class DedicatedConfigSnapshot {
      private final int schemaVersion;
      private final DedicatedServerService.DedicatedAccessMode accessMode;
      private final int maxPeers;
      private final boolean publicationEnabled;
      private final String loginMode;

      public DedicatedConfigSnapshot(
         int schemaVersion, DedicatedServerService.DedicatedAccessMode accessMode, int maxPeers, boolean publicationEnabled, String loginMode
      ) {
         if (schemaVersion < 1) {
            throw new IllegalArgumentException("invalid schemaVersion");
         } else if (maxPeers >= 1 && maxPeers <= 256) {
            this.schemaVersion = schemaVersion;
            this.accessMode = Objects.requireNonNull(accessMode, "accessMode");
            this.maxPeers = maxPeers;
            this.publicationEnabled = publicationEnabled;
            this.loginMode = ApiValidation.text(loginMode, "loginMode", 32);
         } else {
            throw new IllegalArgumentException("invalid maxPeers");
         }
      }

      public int schemaVersion() {
         return this.schemaVersion;
      }

      public DedicatedServerService.DedicatedAccessMode accessMode() {
         return this.accessMode;
      }

      public int maxPeers() {
         return this.maxPeers;
      }

      public boolean publicationEnabled() {
         return this.publicationEnabled;
      }

      public String loginMode() {
         return this.loginMode;
      }

      @Override
      public String toString() {
         return "DedicatedConfigSnapshot{schema="
            + this.schemaVersion
            + ", access="
            + this.accessMode
            + ", maxPeers="
            + this.maxPeers
            + ", publication="
            + this.publicationEnabled
            + ", loginMode="
            + this.loginMode
            + '}';
      }
   }

   public static final class DedicatedServerSnapshot {
      private final DedicatedServerService.DedicatedServerState state;
      private final DedicatedServerService.ServerAuthorityRef authority;
      private final DedicatedServerService.DedicatedAccessMode accessMode;
      private final boolean ingressGuarded;
      private final boolean publicationActive;
      private final int players;
      private final int capacity;
      private final String failureCategory;

      public DedicatedServerSnapshot(
         DedicatedServerService.DedicatedServerState state,
         DedicatedServerService.ServerAuthorityRef authority,
         DedicatedServerService.DedicatedAccessMode accessMode,
         boolean ingressGuarded,
         boolean publicationActive,
         int players,
         int capacity,
         String failureCategory
      ) {
         this.state = Objects.requireNonNull(state, "state");
         this.authority = Objects.requireNonNull(authority, "authority");
         this.accessMode = Objects.requireNonNull(accessMode, "accessMode");
         if (players < 0 || capacity < 1 || players > capacity || capacity > 256) {
            throw new IllegalArgumentException("invalid capacity");
         } else if (state == DedicatedServerService.DedicatedServerState.ACCEPTING && !ingressGuarded) {
            throw new IllegalArgumentException("accepting requires ingress guard");
         } else {
            this.ingressGuarded = ingressGuarded;
            this.publicationActive = publicationActive;
            this.players = players;
            this.capacity = capacity;
            this.failureCategory = ApiValidation.optionalText(failureCategory, "failureCategory", 96);
         }
      }

      public DedicatedServerService.DedicatedServerState state() {
         return this.state;
      }

      public DedicatedServerService.ServerAuthorityRef authority() {
         return this.authority;
      }

      public DedicatedServerService.DedicatedAccessMode accessMode() {
         return this.accessMode;
      }

      public boolean ingressGuarded() {
         return this.ingressGuarded;
      }

      public boolean publicationActive() {
         return this.publicationActive;
      }

      public int players() {
         return this.players;
      }

      public int capacity() {
         return this.capacity;
      }

      public String failureCategory() {
         return this.failureCategory;
      }

      @Override
      public String toString() {
         return "DedicatedServerSnapshot{state="
            + this.state
            + ", guarded="
            + this.ingressGuarded
            + ", publication="
            + this.publicationActive
            + ", players="
            + this.players
            + '/'
            + this.capacity
            + '}';
      }
   }

   public static enum DedicatedServerState {
      OFF,
      CONFIG_VALIDATED,
      NATIVES_READY,
      STEAM_INITIALIZING,
      STEAM_LOGGING_ON,
      TRANSPORT_READY,
      MINECRAFT_READY,
      ACCEPTING,
      DRAINING,
      STOPPED,
      FAILED;
   }

   public static final class PublicationPlan {
      private final boolean allowed;
      private final String reasonCode;

      public PublicationPlan(boolean allowed, String reasonCode) {
         this.allowed = allowed;
         this.reasonCode = ApiValidation.optionalText(reasonCode, "reasonCode", 96);
      }

      public boolean allowed() {
         return this.allowed;
      }

      public String reasonCode() {
         return this.reasonCode;
      }
   }

   public static final class PublicationProposal {
      private final String providerId;
      private final String displayName;
      private final Set<String> tags;

      public PublicationProposal(String providerId, String displayName, Set<String> tags) {
         this.providerId = ApiValidation.text(providerId, "providerId", 96);
         this.displayName = ApiValidation.text(displayName, "displayName", 64);
         if (tags != null && tags.size() <= 16) {
            LinkedHashSet<String> copy = new LinkedHashSet<>();

            for (String tag : tags) {
               copy.add(ApiValidation.text(tag, "tag", 32));
            }

            this.tags = Collections.unmodifiableSet(copy);
         } else {
            throw new IllegalArgumentException("invalid tags");
         }
      }

      public String providerId() {
         return this.providerId;
      }

      public String displayName() {
         return this.displayName;
      }

      public Set<String> tags() {
         return this.tags;
      }
   }

   public static final class ServerAuthorityRef {
      private static final Pattern FORMAT = Pattern.compile("^[A-Za-z0-9_-]{8,96}$");
      private final String opaqueId;
      private final long generation;

      public ServerAuthorityRef(String opaqueId, long generation) {
         this.opaqueId = ApiValidation.identifier(opaqueId, "authorityId", FORMAT);
         if (generation <= 0L) {
            throw new IllegalArgumentException("generation must be positive");
         } else {
            this.generation = generation;
         }
      }

      public String opaqueId() {
         return this.opaqueId;
      }

      public long generation() {
         return this.generation;
      }

      @Override
      public String toString() {
         return "ServerAuthorityRef{generation=" + this.generation + '}';
      }
   }
}
