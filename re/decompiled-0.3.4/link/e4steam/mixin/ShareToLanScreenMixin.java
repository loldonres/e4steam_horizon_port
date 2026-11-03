package link.e4steam.mixin;

import link.e4steam.MinecraftUiCompat;
import link.e4steam.lan.LanSettingsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ShareToLanScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({ShareToLanScreen.class})
@Pseudo
public abstract class ShareToLanScreenMixin {
   @Inject(
      method = {"init()V"},
      at = {@At("HEAD")},
      cancellable = true
   )
   private void e4steam$openLanSettings(CallbackInfo ci) {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.getSingleplayerServer() != null) {
         ci.cancel();
         MinecraftUiCompat.setScreen(minecraft, new LanSettingsScreen(null));
      }
   }
}
