package link.e4steam.internal.kaleido.lib.nightconfig.core.io;

public interface CharacterOutput {
   void write(char var1);

   default void write(char... chars) {
      this.write(chars, 0, chars.length);
   }

   void write(char[] var1, int var2, int var3);

   default void write(String s) {
      this.write(s, 0, s.length());
   }

   void write(String var1, int var2, int var3);

   default void write(CharsWrapper cw) {
      this.write(cw.chars, cw.offset, cw.limit - cw.offset);
   }
}
