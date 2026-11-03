package link.e4steam.internal.kaleido.lib.nightconfig.toml;

import java.util.List;
import link.e4steam.internal.kaleido.lib.nightconfig.core.io.CharacterInput;
import link.e4steam.internal.kaleido.lib.nightconfig.core.io.CharsWrapper;
import link.e4steam.internal.kaleido.lib.nightconfig.core.io.Utils;

final class Toml {
   private static final char[] WHITESPACE_OR_NEWLINE = new char[]{'\t', ' ', '\n', '\r'};
   private static final char[] WHITESPACE = new char[]{'\t', ' '};
   private static final char[] NEWLINE = new char[]{'\n'};
   private static final char[] FORBIDDEN_IN_ALL_BARE_KEYS = new char[]{'.', '[', ']', '#', '='};

   static char readUsefulChar(CharacterInput input) {
      char next;
      for (next = input.readCharAndSkip(WHITESPACE_OR_NEWLINE); next == '#'; next = input.readCharAndSkip(WHITESPACE_OR_NEWLINE)) {
         input.readCharsUntil(NEWLINE);
      }

      return next;
   }

   static int readUseful(CharacterInput input, List<CharsWrapper> commentsList) {
      int next;
      for (next = input.readAndSkip(WHITESPACE_OR_NEWLINE); next == 35; next = input.readAndSkip(WHITESPACE_OR_NEWLINE)) {
         CharsWrapper comment = readLine(input);
         commentsList.add(comment);
      }

      return next;
   }

   static char readNonSpaceChar(CharacterInput input, boolean skipNewlines) {
      return skipNewlines ? input.readCharAndSkip(WHITESPACE_OR_NEWLINE) : input.readCharAndSkip(WHITESPACE);
   }

   static int readNonSpace(CharacterInput input, boolean skipNewlines) {
      return skipNewlines ? input.readAndSkip(WHITESPACE_OR_NEWLINE) : input.readAndSkip(WHITESPACE);
   }

   static CharsWrapper readLine(CharacterInput input) {
      CharsWrapper chars = input.readUntil(NEWLINE);
      int lastIndex = chars.length() - 1;
      return lastIndex >= 0 && chars.get(lastIndex) == '\r' ? chars.subView(0, lastIndex) : chars;
   }

   static boolean isControlChar(char c) {
      return (c <= 31 || c == 127) && !Character.isSurrogate(c);
   }

   static boolean isControlChar(int c) {
      return c <= 31 || c == 127;
   }

   static boolean isValidCodePoint(int c) {
      return c <= 55295 || c >= 57344 && c <= 1114111;
   }

   static boolean isValidInBareKey(char c, boolean lenient) {
      return lenient
         ? c > ' ' && !Utils.arrayContains(FORBIDDEN_IN_ALL_BARE_KEYS, c) && !isControlChar(c)
         : c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-' || c == '_';
   }

   static boolean isValidBareKey(CharSequence csq, boolean lenient) {
      int len = csq.length();
      if (len == 0) {
         return false;
      } else {
         for (int i = 0; i < len; i++) {
            if (!isValidInBareKey(csq.charAt(i), lenient)) {
               return false;
            }
         }

         return true;
      }
   }

   static boolean isKeyValueSeparator(char c, boolean lenient) {
      return c == '=' || lenient && c == ':';
   }

   private Toml() {
   }
}
