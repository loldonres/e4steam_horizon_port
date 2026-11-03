package link.e4steam.internal.kaleido.lib.nightconfig.core.io;

public interface CharacterInput {
   int read();

   char readChar();

   default int readAndSkip(char[] toSkip) {
      int c;
      do {
         c = this.read();
      } while (c != -1 && Utils.arrayContains(toSkip, (char)c));

      return c;
   }

   default char readCharAndSkip(char[] toSkip) {
      char c;
      do {
         c = this.readChar();
      } while (Utils.arrayContains(toSkip, c));

      return c;
   }

   default CharsWrapper read(int n) {
      CharsWrapper.Builder builder = new CharsWrapper.Builder(n);

      for (int i = 0; i < n; i++) {
         int next = this.read();
         if (next == -1) {
            break;
         }

         builder.append((char)next);
      }

      return builder.build();
   }

   default CharsWrapper readChars(int n) {
      char[] chars = new char[n];

      for (int i = 0; i < n; i++) {
         int next = this.read();
         if (next == -1) {
            throw ParsingException.notEnoughData();
         }

         chars[i] = (char)next;
      }

      return new CharsWrapper(chars);
   }

   CharsWrapper readUntil(char[] var1);

   CharsWrapper readCharsUntil(char[] var1);

   int peek();

   int peek(int var1);

   char peekChar();

   char peekChar(int var1);

   void skipPeeks();

   void pushBack(char var1);
}
