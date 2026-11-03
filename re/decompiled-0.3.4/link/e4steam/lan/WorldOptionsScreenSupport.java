package link.e4steam.lan;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import link.e4steam.MinecraftUiCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

public final class WorldOptionsScreenSupport {
   private static final Map<Screen, Boolean> NATIVE_TAB = Collections.synchronizedMap(new WeakHashMap<>());

   private WorldOptionsScreenSupport() {
   }

   public static boolean consumeNativeWorldTab(Screen screen) {
      return NATIVE_TAB.remove(screen) != null;
   }

   public static void openNativeWorldTab(Minecraft minecraft, Screen interruptedScreen, Screen returnScreen) {
      try {
         ClassLoader loader = interruptedScreen.getClass().getClassLoader();
         Class<?> worldOptions = Class.forName("net.minecraft.client.gui.screens.WorldOptionsScreen", true, loader);
         Field levelField = worldOptions.getDeclaredField("level");
         levelField.setAccessible(true);
         Object level = levelField.get(interruptedScreen);
         Class<?> levelType = Class.forName("net.minecraft.world.level.Level", true, loader);
         Constructor<?> constructor = worldOptions.getDeclaredConstructor(Screen.class, levelType);
         constructor.setAccessible(true);
         Screen freshWorldOptions = (Screen)constructor.newInstance(returnScreen, level);
         NATIVE_TAB.put(freshWorldOptions, Boolean.TRUE);
         MinecraftUiCompat.setScreen(minecraft, freshWorldOptions);
      } catch (ReflectiveOperationException var10) {
         throw new IllegalStateException("Could not create the vanilla world-settings tab", var10);
      }
   }
}
