package link.e4steam.internal.dedicated;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import link.e4steam.api.dedicated.DedicatedServerService;

final class DedicatedLifecycle {
   private static final Map<DedicatedServerService.DedicatedServerState, EnumSet<DedicatedServerService.DedicatedServerState>> ALLOWED = allowedTransitions();
   private final Object lock = new Object();
   private final CompletableFuture<DedicatedServerService.DedicatedServerSnapshot> readiness = new CompletableFuture<>();
   private final DedicatedRuntimeConfig config;
   private DedicatedServerService.DedicatedServerState state = DedicatedServerService.DedicatedServerState.OFF;
   private DedicatedServerService.ServerAuthorityRef authority;
   private boolean ingressGuarded;
   private int players;
   private String failureCategory = "";

   DedicatedLifecycle(DedicatedRuntimeConfig config) {
      this.config = config;
   }

   void begin(long generation) {
      synchronized (this.lock) {
         if (this.authority != null) {
            throw new IllegalStateException("Dedicated generation already started");
         } else {
            this.authority = new DedicatedServerService.ServerAuthorityRef(opaqueAuthority(), generation);
            this.transitionLocked(DedicatedServerService.DedicatedServerState.CONFIG_VALIDATED, "");
         }
      }
   }

   void transition(DedicatedServerService.DedicatedServerState next) {
      synchronized (this.lock) {
         this.transitionLocked(next, "");
      }
   }

   void accepting(boolean guarded) {
      synchronized (this.lock) {
         this.ingressGuarded = guarded;
         this.transitionLocked(DedicatedServerService.DedicatedServerState.MINECRAFT_READY, "");
         this.transitionLocked(DedicatedServerService.DedicatedServerState.ACCEPTING, "");
         this.readiness.complete(this.snapshotLocked());
      }
   }

   void fail(String category) {
      synchronized (this.lock) {
         if (!terminal(this.state)) {
            this.failureCategory = safeCategory(category);
            this.state = DedicatedServerService.DedicatedServerState.FAILED;
            DedicatedServerService.DedicatedServerSnapshot snapshot = this.snapshotLocked();
            this.readiness.completeExceptionally(new IllegalStateException(this.failureCategory));
         }
      }
   }

   void players(int value) {
      synchronized (this.lock) {
         if (value >= 0 && value <= this.config.maxPeers()) {
            this.players = value;
         } else {
            throw new IllegalArgumentException("players");
         }
      }
   }

   DedicatedServerService.DedicatedServerSnapshot snapshot() {
      synchronized (this.lock) {
         return this.snapshotLocked();
      }
   }

   CompletionStage<DedicatedServerService.DedicatedServerSnapshot> readiness() {
      return this.readiness.thenApply(value -> (DedicatedServerService.DedicatedServerSnapshot)value);
   }

   private void transitionLocked(DedicatedServerService.DedicatedServerState next, String category) {
      if (next != this.state) {
         EnumSet<DedicatedServerService.DedicatedServerState> allowed = ALLOWED.get(this.state);
         if (allowed != null && allowed.contains(next)) {
            this.state = next;
            this.failureCategory = category;
         } else {
            throw new IllegalStateException("Invalid dedicated lifecycle transition");
         }
      }
   }

   private DedicatedServerService.DedicatedServerSnapshot snapshotLocked() {
      DedicatedServerService.ServerAuthorityRef current = this.authority;
      if (current == null) {
         current = new DedicatedServerService.ServerAuthorityRef("dedicated_off", 1L);
      }

      return new DedicatedServerService.DedicatedServerSnapshot(
         this.state, current, this.config.accessMode(), this.ingressGuarded, false, this.players, this.config.maxPeers(), this.failureCategory
      );
   }

   private static boolean terminal(DedicatedServerService.DedicatedServerState value) {
      return value == DedicatedServerService.DedicatedServerState.STOPPED || value == DedicatedServerService.DedicatedServerState.FAILED;
   }

   private static String safeCategory(String value) {
      return value != null && value.matches("[A-Z0-9_]{3,96}") ? value : "DEDICATED_FAILED";
   }

   private static String opaqueAuthority() {
      byte[] random = new byte[18];
      new SecureRandom().nextBytes(random);
      return "srv_" + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
   }

   private static Map<DedicatedServerService.DedicatedServerState, EnumSet<DedicatedServerService.DedicatedServerState>> allowedTransitions() {
      EnumMap<DedicatedServerService.DedicatedServerState, EnumSet<DedicatedServerService.DedicatedServerState>> result = new EnumMap<>(
         DedicatedServerService.DedicatedServerState.class
      );
      result.put(DedicatedServerService.DedicatedServerState.OFF, EnumSet.of(DedicatedServerService.DedicatedServerState.CONFIG_VALIDATED));
      result.put(
         DedicatedServerService.DedicatedServerState.CONFIG_VALIDATED,
         EnumSet.of(DedicatedServerService.DedicatedServerState.NATIVES_READY, DedicatedServerService.DedicatedServerState.DRAINING)
      );
      result.put(
         DedicatedServerService.DedicatedServerState.NATIVES_READY,
         EnumSet.of(DedicatedServerService.DedicatedServerState.STEAM_INITIALIZING, DedicatedServerService.DedicatedServerState.DRAINING)
      );
      result.put(
         DedicatedServerService.DedicatedServerState.STEAM_INITIALIZING,
         EnumSet.of(DedicatedServerService.DedicatedServerState.STEAM_LOGGING_ON, DedicatedServerService.DedicatedServerState.DRAINING)
      );
      result.put(
         DedicatedServerService.DedicatedServerState.STEAM_LOGGING_ON,
         EnumSet.of(DedicatedServerService.DedicatedServerState.TRANSPORT_READY, DedicatedServerService.DedicatedServerState.DRAINING)
      );
      result.put(
         DedicatedServerService.DedicatedServerState.TRANSPORT_READY,
         EnumSet.of(DedicatedServerService.DedicatedServerState.MINECRAFT_READY, DedicatedServerService.DedicatedServerState.DRAINING)
      );
      result.put(
         DedicatedServerService.DedicatedServerState.MINECRAFT_READY,
         EnumSet.of(DedicatedServerService.DedicatedServerState.ACCEPTING, DedicatedServerService.DedicatedServerState.DRAINING)
      );
      result.put(DedicatedServerService.DedicatedServerState.ACCEPTING, EnumSet.of(DedicatedServerService.DedicatedServerState.DRAINING));
      result.put(DedicatedServerService.DedicatedServerState.DRAINING, EnumSet.of(DedicatedServerService.DedicatedServerState.STOPPED));
      return result;
   }
}
