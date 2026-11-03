package link.e4steam.steam;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class SteamConnectionBridge {
   private static final Logger LOGGER = LogManager.getLogger("e4steam");
   private static final int MAX_QUEUED_INBOUND_CHUNKS = 256;
   private static final long OUTBOUND_BACKPRESSURE_TIMEOUT_MILLIS = 30000L;
   private static final long OUTBOUND_BACKPRESSURE_RETRY_MILLIS = 10L;
   private static final long GRACEFUL_CLOSE_TIMEOUT_MILLIS = 2000L;
   private static final int MAX_PRE_NEGOTIATION_CHUNKS = 32;
   private static final int MAX_PRE_NEGOTIATION_BYTES = 1048576;
   private final SteamBridgeRuntime runtime;
   private final long remoteSteamId;
   private final int connectionId;
   private final Socket socket;
   private final Object hostOwner;
   private final boolean hostSide;
   private final AtomicReference<AutoCloseable> activity;
   private final BlockingQueue<SteamConnectionBridge.InboundFrame> inbound = new ArrayBlockingQueue<>(257);
   private final Semaphore inboundDataSlots = new Semaphore(256);
   private final AtomicBoolean started = new AtomicBoolean();
   private final AtomicBoolean closed = new AtomicBoolean();
   private final AtomicBoolean outboundFinQueued = new AtomicBoolean();
   private final AtomicBoolean outboundFinSubmitted = new AtomicBoolean();
   private final AtomicBoolean inboundFinQueued = new AtomicBoolean();
   private final AtomicBoolean inboundFinished = new AtomicBoolean();
   private final AtomicBoolean outboundDataObserved = new AtomicBoolean();
   private final AtomicBoolean inboundDataObserved = new AtomicBoolean();
   private final AtomicBoolean peerReady = new AtomicBoolean();
   private final AtomicBoolean addonNegotiationReady = new AtomicBoolean(true);
   private final Object addonNegotiationLock = new Object();
   private final ArrayList<byte[]> preNegotiationData = new ArrayList<>();
   private int preNegotiationBytes;
   private final AtomicLong gracefulCloseDeadlineMillis = new AtomicLong(Long.MAX_VALUE);
   private final AtomicLong outboundNotBeforeMillis = new AtomicLong();
   private final AtomicLong outboundDataFallbackMillis = new AtomicLong();
   private final AtomicLong dedicatedSessionGeneration = new AtomicLong();
   private volatile Thread readerThread;
   private volatile Thread writerThread;

   SteamConnectionBridge(SteamBridgeRuntime runtime, long remoteSteamId, int connectionId, Socket socket, Object hostOwner, AutoCloseable activity) {
      this(runtime, remoteSteamId, connectionId, socket, hostOwner, hostOwner != null, activity);
   }

   SteamConnectionBridge(SteamBridgeRuntime runtime, long remoteSteamId, int connectionId, Socket socket, boolean hostSide, AutoCloseable activity) {
      this(runtime, remoteSteamId, connectionId, socket, null, hostSide, activity);
   }

   private SteamConnectionBridge(
      SteamBridgeRuntime runtime, long remoteSteamId, int connectionId, Socket socket, Object hostOwner, boolean hostSide, AutoCloseable activity
   ) {
      this.runtime = runtime;
      this.remoteSteamId = remoteSteamId;
      this.connectionId = connectionId;
      this.socket = socket;
      this.hostOwner = hostOwner;
      this.hostSide = hostSide;
      this.activity = new AtomicReference<>(activity);
   }

   long remoteSteamId() {
      return this.remoteSteamId;
   }

   int connectionId() {
      return this.connectionId;
   }

   int localPort() {
      return this.socket.getLocalPort();
   }

   boolean isHostSide() {
      return this.hostSide;
   }

   boolean isHostedBy(Object owner) {
      return this.hostOwner == owner;
   }

   boolean isClosed() {
      return this.closed.get();
   }

   void dedicatedSessionGeneration(long generation) {
      if (generation <= 0L || !this.dedicatedSessionGeneration.compareAndSet(0L, generation)) {
         throw new IllegalStateException("Dedicated session generation is already bound");
      }
   }

   long dedicatedSessionGeneration() {
      return this.dedicatedSessionGeneration.get();
   }

   void delayOutboundUntil(long deadlineMillis) {
      this.outboundNotBeforeMillis.accumulateAndGet(deadlineMillis, Math::max);
   }

   boolean isOutboundReady(long nowMillis) {
      return nowMillis >= this.outboundNotBeforeMillis.get();
   }

   void waitForPeerReadyUntil(long fallbackMillis) {
      this.outboundDataFallbackMillis.accumulateAndGet(fallbackMillis, Math::max);
   }

   void markPeerReady() {
      this.peerReady.set(true);
   }

   void requireAddonNegotiation() {
      this.addonNegotiationReady.set(false);
   }

   void markAddonNegotiated() {
      List<byte[]> pending;
      synchronized (this.addonNegotiationLock) {
         if (this.addonNegotiationReady.getAndSet(true)) {
            return;
         }

         pending = new ArrayList<>(this.preNegotiationData);
         this.preNegotiationData.clear();
         this.preNegotiationBytes = 0;
      }

      for (byte[] payload : pending) {
         if (!this.enqueueSteamData(payload)) {
            break;
         }
      }
   }

   boolean isOutboundDataReady(long nowMillis) {
      return this.addonNegotiationReady.get() && isPeerReadyOrFallbackReached(this.outboundDataFallbackMillis.get(), this.peerReady.get(), nowMillis);
   }

   static boolean isPeerReadyOrFallbackReached(long fallbackMillis, boolean peerReady, long nowMillis) {
      return fallbackMillis == 0L || peerReady || nowMillis >= fallbackMillis;
   }

   boolean markFirstOutboundData() {
      return this.outboundDataObserved.compareAndSet(false, true);
   }

   boolean markFirstInboundData() {
      return this.inboundDataObserved.compareAndSet(false, true);
   }

   void start() {
      if (this.started.compareAndSet(false, true) && !this.closed.get()) {
         this.writerThread = this.daemonThread(this::writeLoop, "e4steam-steam-local-writer");
         this.readerThread = this.daemonThread(this::readLoop, "e4steam-steam-local-reader");
         this.writerThread.start();
         this.readerThread.start();
      }
   }

   void acceptSteamData(byte[] payload) {
      if (!this.closed.get()) {
         synchronized (this.addonNegotiationLock) {
            if (!this.addonNegotiationReady.get()) {
               if (payload != null && payload.length != 0 && this.preNegotiationData.size() < 32 && this.preNegotiationBytes <= 1048576 - payload.length) {
                  this.preNegotiationData.add((byte[])payload.clone());
                  this.preNegotiationBytes += payload.length;
                  return;
               }

               this.closeForSlowOrInvalidPeer();
               return;
            }
         }

         this.enqueueSteamData(payload);
      }
   }

   private boolean enqueueSteamData(byte[] payload) {
      if (this.closed.get()) {
         return false;
      } else if (this.inboundFinQueued.get()) {
         this.closeForSlowOrInvalidPeer();
         return false;
      } else if (!this.inboundDataSlots.tryAcquire()) {
         this.closeForSlowOrInvalidPeer();
         return false;
      } else if (!this.inbound.offer(new SteamConnectionBridge.InboundData(payload))) {
         this.inboundDataSlots.release();
         this.closeForSlowOrInvalidPeer();
         return false;
      } else {
         return true;
      }
   }

   private void closeForSlowOrInvalidPeer() {
      if (!this.closed.get()) {
         LOGGER.warn(
            "Closing Steam bridge {}:{} because its local TCP consumer is too slow or sent data after FIN",
            Long.toUnsignedString(this.remoteSteamId),
            Integer.toUnsignedString(this.connectionId)
         );
         this.close(true);
      }
   }

   void acceptRemoteFin() {
      if (!this.closed.get() && this.inboundFinQueued.compareAndSet(false, true)) {
         this.beginGracefulClose();
         if (!this.inbound.offer(SteamConnectionBridge.InboundFin.INSTANCE)) {
            this.close(true);
         }
      }
   }

   void resetFromRemote() {
      this.close(false);
   }

   void markFinSubmitted() {
      if (this.outboundFinQueued.get()) {
         this.outboundFinSubmitted.set(true);
         this.closeIfFullyFinished();
      }
   }

   void markResetSubmitted() {
      this.runtime.unregister(this);
   }

   void close(boolean notifyRemote) {
      if (this.closed.compareAndSet(false, true)) {
         try {
            this.runtime.closeUdpBridge(this);

            try {
               this.socket.close();
            } catch (IOException var9) {
            }

            this.inbound.clear();
            synchronized (this.addonNegotiationLock) {
               this.preNegotiationData.clear();
               this.preNegotiationBytes = 0;
            }

            Thread reader = this.readerThread;
            if (reader != null) {
               reader.interrupt();
            }

            Thread writer = this.writerThread;
            if (writer != null) {
               writer.interrupt();
            }

            if (!notifyRemote || !this.runtime.sendReset(this)) {
               this.runtime.unregister(this);
            }
         } finally {
            this.releaseActivity();
         }
      }
   }

   void releaseActivity() {
      AutoCloseable activityToClose = this.activity.getAndSet(null);
      if (activityToClose != null) {
         try {
            activityToClose.close();
         } catch (Exception var3) {
            LOGGER.warn("Could not release Steam bridge activity", var3);
         }
      }
   }

   private void readLoop() {
      byte[] buffer = new byte[32768];

      try {
         InputStream input = this.socket.getInputStream();

         while (!this.closed.get()) {
            int read = input.read(buffer);
            if (read < 0) {
               if (this.outboundFinQueued.compareAndSet(false, true)) {
                  this.beginGracefulClose();
                  if (!this.runtime.sendFin(this)) {
                     this.close(true);
                  }
               }

               return;
            }

            if (read != 0) {
               byte[] payload = Arrays.copyOf(buffer, read);
               this.sendDataWithBackpressure(payload);
            }
         }
      } catch (IOException var5) {
         if (!this.closed.get()) {
            LOGGER.debug("Local TCP reader for a Steam bridge stopped", var5);
            this.close(true);
         }
      }
   }

   private void sendDataWithBackpressure(byte[] payload) throws IOException {
      long deadline = System.currentTimeMillis() + 30000L;

      while (!this.closed.get()) {
         if (this.runtime.sendData(this, payload)) {
            return;
         }

         if (System.currentTimeMillis() >= deadline) {
            throw new IOException("Steam outbound queue remained full for 30 seconds");
         }

         try {
            Thread.sleep(10L);
         } catch (InterruptedException var5) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for Steam outbound capacity", var5);
         }
      }

      throw new IOException("Steam bridge closed while waiting for outbound capacity");
   }

   private void writeLoop() {
      try {
         OutputStream output = this.socket.getOutputStream();

         while (!this.closed.get()) {
            SteamConnectionBridge.InboundFrame frame = this.inbound.take();
            if (!(frame instanceof SteamConnectionBridge.InboundData data)) {
               output.flush();
               this.socket.shutdownOutput();
               this.inboundFinished.set(true);
               this.closeIfFullyFinished();
               return;
            }

            this.inboundDataSlots.release();
            output.write(data.payload());
            output.flush();
         }
      } catch (InterruptedException var4) {
         Thread.currentThread().interrupt();
      } catch (IOException var5) {
         if (!this.closed.get()) {
            LOGGER.debug("Local TCP writer for a Steam bridge stopped", var5);
            this.close(true);
         }
      }
   }

   private void closeIfFullyFinished() {
      if (this.outboundFinSubmitted.get() && this.inboundFinished.get()) {
         this.close(false);
      }
   }

   void closeIfGracefulCloseTimedOut(long nowMillis) {
      long deadline = this.gracefulCloseDeadlineMillis.get();
      if (!this.closed.get() && isGracefulCloseExpired(deadline, nowMillis)) {
         LOGGER.debug(
            "Forcing cleanup of stale Steam bridge {}:{} after graceful FIN timeout",
            Long.toUnsignedString(this.remoteSteamId),
            Integer.toUnsignedString(this.connectionId)
         );
         this.close(true);
      }
   }

   private void beginGracefulClose() {
      long deadline = System.currentTimeMillis() + 2000L;
      this.gracefulCloseDeadlineMillis.accumulateAndGet(deadline, Math::min);
   }

   static boolean isGracefulCloseExpired(long deadlineMillis, long nowMillis) {
      return deadlineMillis != Long.MAX_VALUE && nowMillis >= deadlineMillis;
   }

   private Thread daemonThread(Runnable action, String role) {
      Thread thread = new Thread(action, role + "-" + Long.toUnsignedString(this.remoteSteamId) + "-" + Integer.toUnsignedString(this.connectionId));
      thread.setDaemon(true);
      return thread;
   }

   private static final class InboundData implements SteamConnectionBridge.InboundFrame {
      private final byte[] payload;

      private InboundData(byte[] payload) {
         this.payload = payload;
      }

      private byte[] payload() {
         return this.payload;
      }
   }

   private static enum InboundFin implements SteamConnectionBridge.InboundFrame {
      INSTANCE;
   }

   private interface InboundFrame {
   }
}
