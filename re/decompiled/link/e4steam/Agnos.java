package link.e4steam;

import dev.architectury.injectables.annotations.ExpectPlatform;
import dev.architectury.injectables.annotations.ExpectPlatform.Transformed;
import java.nio.file.Path;
import link.e4steam.neoforge.AgnosImpl;

public class Agnos {
   @ExpectPlatform
   @Transformed
   public static boolean isClient() {
      return AgnosImpl.isClient();
   }

   @ExpectPlatform
   @Transformed
   public static Path configDir() {
      return AgnosImpl.configDir();
   }

   @ExpectPlatform
   @Transformed
   public static Path jarPath() {
      return AgnosImpl.jarPath();
   }

   @ExpectPlatform
   @Transformed
   public static boolean proactivelyAcceptSteamPeerSessions() {
      return AgnosImpl.proactivelyAcceptSteamPeerSessions();
   }

   @ExpectPlatform
   @Transformed
   public static boolean autoRestartBrokenSteamSessionForHandshake() {
      return AgnosImpl.autoRestartBrokenSteamSessionForHandshake();
   }
}
