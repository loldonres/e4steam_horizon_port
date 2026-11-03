package link.e4steam.steam;

import java.util.concurrent.ConcurrentHashMap;

final class SteamKnownPeerSessionGate {
   private final long reacceptDelayMillis;
   private final ConcurrentHashMap<Long, Long> reacceptDeadlines = new ConcurrentHashMap<>();

   SteamKnownPeerSessionGate(long reacceptDelayMillis) {
      if (reacceptDelayMillis < 0L) {
         throw new IllegalArgumentException("reacceptDelayMillis must not be negative");
      } else {
         this.reacceptDelayMillis = reacceptDelayMillis;
      }
   }

   void defer(long remoteSteamId, long nowMillis) {
      long requestedDeadline = nowMillis + this.reacceptDelayMillis;
      this.reacceptDeadlines.merge(remoteSteamId, requestedDeadline, (previous, requested) -> Math.max(previous, requested));
   }

   void observeNewSession(long remoteSteamId) {
      this.reacceptDeadlines.remove(remoteSteamId);
   }

   boolean mayProactivelyAccept(long remoteSteamId, boolean bridgeExists, boolean pending, boolean capacityAvailable, long nowMillis) {
      return !bridgeExists && !pending && capacityAvailable && nowMillis >= this.reacceptDeadlines.getOrDefault(remoteSteamId, 0L);
   }

   void clear() {
      this.reacceptDeadlines.clear();
   }
}
