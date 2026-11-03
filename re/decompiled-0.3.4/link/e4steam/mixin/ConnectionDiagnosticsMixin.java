package link.e4steam.mixin;

import io.netty.channel.ChannelHandlerContext;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicLong;
import link.e4steam.steam.SteamConnectionBridge;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import org.apache.logging.log4j.LogManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({Connection.class})
public abstract class ConnectionDiagnosticsMixin {
   @Unique
   private final AtomicLong e4steam$packets = new AtomicLong();
   @Unique
   private final AtomicLong e4steam$chunks = new AtomicLong();
   @Unique
   private final AtomicLong e4steam$positions = new AtomicLong();
   @Unique
   private final AtomicLong e4steam$teleportAcks = new AtomicLong();
   @Unique
   private final AtomicLong e4steam$keepalives = new AtomicLong();
   @Unique
   private long e4steam$nextLog;
   @Unique
   private final AtomicLong e4steam$errors = new AtomicLong();

   @Shadow
   public abstract SocketAddress getRemoteAddress();

   @Shadow
   public abstract boolean isMemoryConnection();

   @Inject(
      method = {"channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V"},
      at = {@At("HEAD")}
   )
   private void e4steam$decodedPacket(ChannelHandlerContext context, Packet<?> packet, CallbackInfo ci) {
      if (SteamConnectionBridge.NETWORK_DIAGNOSTICS) {
         long sequence = this.e4steam$packets.incrementAndGet();
         String name = packet.getClass().getSimpleName();
         if (!this.isMemoryConnection() && sequence <= 16L) {
            LogManager.getLogger("e4steam")
               .info(
                  "[E4NET-PACKET] connection={} sequence={} type={} autoRead={} active={}",
                  Integer.toHexString(System.identityHashCode(this)),
                  sequence,
                  name,
                  context.channel().config().isAutoRead(),
                  context.channel().isActive()
               );
         }

         if (name.equals("ClientboundLevelChunkWithLightPacket")) {
            this.e4steam$chunks.incrementAndGet();
         }

         if (name.equals("ClientboundPlayerPositionPacket")) {
            this.e4steam$positions.incrementAndGet();
         }

         if (name.equals("ServerboundAcceptTeleportationPacket")) {
            this.e4steam$teleportAcks.incrementAndGet();
         }

         if (name.equals("ClientboundKeepAlivePacket") || name.equals("ServerboundKeepAlivePacket")) {
            this.e4steam$keepalives.incrementAndGet();
         }
      }
   }

   @Inject(
      method = {"exceptionCaught(Lio/netty/channel/ChannelHandlerContext;Ljava/lang/Throwable;)V"},
      at = {@At("HEAD")}
   )
   private void e4steam$networkFailure(ChannelHandlerContext context, Throwable failure, CallbackInfo ci) {
      if (SteamConnectionBridge.NETWORK_DIAGNOSTICS && !this.isMemoryConnection()) {
         if (this.e4steam$errors.incrementAndGet() <= 4L) {
            Throwable cause = failure.getCause();
            LogManager.getLogger("e4steam")
               .warn(
                  "[E4NET-ERROR] connection={} exception={} cause={} autoRead={} active={}",
                  Integer.toHexString(System.identityHashCode(this)),
                  failure.getClass().getName(),
                  cause == null ? "none" : cause.getClass().getName(),
                  context.channel().config().isAutoRead(),
                  context.channel().isActive()
               );
         }
      }
   }

   @Inject(
      method = {"tick()V"},
      at = {@At("TAIL")}
   )
   private void e4steam$logDecodedPackets(CallbackInfo ci) {
      if (SteamConnectionBridge.NETWORK_DIAGNOSTICS && !this.isMemoryConnection() && this.e4steam$packets.get() != 0L) {
         long now = System.currentTimeMillis();
         if (now >= this.e4steam$nextLog) {
            this.e4steam$nextLog = now + 5000L;
            SocketAddress remote = this.getRemoteAddress();
            int bridgePort = remote instanceof InetSocketAddress ? ((InetSocketAddress)remote).getPort() : -1;
            LogManager.getLogger("e4steam")
               .info(
                  "[E4NET-MC] connection={} bridgePort={} decoded={} chunks={} positions={} teleportAcks={} keepalives={} errors={}",
                  Integer.toHexString(System.identityHashCode(this)),
                  bridgePort,
                  this.e4steam$packets.get(),
                  this.e4steam$chunks.get(),
                  this.e4steam$positions.get(),
                  this.e4steam$teleportAcks.get(),
                  this.e4steam$keepalives.get(),
                  this.e4steam$errors.get()
               );
         }
      }
   }
}
