package link.e4steam.steam;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import link.e4steam.E4steamClient;

final class SteamUdpBridge {
   private static final int MAX_UDP_PACKET_SIZE = 65507;
   private final SteamRuntime runtime;
   private final SteamConnectionBridge owner;
   private final DatagramSocket socket;
   private final boolean hostSide;
   private final AtomicReference<SocketAddress> localClient = new AtomicReference<>();
   private final AtomicBoolean closed = new AtomicBoolean();
   private volatile Thread readerThread;

   private SteamUdpBridge(SteamRuntime runtime, SteamConnectionBridge owner, DatagramSocket socket, boolean hostSide) {
      this.runtime = runtime;
      this.owner = owner;
      this.socket = socket;
      this.hostSide = hostSide;
   }

   static SteamUdpBridge client(SteamRuntime runtime, SteamConnectionBridge owner, int port) throws IOException {
      DatagramSocket socket = new DatagramSocket(null);
      boolean ready = false;

      SteamUdpBridge var6;
      try {
         socket.setReuseAddress(false);
         socket.bind(new InetSocketAddress(loopback(), port));
         SteamUdpBridge bridge = new SteamUdpBridge(runtime, owner, socket, false);
         ready = true;
         var6 = bridge;
      } finally {
         if (!ready) {
            socket.close();
         }
      }

      return var6;
   }

   static SteamUdpBridge host(SteamRuntime runtime, SteamConnectionBridge owner, int port) throws IOException {
      DatagramSocket socket = new DatagramSocket(null);
      boolean ready = false;

      SteamUdpBridge var6;
      try {
         socket.bind(new InetSocketAddress(loopback(), 0));
         socket.connect(new InetSocketAddress(loopback(), port));
         SteamUdpBridge bridge = new SteamUdpBridge(runtime, owner, socket, true);
         ready = true;
         var6 = bridge;
      } finally {
         if (!ready) {
            socket.close();
         }
      }

      return var6;
   }

   SteamConnectionBridge owner() {
      return this.owner;
   }

   boolean isClosed() {
      return this.closed.get();
   }

   int localPort() {
      return this.socket.getLocalPort();
   }

   boolean hasLocalClient() {
      return this.localClient.get() != null;
   }

   void start() {
      if (!this.closed.get() && this.readerThread == null) {
         Thread thread = new Thread(
            this::readLoop,
            "e4steam-steam-udp-" + Long.toUnsignedString(this.owner.remoteSteamId()) + "-" + Integer.toUnsignedString(this.owner.connectionId())
         );
         thread.setDaemon(true);
         this.readerThread = thread;
         thread.start();
      }
   }

   void acceptSteamDatagram(byte[] payload) {
      if (!this.closed.get()) {
         try {
            DatagramPacket packet;
            if (this.hostSide) {
               packet = new DatagramPacket(payload, payload.length);
            } else {
               SocketAddress destination = this.localClient.get();
               if (destination == null) {
                  return;
               }

               packet = new DatagramPacket(payload, payload.length, destination);
            }

            this.socket.send(packet);
         } catch (IOException var4) {
            if (!this.closed.get()) {
               E4steamClient.LOGGER.debug("Could not deliver a tunneled UDP datagram", var4);
            }
         }
      }
   }

   void close() {
      if (this.closed.compareAndSet(false, true)) {
         this.socket.close();
         Thread thread = this.readerThread;
         if (thread != null) {
            thread.interrupt();
         }
      }
   }

   private void readLoop() {
      byte[] buffer = new byte[65507];

      while (!this.closed.get()) {
         DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

         try {
            this.socket.receive(packet);
            if (!this.hostSide) {
               InetAddress source = packet.getAddress();
               if (source == null || !source.isLoopbackAddress()) {
                  continue;
               }

               this.localClient.set(packet.getSocketAddress());
            }

            if (packet.getLength() > 1188) {
               E4steamClient.LOGGER.debug("Dropping oversized UDP datagram ({} bytes; maximum {})", packet.getLength(), 1188);
            } else {
               byte[] payload = Arrays.copyOfRange(packet.getData(), packet.getOffset(), packet.getOffset() + packet.getLength());
               this.runtime.sendDatagram(this, payload);
            }
         } catch (SocketException var4) {
            if (!this.closed.get()) {
               E4steamClient.LOGGER.debug("UDP tunnel socket stopped", var4);
            }

            return;
         } catch (IOException var5) {
            if (!this.closed.get()) {
               E4steamClient.LOGGER.debug("UDP tunnel reader stopped", var5);
            }

            return;
         }
      }
   }

   private static InetAddress loopback() throws IOException {
      return InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
   }
}
