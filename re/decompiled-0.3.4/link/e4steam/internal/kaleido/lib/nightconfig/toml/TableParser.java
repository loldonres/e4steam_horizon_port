package link.e4steam.internal.kaleido.lib.nightconfig.toml;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import link.e4steam.internal.kaleido.lib.nightconfig.core.CommentedConfig;
import link.e4steam.internal.kaleido.lib.nightconfig.core.Config;
import link.e4steam.internal.kaleido.lib.nightconfig.core.io.CharacterInput;
import link.e4steam.internal.kaleido.lib.nightconfig.core.io.CharsWrapper;
import link.e4steam.internal.kaleido.lib.nightconfig.core.io.ParsingException;

final class TableParser {
   private static final char[] KEY_END = new char[]{'\t', ' ', '=', '.', '\n', '\r', ']', ':'};

   static <T extends CommentedConfig> T parseInline(CharacterInput input, TomlParser parser, T config) {
      parser.registerInlineTable(config);
      boolean configWasInitiallyEmpty = config.isEmpty();
      boolean expectNextElement = false;

      while (true) {
         char keyFirst = Toml.readNonSpaceChar(input, false);
         if (keyFirst == '}') {
            if (expectNextElement) {
               throw new ParsingException("Invalid trailing comma in inline table");
            }

            return config;
         }

         List<String> key = parseDottedKey(input, keyFirst, parser);
         checkDuplicateKeyBecauseOfParents(key, config, configWasInitiallyEmpty);
         Object value = ValueParser.parse(input, parser, config);
         Object previous = parser.getParsingMode().put(config, key, value);
         checkDuplicateKey(key, previous, true);
         char after = Toml.readNonSpaceChar(input, false);
         if (after == '}') {
            return config;
         }

         if (after != ',') {
            throw new ParsingException("Invalid entry separator '" + after + "' in inline table.");
         }

         expectNextElement = true;
      }
   }

   static <T extends CommentedConfig> T parseNormal(CharacterInput input, TomlParser parser, T config) {
      boolean configWasInitiallyEmpty = config.isEmpty();

      while (true) {
         List<CharsWrapper> commentsList = new ArrayList<>(2);
         int keyFirst = Toml.readUseful(input, commentsList);
         if (keyFirst == -1 || keyFirst == 91) {
            parser.setComment(commentsList);
            return config;
         }

         List<String> key = parseDottedKey(input, (char)keyFirst, parser);
         checkDuplicateKeyBecauseOfParents(key, config, configWasInitiallyEmpty);
         Object value = ValueParser.parse(input, parser, config);
         Object previous = parser.getParsingMode().put(config, key, value);
         checkDuplicateKey(key, previous, parser.configWasEmpty());
         int after = Toml.readNonSpace(input, false);
         if (after == -1) {
            return config;
         }

         if (after == 35) {
            CharsWrapper comment = Toml.readLine(input);
            commentsList.add(comment);
         } else if (after != 10 && after != 13) {
            throw new ParsingException("Invalid character '" + (char)after + "' after table entry \"" + key + "\" = " + value);
         }

         parser.setComment(commentsList);
         config.setComment(key, parser.consumeComment());
      }
   }

   private static void checkDuplicateKeyBecauseOfParents(List<String> key, Config config, boolean emptyConfig) {
      if (emptyConfig && key.size() > 1) {
         Config current = config;
         int i = 0;

         for (String k : key.subList(0, key.size() - 1)) {
            List<String> singleKey = Collections.singletonList(k);
            Object sub = current.get(singleKey);
            if (sub == null) {
               return;
            }

            if (!(sub instanceof Config)) {
               throw new ParsingException(String.format("Wrong duplicate key %s: intermediary level %s already exists", key, key.subList(0, i + 1)));
            }

            current = (Config)sub;
            i++;
         }
      }
   }

   private static void checkDuplicateKey(Object key, Object previousValue, boolean emptyConfig) {
      if (previousValue != null && emptyConfig) {
         throw new ParsingException("Invalid TOML data: entry \"" + key + "\" defined twice in its table.");
      }
   }

   static CommentedConfig parseNormal(CommentedConfig parentConfig, CharacterInput input, TomlParser parser) {
      return parseNormal(input, parser, parentConfig.createSubConfig());
   }

   static List<String> parseTableName(CharacterInput input, TomlParser parser, boolean array) {
      List<String> list = parser.createList();

      char separator;
      do {
         char firstChar = Toml.readNonSpaceChar(input, false);
         if (firstChar == ']') {
            throw new ParsingException("Tables names must not be empty.");
         }

         String key = parseNonDottedKey(input, firstChar, parser);
         list.add(key);
         separator = Toml.readNonSpaceChar(input, false);
         if (separator == ']') {
            if (array) {
               char after = input.readChar();
               if (after != ']') {
                  throw new ParsingException("Invalid declaration of an element of an array of tables: it ends by ]" + after + " but should end by ]]");
               }
            }

            char after = Toml.readNonSpaceChar(input, false);
            if (after == '#') {
               CharsWrapper comment = Toml.readLine(input);
               parser.setComment(comment);
            } else if (after != '\n' && after != '\r') {
               throw new ParsingException("Invalid character '" + after + "' after a table declaration.");
            }

            return list;
         }
      } while (separator == '.');

      throw new ParsingException("Invalid separator '" + separator + "' in table name.");
   }

   static List<String> parseDottedKey(CharacterInput input, char firstChar, TomlParser parser) {
      List<String> list = parser.createList();
      char first = firstChar;

      while (true) {
         String part = parseNonDottedKey(input, first, parser);
         list.add(part);
         char sep = Toml.readNonSpaceChar(input, false);
         if (Toml.isKeyValueSeparator(sep, parser.isLenientWithSeparators())) {
            return list;
         }

         if (sep != '.') {
            throw new ParsingException("Invalid character '" + sep + "' after key " + list);
         }

         first = Toml.readNonSpaceChar(input, false);
      }
   }

   static String parseNonDottedKey(CharacterInput input, char firstChar, TomlParser parser) {
      if (firstChar == '"') {
         return StringParser.parseBasic(input, parser);
      } else if (firstChar == '\'') {
         return StringParser.parseLiteral(input, parser);
      } else {
         CharsWrapper restOfKey = input.readCharsUntil(KEY_END);
         String bareKey = new CharsWrapper.Builder(restOfKey.length() + 1).append(firstChar).append(restOfKey).toString();
         if (bareKey.isEmpty()) {
            throw new ParsingException("Empty bare keys aren't allowed.");
         } else if (!Toml.isValidBareKey(bareKey, parser.isLenientWithBareKeys())) {
            throw new ParsingException("Invalid bare key: '" + bareKey + "'");
         } else {
            return bareKey;
         }
      }
   }

   private TableParser() {
   }
}
