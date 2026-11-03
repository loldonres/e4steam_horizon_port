package link.e4steam.internal.kaleido.lib.nightconfig.core.utils;

import java.io.Reader;
import java.util.Objects;

public final class FastStringReader extends Reader {
   private final String str;
   private final int lim;
   private int cursor = 0;
   private int mark;

   public FastStringReader(String str, int lim) {
      if (lim <= str.length() && lim >= 0) {
         this.str = Objects.requireNonNull(str, "The string must not be null.");
         this.lim = lim;
      } else {
         throw new IllegalArgumentException("Invalid limit " + lim + ": must be >= 0 and < str.length()");
      }
   }

   public FastStringReader(String str) {
      this.str = Objects.requireNonNull(str, "The string must not be null.");
      this.lim = str.length();
   }

   @Override
   public int read() {
      return this.cursor < this.lim ? this.str.charAt(this.cursor++) : -1;
   }

   @Override
   public int read(char[] cbuf, int off, int len) {
      if (this.cursor == this.lim) {
         return -1;
      } else {
         len = Math.min(len, this.lim - this.cursor);
         int srcEnd = this.cursor + len;
         this.str.getChars(this.cursor, srcEnd, cbuf, off);
         this.cursor = srcEnd;
         return len;
      }
   }

   @Override
   public long skip(long n) {
      int skip = (int)n;
      this.cursor += skip;
      return skip;
   }

   @Override
   public boolean markSupported() {
      return true;
   }

   @Override
   public void mark(int readAheadLimit) {
      this.mark = this.cursor;
   }

   @Override
   public void reset() {
      this.cursor = this.mark;
   }

   @Override
   public boolean ready() {
      return true;
   }

   @Override
   public void close() {
   }
}
