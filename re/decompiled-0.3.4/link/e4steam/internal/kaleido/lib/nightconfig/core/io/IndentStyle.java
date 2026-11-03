package link.e4steam.internal.kaleido.lib.nightconfig.core.io;

public enum IndentStyle {
   TABS('\t'),
   SPACES_2(' ', ' '),
   SPACES_4(' ', ' ', ' ', ' '),
   SPACES_8(' ', ' ', ' ', ' ', ' ', ' ', ' ', ' '),
   NONE();

   public final char[] chars;

   private IndentStyle(char... chars) {
      this.chars = chars;
   }
}
