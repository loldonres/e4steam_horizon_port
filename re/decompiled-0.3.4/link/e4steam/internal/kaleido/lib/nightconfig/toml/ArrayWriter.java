package link.e4steam.internal.kaleido.lib.nightconfig.toml;

import java.util.Iterator;
import java.util.List;
import link.e4steam.internal.kaleido.lib.nightconfig.core.io.CharacterOutput;

final class ArrayWriter {
   private static final char[] EMPTY_ARRAY = new char[]{'[', ']'};
   static final char[] ELEMENT_SEPARATOR = new char[]{',', ' '};

   static void write(List<?> values, CharacterOutput output, TomlWriter writer) {
      if (values.isEmpty()) {
         output.write(EMPTY_ARRAY);
      } else {
         output.write('[');
         boolean indent = writer.writesIndented(values);
         if (indent) {
            writer.increaseIndentLevel();
         }

         Iterator<?> iterator = values.iterator();
         boolean hasNext = iterator.hasNext();

         while (hasNext) {
            if (indent) {
               writer.writeNewline(output);
               writer.writeIndent(output);
            }

            Object value = iterator.next();
            ValueWriter.write(value, output, writer);
            if (hasNext = iterator.hasNext()) {
               if (indent) {
                  output.write(',');
               } else {
                  output.write(ELEMENT_SEPARATOR);
               }
            }
         }

         if (indent) {
            writer.decreaseIndentLevel();
            writer.writeNewline(output);
            writer.writeIndent(output);
         }

         output.write(']');
      }
   }

   private ArrayWriter() {
   }
}
