package link.e4steam.neoforge;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.loading.LoadingModList;

public class AgnosImpl {
   public static boolean isClient() {
      try {
         return FMLEnvironment.class.getMethod("getDist").invoke(null).equals(Dist.CLIENT);
      } catch (IllegalAccessException | InvocationTargetException | NullPointerException | NoSuchMethodException var2) {
         try {
            return FMLLoader.class.getMethod("getDist").invoke(null).equals(Dist.CLIENT);
         } catch (IllegalAccessException | InvocationTargetException | NullPointerException | NoSuchMethodException var1) {
            throw new RuntimeException("Can't determine dist!");
         }
      }
   }

   public static Path configDir() {
      return FMLPaths.CONFIGDIR.get();
   }

   public static boolean proactivelyAcceptSteamPeerSessions() {
      return true;
   }

   public static boolean autoRestartBrokenSteamSessionForHandshake() {
      return false;
   }

   public static boolean paceMinecraftTrafficForLoader() {
      return false;
   }

   public static Path jarPath() {
      Class<FMLLoader> clazz = FMLLoader.class;

      try {
         LoadingModList modList = (LoadingModList)clazz.getMethod("getLoadingModList").invoke(clazz.getMethod("getCurrent").invoke(null));
         return modList.getModFileById("e4steam").getFile().getFilePath();
      } catch (IllegalAccessException | InvocationTargetException | NullPointerException | NoSuchMethodException var3) {
         try {
            LoadingModList modListx = (LoadingModList)clazz.getMethod("getLoadingModList").invoke(null);
            return modListx.getModFileById("e4steam").getFile().getFilePath();
         } catch (IllegalAccessException | InvocationTargetException | NullPointerException | NoSuchMethodException var2) {
            throw new RuntimeException("Can't determine jar path!");
         }
      }
   }
}
