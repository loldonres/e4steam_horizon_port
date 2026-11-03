package link.e4steam.mixin;

import java.net.InetAddress;
import link.e4steam.E4steamDedicated;
import net.minecraft.server.network.ServerConnectionListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({ServerConnectionListener.class})
public abstract class ServerConnectionListenerMixin {
   @Inject(
      method = {"startTcpServerListener(Ljava/net/InetAddress;I)V"},
      at = {@At("HEAD")}
   )
   private void e4steam$validateSteamOnlyIngress(InetAddress inetAddress, int port, CallbackInfo ci) {
      E4steamDedicated.validateMinecraftBind(inetAddress);
   }

   @Inject(
      method = {"startTcpServerListener(Ljava/net/InetAddress;I)V"},
      at = {@At("TAIL")}
   )
   private void e4steam$startSteamBridge(InetAddress inetAddress, int port, CallbackInfo ci) {
      E4steamDedicated.minecraftListening(inetAddress, port);
   }

   @Inject(
      method = {"stop()V"},
      at = {@At("TAIL")}
   )
   private void e4steam$stopSteamBridge(CallbackInfo ci) {
      E4steamDedicated.minecraftStopped();
   }
}
