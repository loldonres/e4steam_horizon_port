package link.e4steam.neoforge;

import java.lang.reflect.Method;
import link.e4steam.E4steamDedicated;
import link.e4steam.MinecraftVersion;
import link.e4steam.api.runtime.RuntimeMode;
import link.e4steam.internal.addon.AddonDiscoverySupport;
import link.e4steam.internal.api.RuntimeEnvironment;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

@Mod("e4steam")
public final class E4steamNeoForge {
   public E4steamNeoForge() {
      NeoForge.EVENT_BUS.register(this);
      String loaderVersion = versionOf(FMLLoader.class);
      if (!AgnosImpl.isClient()) {
         E4steamDedicated.init(
            new RuntimeEnvironment("neoforge", loaderVersion, MinecraftVersion.current(), RuntimeMode.DEDICATED_SERVER, true),
            AddonDiscoverySupport.serviceLoader(this.getClass().getClassLoader())
         );
      } else {
         initializeClient(loaderVersion);
      }
   }

   @SubscribeEvent
   public void serverStarted(ServerStartedEvent event) {
      E4steamDedicated.minecraftReady();
   }

   @SubscribeEvent
   public void serverStopping(ServerStoppingEvent event) {
      E4steamDedicated.minecraftStopped();
   }

   @SubscribeEvent
   public void registerCommands(RegisterCommandsEvent event) {
      E4steamDedicated.registerCommands(event.getDispatcher());
   }

   private static String versionOf(Class<?> type) {
      String version = type.getPackage() == null ? null : type.getPackage().getImplementationVersion();
      return version != null && !version.trim().isEmpty() ? version : "unknown";
   }

   private static void initializeClient(String loaderVersion) {
      try {
         Class<?> bootstrap = Class.forName("link.e4steam.neoforge.E4steamClientNeoForge", true, E4steamNeoForge.class.getClassLoader());
         Method initialize = bootstrap.getDeclaredMethod("initialize", String.class);
         initialize.invoke(null, loaderVersion);
      } catch (ReflectiveOperationException var3) {
         throw new IllegalStateException("Could not initialize the e4steam NeoForge client", var3);
      }
   }
}
