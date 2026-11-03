package link.e4steam.internal.kaleido.lib.nightconfig.core.io;

import java.io.IOException;
import java.io.Writer;

public final class WriterOutput implements CharacterOutput {
   private final Writer writer;

   public WriterOutput(Writer writer) {
      this.writer = writer;
   }

   @Override
   public void write(char c) {
      try {
         this.writer.write(c);
      } catch (IOException var3) {
         throw new WritingException(var3);
      }
   }

   @Override
   public void write(char[] chars, int offset, int length) {
      try {
         this.writer.write(chars, offset, length);
      } catch (IOException var5) {
         throw new WritingException(var5);
      }
   }

   @Override
   public void write(String s, int offset, int length) {
      try {
         this.writer.write(s, offset, length);
      } catch (IOException var5) {
         throw new WritingException(var5);
      }
   }
}
