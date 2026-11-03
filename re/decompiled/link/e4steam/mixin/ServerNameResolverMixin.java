package link.e4steam.mixin;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Optional;
import link.e4steam.E4steamClient;
import link.e4steam.MinecraftUiCompat;
import link.e4steam.steam.SteamAddress;
import link.e4steam.steam.SteamClientBridge;
import link.e4steam.steam.SteamDedicatedAddress;
import link.e4steam.steam.SteamDedicatedClientBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddressResolver;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin({ServerNameResolver.class})
public class ServerNameResolverMixin {
   @Redirect(
      method = {"resolveAddress(Lnet/minecraft/client/multiplayer/resolver/ServerAddress;)Ljava/util/Optional;"},
      at = @At(
         value = "INVOKE",
         target = "Lnet/minecraft/client/multiplayer/resolver/ServerAddressResolver;resolve(Lnet/minecraft/client/multiplayer/resolver/ServerAddress;)Ljava/util/Optional;"
      )
   )
   private Optional<ResolvedServerAddress> e4steam$resolveSteamAddress(ServerAddressResolver instance, ServerAddress serverAddress) {
      Optional<SteamAddress> steamAddress = SteamAddress.tryParse(serverAddress.getHost());
      Optional<SteamDedicatedAddress> dedicatedAddress = SteamDedicatedAddress.tryParse(serverAddress.getHost());
      if (steamAddress.isEmpty() && dedicatedAddress.isEmpty()) {
         Minecraft minecraft = Minecraft.getInstance();
         if (MinecraftUiCompat.currentScreen(minecraft) instanceof ConnectScreen) {
            E4steamClient.stopSteamForDirectServerConnection();
         }

         return instance.resolve(serverAddress);
      } else {
         Minecraft minecraft = Minecraft.getInstance();
         if (!(MinecraftUiCompat.currentScreen(minecraft) instanceof ConnectScreen)) {
            return Optional.empty();
         } else {
            try {
               InetSocketAddress localAddress = steamAddress.isPresent()
                  ? SteamClientBridge.open(steamAddress.get())
                  : SteamDedicatedClientBridge.open(dedicatedAddress.get());
               return Optional.of(ResolvedServerAddress.from(localAddress));
            } catch (IOException var7) {
               E4steamClient.LOGGER.error("Failed to create the local Steam bridge for an e4steam endpoint", var7);
               return Optional.empty();
            }
         }
      }
   }
}
