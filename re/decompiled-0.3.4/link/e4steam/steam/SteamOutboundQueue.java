package link.e4steam.steam;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;

final class SteamOutboundQueue<B> {
   private final Object lock = new Object();
   private final ArrayBlockingQueue<SteamOutboundQueue.Packet<B>> packets;
   private final Semaphore dataSlots;
   private final Semaphore datagramSlots;
   private final Semaphore addonSlots;
   private final Semaphore openSlots;
   private final Semaphore standaloneResetSlots;
   private final int terminalReserve;
   private final int perBridgeDataCapacity;
   private final Map<B, Integer> dataPacketsByBridge = new IdentityHashMap<>();

   SteamOutboundQueue(int totalCapacity, int dataCapacity, int datagramCapacity, int addonCapacity, int openCapacity, int standaloneResetCapacity) {
      this.packets = new ArrayBlockingQueue<>(totalCapacity);
      this.dataSlots = new Semaphore(dataCapacity);
      this.datagramSlots = new Semaphore(datagramCapacity);
      this.addonSlots = new Semaphore(addonCapacity);
      this.openSlots = new Semaphore(openCapacity);
      this.standaloneResetSlots = new Semaphore(standaloneResetCapacity);
      this.terminalReserve = Math.max(1, Math.min(64, totalCapacity / 16));
      this.perBridgeDataCapacity = Math.max(1, Math.min(8, dataCapacity));
   }

   boolean offerData(long remoteSteamId, int connectionId, byte[] payload, B bridge) {
      return this.offer(new SteamOutboundQueue.Packet<>(remoteSteamId, connectionId, payload, SteamOutboundQueue.Kind.DATA, bridge));
   }

   boolean offerDatagram(long remoteSteamId, int connectionId, byte[] payload, B bridge) {
      return this.offer(new SteamOutboundQueue.Packet<>(remoteSteamId, connectionId, payload, SteamOutboundQueue.Kind.DATAGRAM, bridge));
   }

   boolean offerAddonData(long remoteSteamId, int connectionId, byte[] payload, boolean unreliable, B bridge) {
      return this.offer(
         new SteamOutboundQueue.Packet<>(
            remoteSteamId, connectionId, payload, unreliable ? SteamOutboundQueue.Kind.ADDON_DATAGRAM : SteamOutboundQueue.Kind.ADDON_DATA, bridge
         )
      );
   }

   boolean offerControl(long remoteSteamId, int connectionId, byte[] payload, SteamOutboundQueue.Kind kind, B bridge) {
      if (kind != SteamOutboundQueue.Kind.DATA
         && kind != SteamOutboundQueue.Kind.DATAGRAM
         && kind != SteamOutboundQueue.Kind.ADDON_DATA
         && kind != SteamOutboundQueue.Kind.ADDON_DATAGRAM) {
         return this.offer(new SteamOutboundQueue.Packet<>(remoteSteamId, connectionId, payload, kind, bridge));
      } else {
         throw new IllegalArgumentException("Control queue cannot accept " + kind);
      }
   }

   private boolean offer(SteamOutboundQueue.Packet<B> packet) {
      synchronized (this.lock) {
         Semaphore category = this.categorySlots(packet);
         if (!isTerminal(packet.kind()) && this.packets.remainingCapacity() <= this.terminalReserve) {
            return false;
         } else if (packet.kind() == SteamOutboundQueue.Kind.DATA
            && packet.bridge() != null
            && this.dataPacketsByBridge.getOrDefault(packet.bridge(), 0) >= this.perBridgeDataCapacity) {
            return false;
         } else if (category != null && !category.tryAcquire()) {
            return false;
         } else if (!this.packets.offer(packet)) {
            if (category != null) {
               category.release();
            }

            return false;
         } else {
            if (packet.kind() == SteamOutboundQueue.Kind.DATA && packet.bridge() != null) {
               this.dataPacketsByBridge.merge(packet.bridge(), 1, Integer::sum);
            }

            return true;
         }
      }
   }

   private static boolean isTerminal(SteamOutboundQueue.Kind kind) {
      return kind == SteamOutboundQueue.Kind.FIN || kind == SteamOutboundQueue.Kind.RESET;
   }

   SteamOutboundQueue.Packet<B> poll() {
      synchronized (this.lock) {
         SteamOutboundQueue.Packet<B> packet = this.packets.poll();
         if (packet != null) {
            this.releaseSlot(packet);
         }

         return packet;
      }
   }

   void purge(B bridge) {
      synchronized (this.lock) {
         this.packets.removeIf(packet -> {
            if (packet.bridge() != bridge) {
               return false;
            } else {
               this.releaseSlot((SteamOutboundQueue.Packet<B>)packet);
               return true;
            }
         });
      }
   }

   void clear() {
      synchronized (this.lock) {
         SteamOutboundQueue.Packet<B> packet;
         while ((packet = this.packets.poll()) != null) {
            this.releaseSlot(packet);
         }
      }
   }

   boolean isEmpty() {
      return this.packets.isEmpty();
   }

   private Semaphore categorySlots(SteamOutboundQueue.Packet<B> packet) {
      switch (packet.kind()) {
         case OPEN:
         case OPEN_ACK:
         case DEDICATED_OPEN:
         case DEDICATED_OPEN_ACK:
         case BRIDGE_READY:
         case ADDON_HELLO:
            return this.openSlots;
         case ADDON_DATA:
         case ADDON_DATAGRAM:
            return this.addonSlots;
         case DATA:
            return this.dataSlots;
         case DATAGRAM:
            return this.datagramSlots;
         case FIN:
         default:
            return null;
         case RESET:
            return packet.bridge() == null ? this.standaloneResetSlots : null;
      }
   }

   private void releaseSlot(SteamOutboundQueue.Packet<B> packet) {
      if (packet.kind() == SteamOutboundQueue.Kind.DATA && packet.bridge() != null) {
         Integer count = this.dataPacketsByBridge.get(packet.bridge());
         if (count != null) {
            if (count <= 1) {
               this.dataPacketsByBridge.remove(packet.bridge());
            } else {
               this.dataPacketsByBridge.put(packet.bridge(), count - 1);
            }
         }
      }

      Semaphore category = this.categorySlots(packet);
      if (category != null) {
         category.release();
      }
   }

   static enum Kind {
      OPEN,
      OPEN_ACK,
      DEDICATED_OPEN,
      DEDICATED_OPEN_ACK,
      BRIDGE_READY,
      ADDON_HELLO,
      ADDON_DATA,
      ADDON_DATAGRAM,
      DATA,
      DATAGRAM,
      FIN,
      RESET;
   }

   static final class Packet<B> {
      private final long remoteSteamId;
      private final int connectionId;
      private final byte[] payload;
      private final SteamOutboundQueue.Kind kind;
      private final B bridge;

      Packet(long remoteSteamId, int connectionId, byte[] payload, SteamOutboundQueue.Kind kind, B bridge) {
         this.remoteSteamId = remoteSteamId;
         this.connectionId = connectionId;
         this.payload = payload;
         this.kind = kind;
         this.bridge = bridge;
      }

      long remoteSteamId() {
         return this.remoteSteamId;
      }

      int connectionId() {
         return this.connectionId;
      }

      byte[] payload() {
         return this.payload;
      }

      SteamOutboundQueue.Kind kind() {
         return this.kind;
      }

      B bridge() {
         return this.bridge;
      }
   }
}
