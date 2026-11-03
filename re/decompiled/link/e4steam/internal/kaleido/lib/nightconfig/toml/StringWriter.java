package link.e4steam.internal.kaleido.lib.nightconfig.toml;

import java.util.Iterator;
import link.e4steam.internal.kaleido.lib.nightconfig.core.io.CharacterOutput;
import link.e4steam.internal.kaleido.lib.nightconfig.core.utils.StringUtils;

final class StringWriter {
   private static final char[] ESCAPED_B = new char[]{'\\', 'b'};
   private static final char[] ESCAPED_F = new char[]{'\\', 'f'};
   private static final char[] ESCAPED_N = new char[]{'\\', 'n'};
   private static final char[] ESCAPED_R = new char[]{'\\', 'r'};
   private static final char[] ESCAPED_T = new char[]{'\\', 't'};
   private static final char[] ESCAPED_BACKSLASH = new char[]{'\\', '\\'};
   private static final char[] ESCAPED_QUOTE = new char[]{'\\', '"'};

   static void writeBasic(String str, CharacterOutput output) {
      output.write('"');
      str.codePoints().forEach(c -> writeBasicChar(c, output));
      output.write('"');
   }

   static void writeBasicMultiline(String str, CharacterOutput output, TomlWriter writer) {
      output.write("\"\"\"");
      Iterator<String> it = StringUtils.splitLines(str).iterator();

      while (it.hasNext()) {
         String line = it.next();
         writer.writeNewline(output);
         char[] chars = line.toCharArray();

         for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            switch (c) {
               case '\b':
                  output.write(ESCAPED_B);
                  break;
               case '\f':
                  output.write(ESCAPED_F);
                  break;
               case '"':
                  if ((i + 1 != chars.length || it.hasNext())
                     && (i + 1 >= chars.length || chars[i + 1] != '"' || i + 2 >= chars.length || chars[i + 2] != '"' || i + 3 >= chars.length)) {
                     output.write(c);
                  } else {
                     output.write(ESCAPED_QUOTE);
                  }
                  break;
               case '\\':
                  output.write(ESCAPED_BACKSLASH);
                  break;
               default:
                  if (c != '\t' && c != '\n' && c != '\r' && Toml.isControlChar(c)) {
                     output.write(escapeUnicode(c));
                  } else {
                     output.write(Character.toChars(c));
                  }
            }
         }
      }

      output.write("\"\"\"");
   }

   static void writeLiteral(String str, CharacterOutput output) {
      output.write('\'');
      output.write(str);
      output.write('\'');
   }

   static void writeLiteralMultiline(String str, CharacterOutput output) {
      output.write("'''\n");
      output.write(str);
      output.write("''''");
   }

   private static void writeBasicChar(int c, CharacterOutput output) {
      switch (c) {
         case 8:
            output.write(ESCAPED_B);
            break;
         case 9:
            output.write(ESCAPED_T);
            break;
         case 10:
            output.write(ESCAPED_N);
            break;
         case 12:
            output.write(ESCAPED_F);
            break;
         case 13:
            output.write(ESCAPED_R);
            break;
         case 34:
            output.write(ESCAPED_QUOTE);
            break;
         case 92:
            output.write(ESCAPED_BACKSLASH);
            break;
         default:
            if (Toml.isControlChar(c)) {
               output.write(escapeUnicode(c));
            } else {
               output.write(Character.toChars(c));
            }
      }
   }

   static String escapeUnicode(int codePoint) {
      String hexa = Integer.toHexString(codePoint).toUpperCase();
      if (hexa.length() < 4) {
         while (hexa.length() < 4) {
            hexa = "0" + hexa;
         }
      } else if (hexa.length() < 8) {
         while (hexa.length() < 8) {
            hexa = "0" + hexa;
         }
      }

      return "\\u" + hexa;
   }

   private StringWriter() {
   }
}
