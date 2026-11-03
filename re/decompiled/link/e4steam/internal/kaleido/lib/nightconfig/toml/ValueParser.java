package link.e4steam.internal.kaleido.lib.nightconfig.toml;

import link.e4steam.internal.kaleido.lib.nightconfig.core.CommentedConfig;
import link.e4steam.internal.kaleido.lib.nightconfig.core.io.CharacterInput;
import link.e4steam.internal.kaleido.lib.nightconfig.core.io.CharsWrapper;
import link.e4steam.internal.kaleido.lib.nightconfig.core.io.ParsingException;
import link.e4steam.internal.kaleido.lib.nightconfig.core.io.Utils;

final class ValueParser {
   private static final char[] END_OF_VALUE = new char[]{'\t', ' ', '#', '\n', '\r', ',', ']', '}'};
   private static final char[] END_OF_VALUE_DATE = new char[]{'\t', '#', '\n', '\r', ',', ']', '}'};
   private static final char[] TRUE_END = new char[]{'r', 'u', 'e'};
   private static final char[] FALSE_END = new char[]{'a', 'l', 's', 'e'};
   private static final char[] ONLY_IN_FP_NUMBER = new char[]{'.', 'e', 'E'};
   private static final char[] FP_INFINITY = new char[]{'i', 'n', 'f'};
   private static final char[] FP_NAN = new char[]{'n', 'a', 'n'};

   static Object parse(CharacterInput input, char firstChar, TomlParser parser, CommentedConfig parentConfig) {
      switch (firstChar) {
         case '"':
            if (input.peek() == 34 && input.peek(1) == 34) {
               input.skipPeeks();
               return StringParser.parseMultiBasic(input, parser);
            }

            return StringParser.parseBasic(input, parser);
         case '\'':
            if (input.peek() == 39 && input.peek(1) == 39) {
               input.skipPeeks();
               return StringParser.parseMultiLiteral(input, parser);
            }

            return StringParser.parseLiteral(input, parser);
         case '+':
         case '-':
            input.pushBack(firstChar);
            return parseNumber(input.readUntil(END_OF_VALUE));
         case '[':
            return ArrayParser.parse(input, parser, parentConfig);
         case 'f':
            return parseFalse(input);
         case 't':
            return parseTrue(input);
         case '{':
            return TableParser.parseInline(input, parser, parentConfig.createSubConfig());
         default:
            input.pushBack(firstChar);
            CharsWrapper valueChars = input.readUntil(END_OF_VALUE_DATE);
            if (shouldBeTemporal(valueChars)) {
               return TemporalParser.parse(valueChars);
            } else {
               CharsWrapper trimmed = valueChars.trimmedView();
               if (trimmed.isEmpty()) {
                  throw new ParsingException("Invalid value containing only whitespaces");
               } else {
                  return parseNumber(trimmed);
               }
            }
      }
   }

   static Object parse(CharacterInput input, TomlParser parser, CommentedConfig parentConfig) {
      return parse(input, Toml.readNonSpaceChar(input, false), parser, parentConfig);
   }

   private static boolean shouldBeTemporal(CharsWrapper valueChars) {
      return valueChars.length() >= 8 && (valueChars.get(2) == ':' || valueChars.get(4) == '-' && valueChars.get(7) == '-');
   }

