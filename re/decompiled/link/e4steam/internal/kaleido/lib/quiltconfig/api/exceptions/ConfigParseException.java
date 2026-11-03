package link.e4steam.internal.kaleido.lib.quiltconfig.api.exceptions;

public final class ConfigParseException extends RuntimeException {
   public ConfigParseException() {
   }

   public ConfigParseException(String message) {
      super(message);
   }

   public ConfigParseException(String message, Throwable cause) {
      super(message, cause);
   }

   public ConfigParseException(Throwable cause) {
      super(cause);
   }
}
