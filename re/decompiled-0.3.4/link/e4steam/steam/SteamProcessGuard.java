package link.e4steam.steam;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

final class SteamProcessGuard {
   private static final Object LOCK = new Object();
   private static SteamProcessGuard.Context active;
   private static long generation;
   private static int leases;

   private SteamProcessGuard() {
   }

   static SteamProcessGuard.Lease acquire(SteamProcessGuard.Context requested) throws IOException {
      if (requested == null) {
         throw new NullPointerException("requested");
      } else {
         synchronized (LOCK) {
            if (active != null && active != requested) {
               throw new IOException("Another Steam runtime context is already active");
            } else {
               if (active == null) {
                  active = requested;
                  generation++;
               }

               leases++;
               return new SteamProcessGuard.Lease(requested, generation);
            }
         }
      }
   }

   static SteamProcessGuard.Context activeContext() {
      synchronized (LOCK) {
         return active;
      }
   }

   static enum Context {
      CLIENT,
      GAME_SERVER;
   }

   static final class Lease implements AutoCloseable {
      private final SteamProcessGuard.Context context;
      private final long generation;
      private final AtomicBoolean closed = new AtomicBoolean();

      private Lease(SteamProcessGuard.Context context, long generation) {
         this.context = context;
         this.generation = generation;
      }

      long generation() {
         return this.generation;
      }

      @Override
      public void close() {
         if (this.closed.compareAndSet(false, true)) {
            synchronized (SteamProcessGuard.LOCK) {
               if (SteamProcessGuard.active == this.context && SteamProcessGuard.leases > 0) {
                  SteamProcessGuard.leases--;
                  if (SteamProcessGuard.leases == 0) {
                     SteamProcessGuard.active = null;
                  }
               }
            }
         }
      }
   }
}
