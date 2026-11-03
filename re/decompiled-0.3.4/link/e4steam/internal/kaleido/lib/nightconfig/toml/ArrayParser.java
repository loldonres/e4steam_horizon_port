package link.e4steam.internal.kaleido.lib.nightconfig.toml;

import java.util.List;
import link.e4steam.internal.kaleido.lib.nightconfig.core.CommentedConfig;
import link.e4steam.internal.kaleido.lib.nightconfig.core.io.CharacterInput;
import link.e4steam.internal.kaleido.lib.nightconfig.core.io.ParsingException;

final class ArrayParser {
   static List<?> parse(CharacterInput input, TomlParser parser, CommentedConfig parentConfig) {
      List<Object> list = parser.createList();
      boolean first = true;

      while (true) {
         char firstChar = Toml.readUsefulChar(input);
         if (firstChar == ']') {
            return list;
         }

         if (firstChar == ',') {
            if (first) {
               throw new ParsingException("Invalid array: [,]");
            }

            throw new ParsingException("Invalid double comma in array.");
         }

         Object value = ValueParser.parse(input, firstChar, parser, parentConfig);
         list.add(value);
         char after = Toml.readUsefulChar(input);
         if (after == ']') {
            return list;
         }

         if (after != ',') {
            throw new ParsingException("Invalid separator '" + after + "' in array.");
         }

         first = false;
      }
   }

   private ArrayParser() {
   }
}
