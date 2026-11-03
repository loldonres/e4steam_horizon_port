package link.e4steam.internal.kaleido.lib.quiltconfig.api.exceptions;

public final class ConfigCreationException extends RuntimeException {
   public ConfigCreationException() {
   }

   public ConfigCreationException(String message) {
      super(message);
   }

   public ConfigCreationException(String message, Throwable cause) {
      super(message, cause);
   }

   public ConfigCreationException(Throwable cause) {
      super(cause);
   }
}
