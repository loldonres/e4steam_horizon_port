package link.e4steam.internal.kaleido.lib.nightconfig.core.io;

public class WritingException extends RuntimeException {
   public WritingException(String message) {
      super(message);
   }

   public WritingException(String message, Throwable cause) {
      super(message, cause);
   }

   public WritingException(Throwable cause) {
      this("Failed to write data: ", cause);
   }
}
