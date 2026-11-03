package link.e4steam.steam;

import java.util.IdentityHashMap;
import java.util.Map;

final class SteamReliableDataPacer<B> {
   private final double bytesPerNanosecond;
   private final int burstBytes;
   private final Map<B, SteamReliableDataPacer.Bucket> buckets = new IdentityHashMap<>();

   SteamReliableDataPacer(long bytesPerSecond, int burstBytes) {
      if (bytesPerSecond > 0L && burstBytes > 0) {
         this.bytesPerNanosecond = bytesPerSecond / 1.0E9;
         this.burstBytes = burstBytes;
      } else {
         throw new IllegalArgumentException("Pacer limits must be positive");
      }
   }

   boolean tryAcquire(B bridge, int bytes, long nowNanos) {
      if (bridge != null && bytes > 0 && bytes <= this.burstBytes) {
         SteamReliableDataPacer.Bucket bucket = this.buckets.get(bridge);
         if (bucket == null) {
            bucket = new SteamReliableDataPacer.Bucket((double)this.burstBytes, nowNanos);
            this.buckets.put(bridge, bucket);
         } else if (nowNanos > bucket.updatedAtNanos) {
            long elapsed = nowNanos - bucket.updatedAtNanos;
            bucket.availableBytes = Math.min((double)this.burstBytes, bucket.availableBytes + elapsed * this.bytesPerNanosecond);
            bucket.updatedAtNanos = nowNanos;
         }

         if (bucket.availableBytes < bytes) {
            return false;
         } else {
            bucket.availableBytes -= bytes;
            return true;
         }
      } else {
         return false;
      }
   }

   void remove(B bridge) {
      this.buckets.remove(bridge);
   }

   void clear() {
      this.buckets.clear();
   }

   private static final class Bucket {
      private double availableBytes;
      private long updatedAtNanos;

      private Bucket(double availableBytes, long updatedAtNanos) {
         this.availableBytes = availableBytes;
         this.updatedAtNanos = updatedAtNanos;
      }
   }
}
