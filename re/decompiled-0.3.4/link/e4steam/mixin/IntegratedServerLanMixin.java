package link.e4steam.mixin;

import link.e4steam.E4steamClient;
import link.e4steam.lan.LanPlayerConnection;
import link.e4steam.lan.LanSettings;
import link.e4steam.steam.SteamMinecraftAuthentication;
import link.e4steam.steam.SteamSession;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin({IntegratedServer.class})
public abstract class IntegratedServerLanMixin {
   @Inject(
      method = {"/^getMaxPlayers$/"},
      at = {@At("HEAD")},
      cancellable = true,
      require = 0,
      remap = false
   )
   private void e4steam$modernCapacity(CallbackInfoReturnable<Integer> cir) {
      LanSettings.Snapshot settings = LanSettings.active(this);
      if (settings != null) {
         cir.setReturnValue(settings.maxPlayers());
      }
   }

   @Inject(
      method = {"/^unpublishServer$/"},
      at = {@At("RETURN")},
      require = 0,
      remap = false
   )
   private void e4steam$stopUnpublishedWorld(CallbackInfoReturnable<Boolean> cir) {
      if (Boolean.TRUE.equals(cir.getReturnValue())) {
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

   @Inject(
      method = {"tickServer(Ljava/util/function/BooleanSupplier;)V"},
      at = {@At("TAIL")}
   )
   private void e4steam$countLanPlayers(CallbackInfo ci) {
      if (LanSettings.active(this) != null) {
         MinecraftServer server = (MinecraftServer)this;
         int ordinary = 0;

         for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.connection == null || SteamMinecraftAuthentication.authenticatedPeer(LanPlayerConnection.remoteAddress(player.connection)) == 0L) {
               ordinary++;
            }
         }

         LanSettings.nonSteamPlayers(this, ordinary);
      }
   }
}
