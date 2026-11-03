package link.e4steam.internal.kaleido.lib.nightconfig.core.io;

import link.e4steam.internal.kaleido.lib.nightconfig.core.utils.IntDeque;

public abstract class AbstractInput implements CharacterInput {
   protected final IntDeque deque = new IntDeque();

   protected abstract int directRead();

   protected abstract char directReadChar();

   @Override
   public int read() {
      return !this.deque.isEmpty() ? this.deque.removeFirst() : this.directRead();
   }

   @Override
   public char readChar() {
      if (!this.deque.isEmpty()) {
         int next = this.deque.removeFirst();
         if (next == -1) {
            throw ParsingException.notEnoughData();
         } else {
            return (char)next;
         }
      } else {
         return this.directReadChar();
      }
   }

   @Override
   public int peek() {
      if (this.deque.isEmpty()) {
         int read = this.directRead();
         this.deque.addLast(read);
         return read;
      } else {
         return this.deque.getFirst();
      }
   }

   @Override
   public int peek(int n) {
      int diff = n - this.deque.size();
      if (diff >= 0) {
         for (int i = 0; i <= diff; i++) {
            int read = this.directRead();
            this.deque.addLast(read);
            if (read == -1) {
               return -1;
            }
         }
      }

      return this.deque.get(n);
   }

   @Override
   public char peekChar() {
      int c = this.peek();
      if (c == -1) {
         throw ParsingException.notEnoughData();
      } else {
         return (char)c;
      }
   }

   @Override
   public char peekChar(int n) {
      int c = this.peek(n);
      if (c == -1) {
         throw ParsingException.notEnoughData();
      } else {
         return (char)c;
      }
   }

   @Override
   public void skipPeeks() {
      this.deque.clear();
   }

   @Override
   public void pushBack(char c) {
      this.deque.addFirst(c);
   }

   @Override
   public CharsWrapper readUntil(char[] stop) {
      CharsWrapper.Builder builder = new CharsWrapper.Builder(10);

      int c;
      for (c = this.read(); c != -1 && !Utils.arrayContains(stop, (char)c); c = this.read()) {
         builder.append((char)c);
      }

      this.deque.addFirst(c);
      return builder.build();
   }

   @Override
   public CharsWrapper readCharsUntil(char[] stop) {
      CharsWrapper.Builder builder = new CharsWrapper.Builder(10);

      char c;
      for (c = this.readChar(); !Utils.arrayContains(stop, c); c = this.readChar()) {
         builder.append(c);
      }

      this.deque.addFirst(c);
      return builder.build();
   }

   protected CharsWrapper consumeDeque(char[] array, int offset, boolean mustReadAll) {
      for (int i = 0; i < offset; i++) {
         int next = this.deque.removeFirst();
         if (next == -1) {
            if (mustReadAll) {
               throw ParsingException.notEnoughData();
            }

            return new CharsWrapper(array, 0, i);
         }

         array[i] = (char)next;
      }

      return null;
   }
}
