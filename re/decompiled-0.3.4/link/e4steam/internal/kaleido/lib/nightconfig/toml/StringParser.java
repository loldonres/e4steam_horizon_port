package link.e4steam.internal.kaleido.lib.nightconfig.toml;

import link.e4steam.internal.kaleido.lib.nightconfig.core.io.CharacterInput;
import link.e4steam.internal.kaleido.lib.nightconfig.core.io.CharsWrapper;
import link.e4steam.internal.kaleido.lib.nightconfig.core.io.ParsingException;
import link.e4steam.internal.kaleido.lib.nightconfig.core.io.Utils;

final class StringParser {
   private static final char[] SINGLE_QUOTE_OR_NEWLINE = new char[]{'\'', '\n', '\r'};

   static String parseBasic(CharacterInput input, TomlParser parser) {
      CharsWrapper.Builder builder = parser.createBuilder();
      boolean escape = false;

      char c;
      while ((c = input.readChar()) != '"' || escape) {
         if (escape) {
            builder.write(unescape(c, input));
            escape = false;
         } else if (c != '\\') {
            if (c == '\n' || c == '\r') {
               throw new ParsingException(
                  "Invalid newline in basic string, you should use a multiline string or escape the newline by writing \\n. The string begins with: \""
                     + builder
                     + "\""
               );
            }

            if (c != '\t' && Toml.isControlChar(c)) {
               String properEscape = "\\u" + Integer.toHexString(c).toUpperCase();
               throw new ParsingException("Invalid control character '" + c + "' in string, you should escape it by writing " + properEscape);
            }

            builder.write(c);
         } else {
            escape = true;
         }
      }

      return builder.toString();
   }

   static String parseLiteral(CharacterInput input, TomlParser parser) {
      String str = input.readCharsUntil(SINGLE_QUOTE_OR_NEWLINE).toString();
      char end = input.readChar();
      if (end != '\'') {
         throw new ParsingException("Invalid newline in literal string, you should use a multiline string. The string is '" + str + "'");
      } else {
         str.codePoints()
            .forEach(
               codePoint -> {
                  if (codePoint != 9 && Toml.isControlChar(codePoint)) {
                     String properEscape = "\\u" + Integer.toHexString(codePoint).toUpperCase();
                     CharsWrapper display = new CharsWrapper(Character.toChars(codePoint));
                     throw new ParsingException(
                        "Invalid control character '" + display + "' in literal string '" + str + "', you should escape it by writing " + properEscape
                     );
                  }
               }
            );
         return str;
      }
   }

   static String parseMultiBasic(CharacterInput input, TomlParser parser) {
      CharsWrapper.Builder builder = parser.createBuilder();

      char c;
      while ((c = input.readChar()) != '"' || input.peek() != 34 || input.peek(1) != 34) {
         if (c == '\\') {
            char next = input.readChar();
            if (next != '\n' && (next != '\r' || input.peekChar() != '\n') && (next != '\t' && next != ' ' || !isWhitespace(Toml.readLine(input)))) {
               if (next == '\t' || next == ' ') {
                  throw new ParsingException("Invalid escapement: \\" + next);
               }

               builder.write(unescape(next, input));
            } else {
               char nextNonSpace = Toml.readNonSpaceChar(input, true);
               input.pushBack(nextNonSpace);
            }
         } else {
            if (c != '\n' && c != '\r' && c != '\t' && Toml.isControlChar(c)) {
               String properEscape = "\\u" + Integer.toHexString(c).toUpperCase();
               throw new ParsingException("Invalid control character '" + c + "' in multiline string, you should escape it by writing " + properEscape);
            }

            builder.write(c);
         }
      }

      input.skipPeeks();
      if (input.peek() == 34) {
         input.skipPeeks();
         builder.write('"');
      }

      if (input.peek() == 34) {
         input.skipPeeks();
         builder.write('"');
      }

      return buildMultilineString(builder);
   }

   static String parseMultiLiteral(CharacterInput input, TomlParser parser) {
      CharsWrapper.Builder builder = parser.createBuilder();

      char c;
      while ((c = input.readChar()) != '\'' || input.peek() != 39 || input.peek(1) != 39) {
         if (c != '\n' && c != '\r' && c != '\t' && Toml.isControlChar(c)) {
            String properEscape = "\\u" + Integer.toHexString(c).toUpperCase();
            throw new ParsingException("Invalid control character '" + c + "' in multiline literal string, you should escape it by writing " + properEscape);
         }

         builder.append(c);
      }

      input.skipPeeks();
      if (input.peek() == 39) {
         input.skipPeeks();
         builder.write('\'');
      }

      if (input.peek() == 39) {
         input.skipPeeks();
         builder.write('\'');
      }

      return buildMultilineString(builder);
   }

   private static String buildMultilineString(CharsWrapper.Builder builder) {
      if (builder.get(0) == '\n') {
         return builder.toString(1);
      } else {
         return builder.get(0) == '\r' && builder.get(1) == '\n' ? builder.toString(2) : builder.toString();
      }
   }

   private static String unescape(char c, CharacterInput input) {
      switch (c) {
         case '"':
         case '\\':
            return String.valueOf(c);
         case 'U': {
            CharsWrapper chars = input.readChars(8);
            return parseUnicodeCodepoint(chars);
         }
         case 'b':
            return "\b";
         case 'f':
            return "\f";
         case 'n':
            return "\n";
         case 'r':
            return "\r";
         case 't':
            return "\t";
         case 'u': {
            CharsWrapper chars = input.readChars(4);
            return parseUnicodeCodepoint(chars);
         }
         default:
            throw new ParsingException("Invalid escapement: \\" + c);
      }
   }

   private static String parseUnicodeCodepoint(CharsWrapper chars) {
      try {
         int codePoint = Utils.parseInt(chars, 16);
         if (!Toml.isValidCodePoint(codePoint)) {
            throw new ParsingException("Invalid unicode codepoint: " + chars);
         } else {
            return new String(new int[]{codePoint}, 0, 1);
         }
      } catch (IllegalArgumentException var2) {
         throw new ParsingException("Invalid unicode codepoint: " + chars, var2);
      }
   }

   private static boolean isWhitespace(CharSequence csq) {
      for (int i = 0; i < csq.length(); i++) {
         char c = csq.charAt(i);
         if (c != '\t' && c != ' ') {
            return false;
         }
      }

      return true;
   }

   private StringParser() {
   }
}
