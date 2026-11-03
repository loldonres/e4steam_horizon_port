package link.e4steam.steam;

import java.net.SocketAddress;
import link.e4steam.E4steamDedicated;

public final class SteamMinecraftAuthentication {
   private SteamMinecraftAuthentication() {
   }

   public static long authenticatedPeer(SocketAddress remoteAddress) {
      long dedicated = E4steamDedicated.authenticatedMinecraftPeer(remoteAddress);
      return dedicated != 0L ? dedicated : SteamClientApiBridge.authenticatedMinecraftPeer(remoteAddress);
   }

   public static boolean rejectUntrustedDedicatedIngress(SocketAddress remoteAddress) {
      return E4steamDedicated.requiresAuthenticatedIngress() && E4steamDedicated.authenticatedMinecraftPeer(remoteAddress) == 0L;
   }
}
