package link.e4steam.steam;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import link.e4steam.E4steamClient;

public final class SteamClientBridge {
   private static final int ACCEPT_TIMEOUT_MILLIS = 30000;
   private static final Object PENDING_LOCK = new Object();
   private static final Set<SteamClientBridge.PendingAccept> PENDING_ACCEPTS = ConcurrentHashMap.newKeySet();

   private SteamClientBridge() {
   }

   public static InetSocketAddress open(SteamAddress address) throws IOException {
      SteamRuntime runtime = SteamRuntime.get();
      SteamClientBridge.PendingAccept pending;
      synchronized (PENDING_LOCK) {
         pending = new SteamClientBridge.PendingAccept(runtime.acquireActivity());
         PENDING_ACCEPTS.add(pending);
      }

      boolean acceptThreadStarted = false;

      InetSocketAddress var7;
      try {
         runtime.awaitReady();
         InetAddress loopback = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
         ServerSocket listener = new ServerSocket();

         try {
            listener.setReuseAddress(false);
            listener.bind(new InetSocketAddress(loopback, 0), 1);
            listener.setSoTimeout(30000);
            pending.attachListener(listener);
         } catch (RuntimeException | IOException var12) {
            closeQuietly(listener);
            throw var12;
         }

         Thread acceptThread = new Thread(() -> acceptMinecraftConnection(runtime, address, pending, listener), "e4steam-steam-client-accept");
         acceptThread.setDaemon(true);
         acceptThread.start();
         acceptThreadStarted = true;
         var7 = new InetSocketAddress(loopback, listener.getLocalPort());
      } finally {
         if (!acceptThreadStarted) {
            PENDING_ACCEPTS.remove(pending);
            pending.cancel();
         }
      }

      return var7;
   }

   public static void cancelPending() {
      synchronized (PENDING_LOCK) {
         SteamClientBridge.PendingAccept[] pending = PENDING_ACCEPTS.toArray(new SteamClientBridge.PendingAccept[PENDING_ACCEPTS.size()]);
         PENDING_ACCEPTS.clear();

         for (SteamClientBridge.PendingAccept accept : pending) {
            accept.cancel();
         }
      }
   }

   private static void acceptMinecraftConnection(SteamRuntime runtime, SteamAddress address, SteamClientBridge.PendingAccept pending, ServerSocket listener) {
      Socket socket = null;
      SteamConnectionBridge bridge = null;
      SteamRuntime.Activity activity = null;
      boolean handedOff = false;

      try {
         ServerSocket closeableListener = listener;

         try {
            socket = closeableListener.accept();
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            synchronized (PENDING_LOCK) {
               activity = pending.takeActivityForHandoff();
               PENDING_ACCEPTS.remove(pending);
               if (activity == null) {
                  throw new IOException("Steam connection was cancelled");
               }

               int connectionId = runtime.nextConnectionId(address.steamId());
               bridge = runtime.registerClientBridge(address.steamId(), connectionId, socket, activity);
               activity = null;
            }

            if (!runtime.sendOpen(bridge, address.token())) {
               throw new IOException("Steam outbound queue is unavailable");
            }

            SteamAddonHooks.bridgeReady(bridge);
            bridge.start();
            handedOff = true;
            E4steamClient.LOGGER.info("Opened a local Minecraft bridge to Steam user {}", Long.toUnsignedString(address.steamId()));
         } catch (Throwable var21) {
            if (listener != null) {
               try {
                  closeableListener.close();
               } catch (Throwable var19) {
                  var21.addSuppressed(var19);
               }
            }

            throw var21;
         }

         if (listener != null) {
            listener.close();
         }
      } catch (SocketTimeoutException var22) {
         E4steamClient.LOGGER.debug("Timed out waiting for Minecraft to use a resolved Steam address");
      } catch (IOException var23) {
         if (pending.isCancelled()) {
            E4steamClient.LOGGER.debug("Cancelled a pending Steam client bridge");
         } else {
            E4steamClient.LOGGER.warn("Steam client bridge failed", var23);
         }
      } finally {
         PENDING_ACCEPTS.remove(pending);
         pending.cancel();
         if (!handedOff) {
            if (bridge != null) {
               bridge.close(false);
            } else if (socket != null) {
               closeQuietly(socket);
            }

            closeActivity(activity);
         }
      }
   }

   private static void closeQuietly(AutoCloseable closeable) {
      try {
         closeable.close();
      } catch (Exception var2) {
      }
   }

   private static void closeActivity(SteamRuntime.Activity activity) {
      if (activity != null) {
         try {
            activity.close();
         } catch (Exception var2) {
            E4steamClient.LOGGER.warn("Could not release Steam activity", var2);
         }
      }
   }

   private static final class PendingAccept {
      private SteamRuntime.Activity activity;
      private ServerSocket listener;
      private boolean cancelled;

      private PendingAccept(SteamRuntime.Activity activity) {
         this.activity = activity;
      }

      synchronized void attachListener(ServerSocket listener) throws IOException {
         if (this.cancelled) {
            throw new IOException("Steam connection was cancelled");
         } else {
            this.listener = listener;
         }
      }

      synchronized SteamRuntime.Activity takeActivityForHandoff() {
         if (this.cancelled) {
            return null;
         } else {
            SteamRuntime.Activity result = this.activity;
            this.activity = null;
            this.listener = null;
            return result;
         }
      }

      synchronized boolean isCancelled() {
         return this.cancelled;
      }

      void cancel() {
         SteamRuntime.Activity activityToClose;
         ServerSocket listenerToClose;
         synchronized (this) {
            if (this.cancelled) {
               return;
            }

            this.cancelled = true;
            activityToClose = this.activity;
            this.activity = null;
            listenerToClose = this.listener;
            this.listener = null;
         }

         if (listenerToClose != null) {
            SteamClientBridge.closeQuietly(listenerToClose);
         }

         SteamClientBridge.closeActivity(activityToClose);
      }
   }
}
