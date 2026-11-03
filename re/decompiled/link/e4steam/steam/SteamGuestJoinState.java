package link.e4steam.steam;

final class SteamGuestJoinState {
   private SteamGuestJoinState.Phase phase = SteamGuestJoinState.Phase.RESOLVING;
   private long deadlineMillis;
   private boolean claimed;

   SteamGuestJoinState(long deadlineMillis) {
      this.deadlineMillis = deadlineMillis;
   }

   void waitForConfirmation() {
      if (this.phase == SteamGuestJoinState.Phase.RESOLVING) {
         this.phase = SteamGuestJoinState.Phase.WAITING_FOR_CONFIRMATION;
         this.deadlineMillis = Long.MAX_VALUE;
      }
   }

   boolean claim() {
      if (!this.claimed && this.phase == SteamGuestJoinState.Phase.WAITING_FOR_CONFIRMATION) {
         this.claimed = true;
         return true;
      } else {
         return false;
      }
   }

   boolean beginConnect(long deadlineMillis) {
      if (this.phase != SteamGuestJoinState.Phase.WAITING_FOR_CONFIRMATION) {
         return false;
      } else {
         this.claimed = true;
         this.phase = SteamGuestJoinState.Phase.CONNECTING;
         this.deadlineMillis = deadlineMillis;
         return true;
      }
   }

   void connected() {
      if (this.phase == SteamGuestJoinState.Phase.CONNECTING) {
         this.phase = SteamGuestJoinState.Phase.CONNECTED;
         this.deadlineMillis = Long.MAX_VALUE;
      }
   }

   boolean expired(long nowMillis) {
      return (this.phase == SteamGuestJoinState.Phase.RESOLVING || this.phase == SteamGuestJoinState.Phase.CONNECTING) && this.deadlineMillis <= nowMillis;
   }

   boolean isConnected() {
      return this.phase == SteamGuestJoinState.Phase.CONNECTED;
   }

   void cancel() {
      if (this.phase != SteamGuestJoinState.Phase.LOST) {
         this.phase = SteamGuestJoinState.Phase.CANCELED;
         this.deadlineMillis = Long.MIN_VALUE;
      }
   }

   void loseLobby() {
      this.phase = SteamGuestJoinState.Phase.LOST;
      this.deadlineMillis = Long.MIN_VALUE;
   }

   SteamGuestJoinState.Phase phase() {
      return this.phase;
   }

   static enum Phase {
      RESOLVING,
      WAITING_FOR_CONFIRMATION,
      CONNECTING,
      CONNECTED,
      CANCELED,
      LOST;
   }
}
