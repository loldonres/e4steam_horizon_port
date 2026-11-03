package link.e4steam;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public final class E4steamConstants {
   public static final String MOD_ID = "e4steam";
   private static final String MOD_VERSION = loadModVersion();

   private E4steamConstants() {
   }

   public static String modVersion() {
      return MOD_VERSION;
   }

   private static String loadModVersion() {
      Properties properties = new Properties();

      try {
         label64: {
            String var3;
            try (InputStream input = E4steamConstants.class.getClassLoader().getResourceAsStream("e4steam-version.properties")) {
               if (input == null) {
                  break label64;
               }

               properties.load(input);
               String value = properties.getProperty("version", "").trim();
               if (!value.matches("[0-9A-Za-z][0-9A-Za-z.+_-]{0,63}")) {
                  break label64;
               }

               var3 = value;
            }

            return var3;
         }
      } catch (IOException var6) {
      }

      Package owner = E4steamConstants.class.getPackage();
      String implementation = owner == null ? null : owner.getImplementationVersion();
      return implementation != null && !implementation.trim().isEmpty() ? implementation.trim() : "unknown";
   }
}
