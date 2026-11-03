package link.e4steam.mixin;

import java.net.InetAddress;
import link.e4steam.Config;
import link.e4steam.E4steamClient;
import link.e4steam.lan.LanSettings;
import link.e4steam.steam.SteamAccessMode;
import link.e4steam.steam.SteamSession;
import net.minecraft.server.network.ServerConnectionListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({ServerConnectionListener.class})
public abstract class IntegratedServerConnectionListenerMixin {
   @Inject(
      method = {"startTcpServerListener(Ljava/net/InetAddress;I)V"},
      at = {@At("TAIL")}
   )
   private void e4steam$startSteamBridge(InetAddress address, int port, CallbackInfo ci) {
      SteamSession previous = E4steamClient.session;
      if (previous != null) {
         previous.stop();
         if (E4steamClient.session == previous) {
            E4steamClient.session = null;
         }
      }

      if (Config.INSTANCE.hostEnabled.value() && E4steamClient.selectedAccessMode != SteamAccessMode.LOCAL_ONLY) {
         SteamSession session = new SteamSession(port, E4steamClient.selectedAccessMode, E4steamClient.selectedCustomAccessMode);
         E4steamClient.session = session;
         session.startAsync();
      }
   }

   @Inject(
      method = {"stop()V"},
      at = {@At("TAIL")}
   )
   private void e4steam$stopSteamBridge(CallbackInfo ci) {
      LanSettings.closed();
      SteamSession session = E4steamClient.session;
      if (session != null) {
         session.stop();
         if (E4steamClient.session == session) {
            E4steamClient.session = null;
         }
      }
   }
}
