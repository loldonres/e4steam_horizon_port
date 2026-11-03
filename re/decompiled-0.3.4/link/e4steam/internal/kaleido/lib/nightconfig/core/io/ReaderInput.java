package link.e4steam.internal.kaleido.lib.nightconfig.core.io;

import java.io.IOException;
import java.io.Reader;

public final class ReaderInput extends AbstractInput {
   private final Reader reader;

   public ReaderInput(Reader reader) {
      this.reader = reader;
   }

   @Override
   protected int directRead() {
      try {
         return this.reader.read();
      } catch (IOException var2) {
         throw ParsingException.readFailed(var2);
      }
   }

   @Override
   protected char directReadChar() throws ParsingException {
      int read;
      try {
         read = this.reader.read();
      } catch (IOException var3) {
         throw ParsingException.readFailed(var3);
      }

      if (read == -1) {
         throw ParsingException.notEnoughData();
      } else {
         return (char)read;
      }
   }

   @Override
   public CharsWrapper read(int n) {
      char[] array = new char[n];
      int offset = Math.min(this.deque.size(), n);
      CharsWrapper smaller = this.consumeDeque(array, offset, false);
      if (smaller != null) {
         return smaller;
      } else {
         int nRead;
         try {
            nRead = this.reader.read(array, offset, n - offset);
         } catch (IOException var7) {
            throw ParsingException.readFailed(var7);
         }

         return new CharsWrapper(array, 0, offset + nRead);
      }
   }

   @Override
   public CharsWrapper readChars(int n) {
      char[] array = new char[n];
      int offset = Math.min(this.deque.size(), n);
      this.consumeDeque(array, offset, true);
      int length = n - offset;

      int nRead;
      try {
         nRead = this.reader.read(array, offset, length);
      } catch (IOException var7) {
         throw ParsingException.readFailed(var7);
      }

      if (nRead != length) {
         throw ParsingException.notEnoughData();
      } else {
         return new CharsWrapper(array);
      }
   }
}
