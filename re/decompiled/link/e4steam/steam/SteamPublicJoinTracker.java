package link.e4steam.steam;

final class SteamPublicJoinTracker {
   private volatile SteamPublicJoinTracker.Snapshot current = SteamPublicJoinTracker.Snapshot.idle();

   void begin(long lobbyId) {
      if (lobbyId != 0L) {
         this.current = new SteamPublicJoinTracker.Snapshot(lobbyId, SteamPublicJoinTracker.Phase.JOINING_STEAM_TARGET, "");
      }
   }

   void authenticating(long lobbyId) {
      this.advance(lobbyId, SteamPublicJoinTracker.Phase.AUTHENTICATING, "");
   }

   void connecting(long lobbyId) {
      this.advance(lobbyId, SteamPublicJoinTracker.Phase.CONNECTING_MINECRAFT, "");
   }

   void active(long lobbyId) {
      this.advance(lobbyId, SteamPublicJoinTracker.Phase.ACTIVE, "");
   }

   void cancel(long lobbyId) {
      this.advance(lobbyId, SteamPublicJoinTracker.Phase.CANCELLED, "");
   }

   void fail(long lobbyId, SteamPublicJoinTracker.Phase phase, String detailCode) {
      if (phase != SteamPublicJoinTracker.Phase.STALE
         && phase != SteamPublicJoinTracker.Phase.FULL
         && phase != SteamPublicJoinTracker.Phase.INCOMPATIBLE
         && phase != SteamPublicJoinTracker.Phase.FAILED) {
         throw new IllegalArgumentException("phase is not a failure state");
      } else {
         this.advance(lobbyId, phase, detailCode);
      }
   }

   SteamPublicJoinTracker.Snapshot snapshot(long lobbyId) {
      SteamPublicJoinTracker.Snapshot snapshot = this.current;
      return lobbyId != 0L && snapshot.lobbyId == lobbyId ? snapshot : SteamPublicJoinTracker.Snapshot.idle();
   }

   private void advance(long lobbyId, SteamPublicJoinTracker.Phase phase, String detailCode) {
      if (lobbyId != 0L) {
         SteamPublicJoinTracker.Snapshot before = this.current;
         if (before.lobbyId == lobbyId && !terminal(before.phase)) {
            this.current = new SteamPublicJoinTracker.Snapshot(lobbyId, phase, safeDetail(detailCode));
         }
      }
   }

   private static boolean terminal(SteamPublicJoinTracker.Phase phase) {
      return phase == SteamPublicJoinTracker.Phase.ACTIVE
         || phase == SteamPublicJoinTracker.Phase.CANCELLED
         || phase == SteamPublicJoinTracker.Phase.STALE
         || phase == SteamPublicJoinTracker.Phase.FULL
         || phase == SteamPublicJoinTracker.Phase.INCOMPATIBLE
         || phase == SteamPublicJoinTracker.Phase.FAILED;
   }

   private static String safeDetail(String value) {
      if (value != null && !value.isEmpty()) {
         return value.matches("[a-z][a-z0-9_.-]{0,95}") ? value : "join-failed";
      } else {
         return "";
      }
   }

   static enum Phase {
      IDLE,
      JOINING_STEAM_TARGET,
      AUTHENTICATING,
      CONNECTING_MINECRAFT,
      ACTIVE,
      CANCELLED,
      STALE,
      FULL,
      INCOMPATIBLE,
      FAILED;
   }

   static final class Snapshot {
      private final long lobbyId;
      private final SteamPublicJoinTracker.Phase phase;
      private final String detailCode;

      private Snapshot(long lobbyId, SteamPublicJoinTracker.Phase phase, String detailCode) {
         this.lobbyId = lobbyId;
         this.phase = phase;
         this.detailCode = detailCode;
      }

      private static SteamPublicJoinTracker.Snapshot idle() {
         return new SteamPublicJoinTracker.Snapshot(0L, SteamPublicJoinTracker.Phase.IDLE, "");
      }

      SteamPublicJoinTracker.Phase phase() {
         return this.phase;
      }

      String detailCode() {
         return this.detailCode;
      }

      @Override
      public String toString() {
         return "SteamPublicJoinSnapshot{phase=" + this.phase + ", target=opaque}";
      }
   }
}
