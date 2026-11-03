package link.e4steam.internal.dedicated;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.ConcurrentHashMap;

final class DedicatedIngressRegistry {
   private final ConcurrentHashMap<Integer, DedicatedIngressRegistry.Entry> entries = new ConcurrentHashMap<>();

   AutoCloseable register(int localPort, long steamId, long generation) {
      if (localPort >= 1 && localPort <= 65535 && steamId != 0L && generation > 0L) {
         DedicatedIngressRegistry.Entry entry = new DedicatedIngressRegistry.Entry(steamId, generation);
         if (this.entries.putIfAbsent(localPort, entry) != null) {
            throw new IllegalStateException("loopback ingress port is already authenticated");
         } else {
            return () -> this.entries.remove(localPort, entry);
         }
      } else {
         throw new IllegalArgumentException("invalid ingress binding");
      }
   }

   long resolve(SocketAddress remoteAddress, long activeGeneration) {
      if (!(remoteAddress instanceof InetSocketAddress address)) {
         return 0L;
      } else if (!address.isUnresolved() && address.getAddress() != null && address.getAddress().isLoopbackAddress()) {
         DedicatedIngressRegistry.Entry entry = this.entries.remove(address.getPort());
         return entry != null && entry.generation == activeGeneration ? entry.steamId : 0L;
      } else {
         return 0L;
      }
   }

   void clear() {
      this.entries.clear();
   }

   private static final class Entry {
      private final long steamId;
      private final long generation;

      private Entry(long steamId, long generation) {
         this.steamId = steamId;
         this.generation = generation;
      }
   }
}
