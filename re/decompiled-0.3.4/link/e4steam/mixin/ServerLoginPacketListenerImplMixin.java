package link.e4steam.mixin;

import com.mojang.authlib.GameProfile;
import java.lang.reflect.Field;
import link.e4steam.lan.LanReflection;
import link.e4steam.steam.SteamMinecraftAuthentication;
import link.e4steam.steam.SteamMinecraftProfile;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({ServerLoginPacketListenerImpl.class})
public abstract class ServerLoginPacketListenerImplMixin {
   private static final int E4STEAM_LOGIN_TIMEOUT_TICKS = 2400;
   @Shadow
   @Final
   private Connection connection;
   private long e4steam$authenticatedSteamId;

   private long e4steam$authenticatedSteamId() {
      long current = this.e4steam$authenticatedSteamId;
      if (current != 0L) {
         return current;
      } else {
         current = SteamMinecraftAuthentication.authenticatedPeer(this.connection.getRemoteAddress());
         if (current != 0L) {
            this.e4steam$authenticatedSteamId = current;
         }

         return current;
      }
   }

   @Redirect(
      method = {"handleHello(Lnet/minecraft/network/protocol/login/ServerboundHelloPacket;)V"},
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/server/MinecraftServer;usesAuthentication()Z"
      )
   )
   private boolean e4steam$useSteamAuthentication(MinecraftServer server) {
      long authenticatedSteamId = this.e4steam$authenticatedSteamId();
      return authenticatedSteamId == 0L && server.usesAuthentication();
   }

   @Inject(
      method = {"handleHello(Lnet/minecraft/network/protocol/login/ServerboundHelloPacket;)V"},
      at = {@At("HEAD")},
      cancellable = true
   )
   private void e4steam$rejectDirectDedicatedLogin(CallbackInfo ci) {
      if (this.e4steam$authenticatedSteamId() == 0L && SteamMinecraftAuthentication.rejectUntrustedDedicatedIngress(this.connection.getRemoteAddress())) {
         this.connection.disconnect(Component.literal("This server requires an authenticated e4steam connection"));
         ci.cancel();
      }
   }

   @ModifyVariable(
      method = {"startClientVerification(Lcom/mojang/authlib/GameProfile;)V"},
      at = @At("HEAD"),
      argsOnly = true,
      ordinal = 0,
      require = 0
   )
   private GameProfile e4steam$bindProfileToSteamIdentity(GameProfile original) {
      long authenticatedSteamId = this.e4steam$authenticatedSteamId();
      return authenticatedSteamId == 0L ? original : SteamMinecraftProfile.bind(original, authenticatedSteamId);
   }

   @Inject(
      method = {"/^(handleAcceptedLogin|acceptPlayer|method_14384|m_10055_)$/"},
      at = {@At("HEAD")},
      require = 0,
      remap = false
   )
   private void e4steam$bindOlderProfile(CallbackInfo ci) {
      long peer = this.e4steam$authenticatedSteamId();
      if (peer != 0L) {
         Field field = LanReflection.field(this, "gameProfile", "profile", "field_14160", "f_10021_");
         if (field != null) {
            try {
               GameProfile original = (GameProfile)field.get(this);
               field.set(this, SteamMinecraftProfile.bind(original, peer));
            } catch (IllegalAccessException var6) {
               throw new IllegalStateException("Cannot bind accepted Steam profile", var6);
            }
         }
      }
   }

   @ModifyConstant(
      method = {"tick()V"},
      constant = {@Constant(
         intValue = 600
      )},
      require = 0
   )
   private int e4steam$extendAuthenticatedSteamLoginTimeout(int vanillaTimeout) {
      long authenticatedSteamId = this.e4steam$authenticatedSteamId();
      return authenticatedSteamId == 0L ? vanillaTimeout : 2400;
   }
}