   private static Number parseNumber(CharsWrapper valueChars) {
      char first = valueChars.get(0);
      CharsWrapper remaining;
      if (first == '-') {
         remaining = valueChars.subView(1);
         if (remaining.contentEquals(FP_INFINITY)) {
            return Double.NEGATIVE_INFINITY;
         }
      } else if (first == '+') {
         remaining = valueChars.subView(1);
      } else {
         remaining = valueChars;
      }

      if (remaining.contentEquals(FP_INFINITY)) {
         return Double.POSITIVE_INFINITY;
      } else if (remaining.contentEquals(FP_NAN)) {
         return Double.NaN;
      } else {
         CharsWrapper numberChars = valueChars;
         int base = 10;
         if (valueChars.length() > 2) {
            String longValue = valueChars.subView(0, 2).toString();
            switch (longValue) {
               case "0x":
                  base = 16;
                  break;
               case "0b":
                  base = 2;
                  break;
               case "0o":
                  base = 8;
            }

            if (base != 10) {
               numberChars = valueChars.subView(2);
            }
         }

         numberChars = simplifyNumber(numberChars, base);
         if (base == 10 && numberChars.indexOfFirst(ONLY_IN_FP_NUMBER) != -1) {
            if (numberChars.length() > 1) {
               if (numberChars.get(0) == '0' && isDigitChar(numberChars.get(1), 10)) {
                  throw new ParsingException("Invalid leading zero in floating-point number " + numberChars);
               }

               if (numberChars.length() > 2 && (numberChars.startsWith("-0") || numberChars.startsWith("+0")) && isDigitChar(numberChars.get(2), 10)) {
                  throw new ParsingException("Invalid leading zero (after sign) in floating-point number " + numberChars);
               }
            }

            try {
               return Utils.parseDouble(numberChars);
            } catch (NumberFormatException var8) {
               throw new ParsingException("Invalid floating-point value: " + valueChars);
            }
         } else {
            if (base == 10) {
               if (numberChars.length() > 1 && numberChars.get(0) == '0') {
                  throw new ParsingException("Invalid leading zero in base 10 integer " + numberChars);
               }

               if (numberChars.length() > 2 && (numberChars.startsWith("-0") || numberChars.startsWith("+0")) && isDigitChar(numberChars.get(2), 10)) {
                  throw new ParsingException("Invalid leading zero (after sign) in base 10 integer " + numberChars);
               }
            }

            long longValue;
            try {
               longValue = Utils.parseLong(numberChars, base);
            } catch (NumberFormatException var9) {
               throw new ParsingException("Invalid integer value: " + valueChars);
            }

            int intValue = (int)longValue;
            return (Number)(intValue == longValue ? intValue : longValue);
         }
      }
   }

   private static boolean isDigitChar(char c, int base) {
      switch (base) {
         case 2:
            return c == '0' || c == '1';
         case 8:
            return c >= '0' && c <= '7';
         case 10:
            return c >= '0' && c <= '9';
         case 16:
            return c >= '0' && c <= '9' || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F';
         default:
            throw new IllegalArgumentException("Unsupported base " + base);
      }
   }

   private static CharsWrapper simplifyNumber(CharsWrapper numberChars, int base) {
      CharsWrapper.Builder builder = new CharsWrapper.Builder(16);
      int previousChar = -1;

      for (char c : numberChars) {
         switch (c) {
            case '.':
               if (previousChar == -1) {
                  throw new ParsingException("Invalid leading decimal point in number " + numberChars);
               }

               if (!isDigitChar((char)previousChar, base)) {
                  throw new ParsingException("Invalid decimal point in number (each decimal point must be surrounded by digits): " + numberChars);
               }

               builder.append(c);
               break;
            case '_':
               if (previousChar == -1) {
                  throw new ParsingException("Invalid leading underscore in number " + numberChars);
               }

               if (!isDigitChar((char)previousChar, base)) {
                  throw new ParsingException("Invalid underscore in number (each underscore must be surrounded by digits): " + numberChars);
               }
               break;
            case 'e':
               if (base != 16 && !isDigitChar((char)previousChar, base)) {
                  throw new ParsingException("Invalid sequence " + previousChar + "e in number" + numberChars);
               }
            default:
               builder.append(c);
         }

         previousChar = c;
      }

      if (previousChar == 95) {
         throw new ParsingException("Invalid trailing underscore in number: " + numberChars);
      } else if (previousChar == 46) {
         throw new ParsingException("Invalid trailing decimal point in number: " + numberChars);
      } else if (previousChar != 101 && previousChar != 69 && previousChar != 45 && previousChar != 43) {
         return builder.build();
      } else {
         throw new ParsingException("Invalid trailing '" + previousChar + "' in number: " + numberChars);
      }
   }

   private static Boolean parseFalse(CharacterInput input) {
      CharsWrapper remaining = input.readUntil(END_OF_VALUE);
      if (!remaining.contentEquals(FALSE_END)) {
         throw new ParsingException("Invalid value f" + remaining + " - Expected the boolean value false.");
      } else {
         return false;
      }
   }

   private static Boolean parseTrue(CharacterInput input) {
      CharsWrapper remaining = input.readUntil(END_OF_VALUE);
      if (!remaining.contentEquals(TRUE_END)) {
         throw new ParsingException("Invalid value t" + remaining + " - Expected the boolean value true.");
      } else {
         return true;
      }
   }

   private ValueParser() {
   }
}
