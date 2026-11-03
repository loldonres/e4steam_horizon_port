package link.e4steam.internal.kaleido.lib.quiltconfig.api.metadata;

import java.util.ArrayList;

public enum NamingSchemes implements NamingScheme {
   PASSTHROUGH {
      @Override
      public String coerce(String input) {
         return input;
      }
   },
   UPPER_CAMEL_CASE {
      @Override
      public String coerce(String input) {
         StringBuilder builder = new StringBuilder();

         for (String word : NamingSchemes.extractWords(input)) {
            if (!word.isEmpty()) {
               builder.appendCodePoint(Character.toUpperCase(word.codePointAt(0)));
               builder.append(word.substring(1));
            }
         }

         return builder.toString();
      }
   },
   LOWER_CAMEL_CASE {
      @Override
      public String coerce(String input) {
         StringBuilder builder = new StringBuilder();
         boolean first = true;

         for (String word : NamingSchemes.extractWords(input)) {
            if (!word.isEmpty()) {
               if (first) {
                  builder.append(word);
                  first = false;
               } else {
                  builder.appendCodePoint(Character.toUpperCase(word.codePointAt(0)));
                  builder.append(word.substring(1));
               }
            }
         }

         return builder.toString();
      }
   },
   KEBAB_CASE {
      @Override
      public String coerce(String input) {
         StringBuilder builder = new StringBuilder();
         boolean first = true;

         for (String word : NamingSchemes.extractWords(input)) {
            if (!word.isEmpty()) {
               if (first) {
                  first = false;
               } else {
                  builder.append("-");
               }

               builder.append(word);
            }
         }

         return builder.toString();
      }
   },
   SNAKE_CASE {
      @Override
      public String coerce(String input) {
         StringBuilder builder = new StringBuilder();
         boolean first = true;

         for (String word : NamingSchemes.extractWords(input)) {
            if (!word.isEmpty()) {
               if (first) {
                  first = false;
               } else {
                  builder.append("_");
               }

               builder.append(word);
            }
         }

         return builder.toString();
      }
   },
   SPACE_SEPARATED_LOWER_CASE {
      @Override
      public String coerce(String input) {
         StringBuilder builder = new StringBuilder();
         boolean first = true;

         for (String word : NamingSchemes.extractWords(input)) {
            if (!word.isEmpty()) {
               if (first) {
                  first = false;
               } else {
                  builder.append(" ");
               }

               builder.append(word);
            }
         }

         return builder.toString();
      }
   },
   SPACE_SEPARATED_LOWER_CASE_INITIAL_UPPER_CASE {
      @Override
      public String coerce(String input) {
         StringBuilder builder = new StringBuilder();
         boolean first = true;

         for (String word : NamingSchemes.extractWords(input)) {
            if (!word.isEmpty()) {
               if (first) {
                  builder.appendCodePoint(Character.toUpperCase(word.codePointAt(0)));
                  builder.append(word.substring(1));
                  first = false;
               } else {
                  builder.append(" ");
                  builder.append(word);
               }
            }
         }

         return builder.toString();
      }
   },
   TITLE_CASE {
      @Override
      public String coerce(String input) {
         StringBuilder builder = new StringBuilder();
         boolean first = true;

         for (String word : NamingSchemes.extractWords(input)) {
            if (!word.isEmpty()) {
               if (first) {
                  first = false;
               } else {
                  builder.append(" ");
               }

               builder.appendCodePoint(Character.toUpperCase(word.codePointAt(0)));
               builder.append(word.substring(1));
            }
         }

         return builder.toString();
      }
   };

   private NamingSchemes() {
   }

   private static String[] extractWords(String input) {
      ArrayList<String> list = new ArrayList<>();
      StringBuilder[] builder = new StringBuilder[]{new StringBuilder()};
      int[] state = new int[]{0};
      input.codePoints().forEach(point -> {
         if (point != 45 && point != 95 && point != 32) {
            boolean isUpper = Character.isUpperCase(point);
            if (isUpper) {
               if (state[0] == 2) {
                  list.add(builder[0].toString());
                  builder[0] = new StringBuilder();
                  state[0] = 0;
               } else {
                  state[0] = 1;
               }
            } else {
               state[0] = 2;
            }

            builder[0].appendCodePoint(Character.toLowerCase(point));
         } else {
            list.add(builder[0].toString());
            builder[0] = new StringBuilder();
            state[0] = 0;
         }
      });
      list.add(builder[0].toString());
      return list.toArray(new String[0]);
   }
}
