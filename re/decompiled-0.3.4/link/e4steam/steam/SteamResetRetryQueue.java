package link.e4steam.steam;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongUnaryOperator;

final class SteamResetRetryQueue<B> {
   private final int capacity;
   private final int maxAttempts;
   private final long maxAgeMillis;
   private final long baseDelayMillis;
   private final long maxDelayMillis;
   private final LongUnaryOperator jitter;
   private final LinkedHashMap<SteamResetRetryQueue.Key<B>, SteamResetRetryQueue.Entry<B>> entries = new LinkedHashMap<>();

   SteamResetRetryQueue(int capacity, int maxAttempts, long maxAgeMillis, long baseDelayMillis, long maxDelayMillis) {
      this(capacity, maxAttempts, maxAgeMillis, baseDelayMillis, maxDelayMillis, bound -> bound <= 0L ? 0L : ThreadLocalRandom.current().nextLong(bound));
   }

   SteamResetRetryQueue(int capacity, int maxAttempts, long maxAgeMillis, long baseDelayMillis, long maxDelayMillis, LongUnaryOperator jitter) {
      if (capacity >= 1 && maxAttempts >= 2 && maxAgeMillis >= 1L && baseDelayMillis >= 1L && maxDelayMillis >= baseDelayMillis) {
         this.capacity = capacity;
         this.maxAttempts = maxAttempts;
         this.maxAgeMillis = maxAgeMillis;
         this.baseDelayMillis = baseDelayMillis;
         this.maxDelayMillis = maxDelayMillis;
         this.jitter = jitter;
      } else {
         throw new IllegalArgumentException("Invalid RESET retry bounds");
      }
   }

   synchronized SteamResetRetryQueue.Offer<B> offerAfterTemporaryFailure(
      long remoteSteamId, int connectionId, byte[] payload, B bridge, long generation, long nowMillis
   ) {
      SteamResetRetryQueue.Key<B> key = new SteamResetRetryQueue.Key<>(remoteSteamId, connectionId, bridge, generation);
      SteamResetRetryQueue.Entry<B> existing = this.entries.get(key);
      if (existing != null) {
         return new SteamResetRetryQueue.Offer<>(SteamResetRetryQueue.OfferStatus.DUPLICATE, existing);
      } else if (this.entries.size() >= this.capacity) {
         return new SteamResetRetryQueue.Offer<>(SteamResetRetryQueue.OfferStatus.FULL, null);
      } else {
         SteamResetRetryQueue.Entry<B> entry = new SteamResetRetryQueue.Entry<>(key, payload, nowMillis, nowMillis + this.retryDelayMillis(1));
         this.entries.put(key, entry);
         return new SteamResetRetryQueue.Offer<>(SteamResetRetryQueue.OfferStatus.ACCEPTED, entry);
      }
   }

   synchronized SteamResetRetryQueue.Entry<B> poll(long currentGeneration, long nowMillis) {
      Iterator<Map.Entry<SteamResetRetryQueue.Key<B>, SteamResetRetryQueue.Entry<B>>> iterator = this.entries.entrySet().iterator();

      while (iterator.hasNext()) {
         SteamResetRetryQueue.Entry<B> entry = iterator.next().getValue();
         if (entry.generation() != currentGeneration) {
            iterator.remove();
            entry.state = SteamResetRetryQueue.State.CANCELLED_STALE_GENERATION;
            return entry;
         }

         if (entry.attempts >= this.maxAttempts || nowMillis - entry.createdAtMillis >= this.maxAgeMillis) {
            iterator.remove();
            entry.state = SteamResetRetryQueue.State.EXHAUSTED;
            return entry;
         }

         if (entry.state == SteamResetRetryQueue.State.RETRY_SCHEDULED && nowMillis >= entry.nextAttemptMillis) {
            entry.state = SteamResetRetryQueue.State.AWAITING_SEND;
            return entry;
         }
      }

      return null;
   }

   synchronized SteamResetRetryQueue.State complete(SteamResetRetryQueue.Entry<B> entry, SteamResetRetryQueue.SendOutcome outcome, long nowMillis) {
      SteamResetRetryQueue.Entry<B> current = this.entries.get(entry.key);
      if (current == entry && entry.state == SteamResetRetryQueue.State.AWAITING_SEND) {
         switch (outcome) {
            case SUCCESS:
               this.entries.remove(entry.key);
               entry.state = SteamResetRetryQueue.State.SENT;
               return entry.state;
            case TEMPORARY_FAILURE:
               entry.attempts++;
               if (entry.attempts < this.maxAttempts && nowMillis - entry.createdAtMillis < this.maxAgeMillis) {
                  entry.nextAttemptMillis = nowMillis + this.retryDelayMillis(entry.attempts);
                  entry.state = SteamResetRetryQueue.State.RETRY_SCHEDULED;
                  return entry.state;
               }

               this.entries.remove(entry.key);
               entry.state = SteamResetRetryQueue.State.EXHAUSTED;
               return entry.state;
            case PERMANENT_FAILURE:
               this.entries.remove(entry.key);
               entry.state = SteamResetRetryQueue.State.PERMANENT_FAILURE;
               return entry.state;
            default:
               throw new IllegalStateException("Unhandled RESET retry outcome: " + outcome);
         }
      } else {
         return entry.state;
      }
   }

