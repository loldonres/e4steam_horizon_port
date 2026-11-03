package link.e4steam.internal.kaleido.lib.nightconfig.core.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class StringUtils {
   private StringUtils() {
   }

   public static List<String> split(String str, char sep) {
      List<String> list = new ArrayList<>(8);
      split(str, sep, list);
      return list;
   }

   public static void split(String str, char sep, List<String> list) {
      int pos0 = 0;

      for (int i = 0; i < str.length(); i++) {
         if (str.charAt(i) == sep) {
            list.add(str.substring(pos0, i));
            pos0 = i + 1;
         }
      }

      list.add(str.substring(pos0));
   }

   public static List<String> splitLines(String str) {
      if (str != null && !str.isEmpty()) {
         List<String> list = new ArrayList<>(8);
         splitLines(str, list);
         return list;
      } else {
         return Collections.emptyList();
      }
   }

   public static void splitLines(String str, List<String> list) {
      int pos0 = 0;

      for (int i = 0; i < str.length(); i++) {
         char ch = str.charAt(i);
         if (ch == '\n') {
            list.add(str.substring(pos0, i));
            pos0 = i + 1;
         } else if (ch == '\r') {
            list.add(str.substring(pos0, i));
            int next = i + 1;
            if (next < str.length() && str.charAt(next) == '\n') {
               i++;
            }

            pos0 = i + 1;
         }
      }

      String lastPart = str.substring(pos0);
      list.add(lastPart);
   }
}
