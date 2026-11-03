package link.e4steam.steam;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

final class SteamLoopbackAuthentication {
   private SteamLoopbackAuthentication() {
   }

   static int loopbackPort(SocketAddress address) {
      if (!(address instanceof InetSocketAddress inetAddress)) {
         return -1;
      } else if (!inetAddress.isUnresolved() && inetAddress.getAddress() != null && inetAddress.getAddress().isLoopbackAddress()) {
         int port = inetAddress.getPort();
         return port > 0 && port <= 65535 ? port : -1;
      } else {
         return -1;
      }
   }
}
