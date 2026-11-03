package link.e4steam.internal.kaleido.lib.quiltconfig.api.exceptions;

public final class ConfigFieldException extends RuntimeException {
   public ConfigFieldException() {
   }

   public ConfigFieldException(String message) {
      super(message);
   }

   public ConfigFieldException(String message, Throwable cause) {
      super(message, cause);
   }

   public ConfigFieldException(Throwable cause) {
      super(cause);
   }
}
