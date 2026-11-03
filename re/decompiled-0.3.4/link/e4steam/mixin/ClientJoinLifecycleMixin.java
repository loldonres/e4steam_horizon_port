package link.e4steam.mixin;

import link.e4steam.lan.LanPlayerConnection;
import link.e4steam.steam.SteamRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({ClientPacketListener.class})
public abstract class ClientJoinLifecycleMixin {
   @Inject(
      method = {"handleLogin(Lnet/minecraft/network/protocol/game/ClientboundLoginPacket;)V"},
      at = {@At("TAIL")},
      require = 0
   )
   private void e4steam$gameLoginCompleted(CallbackInfo ci) {
      if (Minecraft.getInstance().isSameThread()) {
         SteamRuntime.get().clientMinecraftJoined(LanPlayerConnection.clientRemoteAddress(this));
      }
   }
}
