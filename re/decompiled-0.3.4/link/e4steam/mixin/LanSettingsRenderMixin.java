package link.e4steam.mixin;

import link.e4steam.lan.LanSettingsScreen;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin({Screen.class})
public abstract class LanSettingsRenderMixin {
   @Inject(
      method = {"/^(render|method_25394|m_88315_|m_6305_)$/"},
      at = {@At("HEAD")},
      require = 0,
      remap = false
   )
   private void e4steam$panel(@Coerce Object graphics, int x, int y, float delta, CallbackInfo ci) {
      if (this instanceof LanSettingsScreen) {
         ((LanSettingsScreen)this).renderLanPanel(graphics);
      }
   }

   @Inject(
      method = {"/^(render|method_25394|m_88315_|m_6305_)$/"},
      at = {@At("TAIL")},
      require = 0,
      remap = false
   )
   private void e4steam$indicators(@Coerce Object graphics, int x, int y, float delta, CallbackInfo ci) {
      if (this instanceof LanSettingsScreen) {
         ((LanSettingsScreen)this).renderLanIndicators(graphics);
      }
   }

   @Inject(
      method = {"extractRenderState"},
      at = {@At("HEAD")},
      require = 0,
      remap = false
   )
   private void e4steam$panelExtracted(@Coerce Object graphics, int x, int y, float delta, CallbackInfo ci) {
      if (this instanceof LanSettingsScreen) {
         ((LanSettingsScreen)this).renderLanPanel(graphics);
      }
   }

   @Inject(
      method = {"extractRenderState"},
      at = {@At("TAIL")},
      require = 0,
      remap = false
   )
   private void e4steam$indicatorsExtracted(@Coerce Object graphics, int x, int y, float delta, CallbackInfo ci) {
      if (this instanceof LanSettingsScreen) {
         ((LanSettingsScreen)this).renderLanIndicators(graphics);
      }
   }
}
