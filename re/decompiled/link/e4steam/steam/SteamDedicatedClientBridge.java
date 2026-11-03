package link.e4steam.steam;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class SteamDedicatedClientBridge {
   private static final Logger LOGGER = LogManager.getLogger("e4steam");
   private static final int ACCEPT_TIMEOUT_MILLIS = 30000;
   private static final Set<SteamDedicatedClientBridge.Pending> PENDING = ConcurrentHashMap.newKeySet();

   private SteamDedicatedClientBridge() {
   }

   public static InetSocketAddress open(SteamDedicatedAddress address) throws IOException {
      SteamRuntime runtime = SteamRuntime.get();
      SteamRuntime.Activity activity = runtime.acquireActivity();
      boolean handedOff = false;

      InetSocketAddress var8;
      try {
         runtime.awaitReady();
         InetAddress loopback = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
         ServerSocket listener = new ServerSocket();
         listener.setReuseAddress(false);
         listener.bind(new InetSocketAddress(loopback, 0), 1);
         listener.setSoTimeout(30000);
         SteamDedicatedClientBridge.Pending pending = new SteamDedicatedClientBridge.Pending(listener, activity);
         PENDING.add(pending);
         Thread thread = new Thread(() -> accept(runtime, address, pending), "e4steam-dedicated-client-accept");
         thread.setDaemon(true);
         thread.start();
         handedOff = true;
         var8 = new InetSocketAddress(loopback, listener.getLocalPort());
      } finally {
         if (!handedOff) {
            activity.close();
         }
      }

      return var8;
   }

   public static void cancelPending() {
      for (SteamDedicatedClientBridge.Pending pending : PENDING.toArray(new SteamDedicatedClientBridge.Pending[0])) {
         pending.close();
      }

      PENDING.clear();
   }

   private static void accept(SteamRuntime runtime, SteamDedicatedAddress address, SteamDedicatedClientBridge.Pending pending) {
      Socket socket = null;
      SteamConnectionBridge bridge = null;
      SteamRuntime.DedicatedClientAuth authentication = null;
      boolean handedOff = false;

      try (ServerSocket listener = pending.listener) {
         socket = listener.accept();
         socket.setTcpNoDelay(true);
         socket.setKeepAlive(true);
         SteamRuntime.Activity activity = pending.takeActivity();
         if (activity == null) {
            throw new IOException("Dedicated Steam connection was cancelled");
         }

         runtime.prepareDedicatedConnection(address.steamId());
         authentication = runtime.createDedicatedClientAuth();
         int connectionId = runtime.nextConnectionId(address.steamId());
         SteamDedicatedClientBridge.ClientLease lease = new SteamDedicatedClientBridge.ClientLease(activity, authentication);
         bridge = runtime.registerClientBridge(address.steamId(), connectionId, socket, lease);
         bridge.dedicatedSessionGeneration(address.generation());
         activity = null;
         bridge.waitForPeerReadyUntil(Long.MAX_VALUE);
         if (!runtime.sendDedicatedOpen(bridge, address, authentication)) {
            throw new IOException("Steam outbound queue is unavailable");
         }

         authentication = null;
         handedOff = true;
         LOGGER.info("Opened a dedicated e4steam client bridge");
      } catch (SocketTimeoutException var19) {
         LOGGER.debug("Timed out waiting for Minecraft to use a dedicated descriptor");
      } catch (IOException var20) {
         if (!pending.closed) {
            LOGGER.warn("Dedicated Steam client bridge failed", var20);
         }
      } finally {
         PENDING.remove(pending);
         pending.close();
         close(authentication);
         if (!handedOff) {
            if (bridge != null) {
               bridge.close(false);
            } else {
               close(socket);
            }
         }
      }
   }

   private static void close(AutoCloseable closeable) {
      if (closeable != null) {
         try {
            closeable.close();
         } catch (Exception var2) {
         }
      }
   }

   private static final class ClientLease implements AutoCloseable {
      private final SteamRuntime.Activity activity;
      private final SteamRuntime.DedicatedClientAuth authentication;
      private final AtomicBoolean closed = new AtomicBoolean();

      private ClientLease(SteamRuntime.Activity activity, SteamRuntime.DedicatedClientAuth authentication) {
         this.activity = activity;
         this.authentication = authentication;
      }

      @Override
      public void close() {
         if (this.closed.compareAndSet(false, true)) {
            this.authentication.close();
            this.activity.close();
         }
      }
   }

   private static final class Pending implements AutoCloseable {
      private final ServerSocket listener;
      private SteamRuntime.Activity activity;
      private volatile boolean closed;

      private Pending(ServerSocket listener, SteamRuntime.Activity activity) {
         this.listener = listener;
         this.activity = activity;
      }

      synchronized SteamRuntime.Activity takeActivity() {
         if (this.closed) {
            return null;
         } else {
            SteamRuntime.Activity value = this.activity;
            this.activity = null;
            return value;
         }
      }

      @Override
      public void close() {
         SteamRuntime.Activity value;
         synchronized (this) {
            if (this.closed) {
               return;
            }

            this.closed = true;
            value = this.activity;
            this.activity = null;
         }

         SteamDedicatedClientBridge.close(this.listener);
         SteamDedicatedClientBridge.close(value);
      }
   }
}
