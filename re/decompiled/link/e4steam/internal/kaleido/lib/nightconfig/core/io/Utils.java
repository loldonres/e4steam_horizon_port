package link.e4steam.internal.kaleido.lib.nightconfig.core.io;

public final class Utils {
   private Utils() {
   }

   public static boolean arrayContains(char[] array, char element) {
      for (char c : array) {
         if (c == element) {
            return true;
         }
      }

      return false;
   }

   public static int arrayIndexOf(char[] array, char element) {
      for (int i = 0; i < array.length; i++) {
         if (array[i] == element) {
            return i;
         }
      }

      return -1;
   }

   public static long parseLong(CharsWrapper chars, int base) {
      int offset = chars.offset;
      boolean negative = false;
      char firstChar = chars.charAt(0);
      if (firstChar == '-') {
         negative = true;
         offset++;
      } else if (firstChar == '+') {
         offset++;
      }

      long value = 0L;
      long coefficient = 1L;
      char[] array = chars.chars;

      for (int i = chars.limit - 1; i >= offset; i--) {
         int digitValue = Character.digit(array[i], base);
         if (digitValue == -1) {
            throw new ParsingException("Invalid value: " + chars);
         }

         value += digitValue * coefficient;
         coefficient *= base;
      }

      return negative ? -value : value;
   }

   public static int parseInt(CharsWrapper chars, int base) {
      return (int)parseLong(chars, base);
   }

   public static double parseDouble(CharsWrapper chars) {
      return Double.parseDouble(chars.toString());
   }
}
