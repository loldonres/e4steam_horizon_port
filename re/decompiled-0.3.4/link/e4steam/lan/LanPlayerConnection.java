package link.e4steam.lan;

import java.lang.reflect.Method;
import java.net.SocketAddress;

public final class LanPlayerConnection {
   private LanPlayerConnection() {
   }

   public static SocketAddress clientRemoteAddress(Object listener) {
      Object connection = LanReflection.read(listener, "getConnection", "method_2872", "method_48296", "m_104910_", "connection", "field_3689", "f_104885_");
      return (SocketAddress)LanReflection.read(connection, "getRemoteAddress", "getSocketAddress", "method_10755", "func_74430_c", "m_129523_");
   }

   public static SocketAddress remoteAddress(Object listener) {
      Method direct = LanReflection.method(listener, 0, "getRemoteAddress", "method_48107", "m_264262_");
      if (direct != null) {
         return (SocketAddress)LanReflection.call(listener, direct);
      } else {
         Object connection = LanReflection.read(
            listener, "connection", "networkManager", "netManager", "field_14127", "field_45013", "field_147371_a", "f_9742_", "f_291338_"
         );
         return (SocketAddress)LanReflection.read(connection, "getRemoteAddress", "getSocketAddress", "method_10755", "func_74430_c", "m_129523_");
      }
   }
}
