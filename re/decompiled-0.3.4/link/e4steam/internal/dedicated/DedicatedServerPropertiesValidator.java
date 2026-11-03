package link.e4steam.internal.dedicated;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Properties;

public final class DedicatedServerPropertiesValidator {
   private DedicatedServerPropertiesValidator() {
   }

   public static DedicatedServerPropertiesValidator.Validation validate(Properties properties) {
      Properties source = properties == null ? new Properties() : properties;
      String bind = source.getProperty("server-ip", "").trim();
      if (bind.isEmpty()) {
         return DedicatedServerPropertiesValidator.Validation.deny("SERVER_IP_MUST_BE_LOOPBACK");
      } else {
         try {
            InetAddress address = InetAddress.getByName(bind);
            if (!address.isLoopbackAddress()) {
               return DedicatedServerPropertiesValidator.Validation.deny("SERVER_IP_MUST_BE_LOOPBACK");
            }
         } catch (UnknownHostException var4) {
            return DedicatedServerPropertiesValidator.Validation.deny("SERVER_IP_INVALID");
         }

         if (Boolean.parseBoolean(source.getProperty("enable-rcon", "false"))) {
            return DedicatedServerPropertiesValidator.Validation.deny("RCON_MUST_BE_DISABLED");
         } else {
            return Boolean.parseBoolean(source.getProperty("enable-query", "false"))
               ? DedicatedServerPropertiesValidator.Validation.deny("QUERY_MUST_BE_DISABLED")
               : DedicatedServerPropertiesValidator.Validation.allow();
         }
      }
   }

   public static final class Validation {
      private final boolean allowed;
      private final String category;

      private Validation(boolean allowed, String category) {
         this.allowed = allowed;
         this.category = category;
      }

      static DedicatedServerPropertiesValidator.Validation allow() {
         return new DedicatedServerPropertiesValidator.Validation(true, "");
      }

      static DedicatedServerPropertiesValidator.Validation deny(String category) {
         return new DedicatedServerPropertiesValidator.Validation(false, category);
      }

      public boolean allowed() {
         return this.allowed;
      }

      public String category() {
         return this.category;
      }

      @Override
      public String toString() {
         return "DedicatedPropertiesValidation{" + (this.allowed ? "allowed" : this.category) + "}";
      }
   }
}
