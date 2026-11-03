package link.e4steam.steam;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

final class SteamBridgeRegistry<B, U> {
   private final ConcurrentHashMap<SteamBridgeRegistry.Key, B> bridges = new ConcurrentHashMap<>();
   private final ConcurrentHashMap<SteamBridgeRegistry.Key, U> udpBridges = new ConcurrentHashMap<>();
   private final Semaphore bridgeSlots;
   private final Object registrationLock = new Object();

   SteamBridgeRegistry(int capacity) {
      this.bridgeSlots = new Semaphore(capacity);
   }

   int nextConnectionId(long remoteSteamId, Random random) {
      int connectionId;
      do {
         connectionId = random.nextInt();
      } while (connectionId == 0 || this.bridges.containsKey(new SteamBridgeRegistry.Key(remoteSteamId, connectionId)));

      return connectionId;
   }

   SteamBridgeRegistry.Registration register(SteamBridgeRegistry.Key key, B bridge, BooleanSupplier available) {
      synchronized (this.registrationLock) {
         if (!available.getAsBoolean()) {
            return SteamBridgeRegistry.Registration.UNAVAILABLE;
         } else if (this.bridges.containsKey(key)) {
            return SteamBridgeRegistry.Registration.COLLISION;
         } else if (!this.bridgeSlots.tryAcquire()) {
            return SteamBridgeRegistry.Registration.CAPACITY;
         } else if (this.bridges.putIfAbsent(key, bridge) != null) {
            this.bridgeSlots.release();
            return SteamBridgeRegistry.Registration.COLLISION;
         } else if (!available.getAsBoolean() && this.bridges.remove(key, bridge)) {
            this.bridgeSlots.release();
            return SteamBridgeRegistry.Registration.UNAVAILABLE;
         } else {
            return SteamBridgeRegistry.Registration.REGISTERED;
         }
      }
   }

   boolean remove(SteamBridgeRegistry.Key key, B bridge) {
      synchronized (this.registrationLock) {
         if (!this.bridges.remove(key, bridge)) {
            return false;
         } else {
            this.bridgeSlots.release();
            return true;
         }
      }
   }

   B get(SteamBridgeRegistry.Key key) {
      return this.bridges.get(key);
   }

   boolean contains(SteamBridgeRegistry.Key key) {
      return this.bridges.containsKey(key);
   }

   Collection<B> snapshot() {
      return new ArrayList<>(this.bridges.values());
   }

   boolean any(Predicate<B> predicate) {
      return this.bridges.values().stream().anyMatch(predicate);
   }

   long count(Predicate<B> predicate) {
      return this.bridges.values().stream().filter(predicate).count();
   }

   boolean isEmpty() {
      return this.bridges.isEmpty();
   }

   void clear() {
      synchronized (this.registrationLock) {
         int removed = this.bridges.size();
         this.bridges.clear();
         if (removed > 0) {
            this.bridgeSlots.release(removed);
         }

         this.udpBridges.clear();
      }
   }

   U getUdp(SteamBridgeRegistry.Key key) {
      return this.udpBridges.get(key);
   }

   U putUdpIfAbsent(SteamBridgeRegistry.Key key, U bridge) {
      return this.udpBridges.putIfAbsent(key, bridge);
   }

   U removeUdp(SteamBridgeRegistry.Key key) {
      return this.udpBridges.remove(key);
   }

   boolean containsUdp(SteamBridgeRegistry.Key key) {
      return this.udpBridges.containsKey(key);
   }

   static final class Key {
      private final long remoteSteamId;
      private final int connectionId;

      Key(long remoteSteamId, int connectionId) {
         this.remoteSteamId = remoteSteamId;
         this.connectionId = connectionId;
      }

      long remoteSteamId() {
         return this.remoteSteamId;
      }

      int connectionId() {
         return this.connectionId;
      }

      @Override
      public boolean equals(Object other) {
         if (this == other) {
            return true;
         } else {
            return !(other instanceof SteamBridgeRegistry.Key that)
               ? false
               : this.remoteSteamId == that.remoteSteamId && this.connectionId == that.connectionId;
         }
      }

      @Override
      public int hashCode() {
         int result = Long.hashCode(this.remoteSteamId);
         return 31 * result + this.connectionId;
      }
   }

   static enum Registration {
      REGISTERED,
      COLLISION,
      CAPACITY,
      UNAVAILABLE;
   }
}
