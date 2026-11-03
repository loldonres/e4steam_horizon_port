package link.e4steam.neoforge;

import java.util.Locale;
import link.e4steam.E4steamClient;
import link.e4steam.MinecraftVersion;
import link.e4steam.api.runtime.RuntimeMode;
import link.e4steam.internal.addon.AddonDiscoverySupport;
import link.e4steam.internal.api.RuntimeEnvironment;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

public final class E4steamClientNeoForge {
   private E4steamClientNeoForge(String loaderVersion) {
      E4steamClient.init(
         new RuntimeEnvironment(
            "neoforge",
            loaderVersion,
            MinecraftVersion.current(),
            RuntimeMode.CLIENT,
            !System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")
         ),
         AddonDiscoverySupport.serviceLoader(this.getClass().getClassLoader())
      );
      NeoForge.EVENT_BUS.register(this);
   }

   public static void initialize(String loaderVersion) {
      new E4steamClientNeoForge(loaderVersion);
   }

   @SubscribeEvent
   public void onRegisterCommandEvent(RegisterCommandsEvent event) {
      E4steamClient.registerCommands(event.getDispatcher());
   }
}
