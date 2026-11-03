package link.e4steam.internal.kaleido.lib.nightconfig.core.io;

public enum NewlineStyle {
   UNIX('\n'),
   WINDOWS('\r', '\n');

   public final char[] chars;

   private NewlineStyle(char... chars) {
      this.chars = chars;
   }

   public static NewlineStyle system() {
      String systemNewline = System.getProperty("line.separator");
      if (systemNewline.equals("\n")) {
         return UNIX;
      } else if (systemNewline.equals("\r\n")) {
         return WINDOWS;
      } else {
         throw new IllegalArgumentException("Unknown system line separator '" + systemNewline + "'. The NewlineStyle enum only supports LF and CRLF.");
      }
   }
}