   synchronized SteamResetRetryQueue.State cancelStaleConnection(SteamResetRetryQueue.Entry<B> entry) {
      if (this.entries.remove(entry.key, entry)) {
         entry.state = SteamResetRetryQueue.State.CANCELLED_STALE_CONNECTION;
      }

      return entry.state;
   }

   synchronized List<SteamResetRetryQueue.Entry<B>> purge(B bridge) {
      List<SteamResetRetryQueue.Entry<B>> removed = new ArrayList<>();
      this.entries.entrySet().removeIf(candidate -> {
         SteamResetRetryQueue.Entry<B> entry = candidate.getValue();
         if (entry.bridge() != bridge) {
            return false;
         } else {
            entry.state = SteamResetRetryQueue.State.CANCELLED_STALE_CONNECTION;
            removed.add(entry);
            return true;
         }
      });
      return removed;
   }

   synchronized List<SteamResetRetryQueue.Entry<B>> cancelAll() {
      List<SteamResetRetryQueue.Entry<B>> removed = new ArrayList<>(this.entries.values());
      this.entries.clear();

      for (SteamResetRetryQueue.Entry<B> entry : removed) {
         entry.state = SteamResetRetryQueue.State.CANCELLED_SHUTDOWN;
      }

      return removed;
   }

   synchronized boolean isEmpty() {
      return this.entries.isEmpty();
   }

   synchronized int size() {
      return this.entries.size();
   }

   private long retryDelayMillis(int failedAttempts) {
      long delay = this.baseDelayMillis;

      for (int attempt = 1; attempt < failedAttempts && delay < this.maxDelayMillis; attempt++) {
         delay = Math.min(this.maxDelayMillis, delay > this.maxDelayMillis / 2L ? this.maxDelayMillis : delay * 2L);
      }

      long jitterBound = Math.max(1L, delay / 4L + 1L);
      long jitterValue = Math.floorMod(this.jitter.applyAsLong(jitterBound), jitterBound);
      return delay + jitterValue;
   }

   static final class Entry<B> {
      private final SteamResetRetryQueue.Key<B> key;
      private final byte[] payload;
      private final long createdAtMillis;
      private int attempts;
      private long nextAttemptMillis;
      private SteamResetRetryQueue.State state;

      private Entry(SteamResetRetryQueue.Key<B> key, byte[] payload, long createdAtMillis, long nextAttemptMillis) {
         this.key = key;
         this.payload = (byte[])payload.clone();
         this.createdAtMillis = createdAtMillis;
         this.attempts = 1;
         this.nextAttemptMillis = nextAttemptMillis;
         this.state = SteamResetRetryQueue.State.RETRY_SCHEDULED;
      }

      long remoteSteamId() {
         return this.key.remoteSteamId;
      }

      int connectionId() {
         return this.key.connectionId;
      }

      long generation() {
         return this.key.generation;
      }

      B bridge() {
         return this.key.bridge;
      }

      int attempts() {
         return this.attempts;
      }

      long nextAttemptMillis() {
         return this.nextAttemptMillis;
      }

      SteamResetRetryQueue.State state() {
         return this.state;
      }

      void putPayload(ByteBuffer target) {
         target.put(this.payload);
      }

      byte[] payloadCopy() {
         return (byte[])this.payload.clone();
      }

      @Override
      public String toString() {
         return "SteamResetRetryEntry{generation=" + this.generation() + ", attempts=" + this.attempts + ", state=" + this.state + "}";
      }
   }

   private static final class Key<B> {
      private final long remoteSteamId;
      private final int connectionId;
      private final B bridge;
      private final long generation;

      private Key(long remoteSteamId, int connectionId, B bridge, long generation) {
         this.remoteSteamId = remoteSteamId;
         this.connectionId = connectionId;
         this.bridge = bridge;
         this.generation = generation;
      }

      @Override
      public boolean equals(Object other) {
         if (this == other) {
            return true;
         } else {
            return !(other instanceof SteamResetRetryQueue.Key<?> key)
               ? false
               : this.remoteSteamId == key.remoteSteamId
                  && this.connectionId == key.connectionId
                  && this.bridge == key.bridge
                  && this.generation == key.generation;
         }
      }

      @Override
      public int hashCode() {
         int result = Long.hashCode(this.remoteSteamId);
         result = 31 * result + this.connectionId;
         result = 31 * result + System.identityHashCode(this.bridge);
         return 31 * result + Long.hashCode(this.generation);
      }
   }

   static final class Offer<B> {
      private final SteamResetRetryQueue.OfferStatus status;
      private final SteamResetRetryQueue.Entry<B> entry;

      private Offer(SteamResetRetryQueue.OfferStatus status, SteamResetRetryQueue.Entry<B> entry) {
         this.status = status;
         this.entry = entry;
      }

      SteamResetRetryQueue.OfferStatus status() {
         return this.status;
      }

      SteamResetRetryQueue.Entry<B> entry() {
         return this.entry;
      }
   }

   static enum OfferStatus {
      ACCEPTED,
      DUPLICATE,
      FULL;
   }

   static enum SendOutcome {
      SUCCESS,
      TEMPORARY_FAILURE,
      PERMANENT_FAILURE;
   }

   static enum State {
      RETRY_SCHEDULED,
      AWAITING_SEND,
      SENT,
      CANCELLED_STALE_GENERATION,
      CANCELLED_STALE_CONNECTION,
      CANCELLED_SHUTDOWN,
      EXHAUSTED,
      PERMANENT_FAILURE;
   }
}
