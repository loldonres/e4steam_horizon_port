package link.e4steam.internal.kaleido.lib.quiltconfig.api.exceptions;

public final class TrackedValueException extends RuntimeException {
   public TrackedValueException() {
   }

   public TrackedValueException(String message) {
      super(message);
   }

   public TrackedValueException(String message, Throwable cause) {
      super(message, cause);
   }

   public TrackedValueException(Throwable cause) {
      super(cause);
   }
}
