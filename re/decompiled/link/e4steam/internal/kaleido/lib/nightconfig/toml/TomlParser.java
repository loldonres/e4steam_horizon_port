package link.e4steam.internal.kaleido.lib.nightconfig.toml;

import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import link.e4steam.internal.kaleido.lib.nightconfig.core.CommentedConfig;
import link.e4steam.internal.kaleido.lib.nightconfig.core.Config;
import link.e4steam.internal.kaleido.lib.nightconfig.core.ConfigFormat;
import link.e4steam.internal.kaleido.lib.nightconfig.core.io.CharacterInput;
import link.e4steam.internal.kaleido.lib.nightconfig.core.io.CharsWrapper;
import link.e4steam.internal.kaleido.lib.nightconfig.core.io.ConfigParser;
import link.e4steam.internal.kaleido.lib.nightconfig.core.io.ParsingException;
import link.e4steam.internal.kaleido.lib.nightconfig.core.io.ParsingMode;
import link.e4steam.internal.kaleido.lib.nightconfig.core.io.ReaderInput;

public final class TomlParser implements ConfigParser<CommentedConfig> {
   private int initialStringBuilderCapacity = 16;
   private int initialListCapacity = 10;
   private boolean lenientBareKeys = false;
   private boolean lenientSeparators = false;
   private boolean configWasEmpty = false;
   private ParsingMode parsingMode;
   private final Set<Config> inlineTables = Collections.newSetFromMap(new IdentityHashMap<>());
   private String currentComment;

   void registerInlineTable(Config table) {
      this.inlineTables.add(table);
   }

   boolean isInlineTable(Config table) {
      return this.inlineTables.contains(table);
   }

   private void clearParsingState() {
      this.inlineTables.clear();
   }

   public CommentedConfig parse(Reader reader) {
      this.configWasEmpty = true;
      return this.parse(new ReaderInput(reader), TomlFormat.instance().createConfig(), ParsingMode.MERGE);
   }

   @Override
   public void parse(Reader reader, Config destination, ParsingMode parsingMode) {
      if (parsingMode == ParsingMode.REPLACE) {
         this.configWasEmpty = true;
      }

      this.parse(new ReaderInput(reader), destination, parsingMode);
   }

   private <T extends Config> T parse(CharacterInput input, T destination, ParsingMode parsingMode) {
      this.parsingMode = parsingMode;
      parsingMode.prepareParsing(destination);
      CommentedConfig commentedConfig = CommentedConfig.fake(destination);
      CommentedConfig rootTable = TableParser.parseNormal(input, this, commentedConfig);

      int next;
      while ((next = input.peek()) != -1) {
         boolean isArray = next == 91;
         if (isArray) {
            input.skipPeeks();
         }

         List<String> path = TableParser.parseTableName(input, this, isArray);
         int lastIndex = path.size() - 1;
         List<String> parentPath = path.subList(0, lastIndex);
         List<String> lastPath = Collections.singletonList(path.get(lastIndex));
         Config parentConfig = this.getSubTable(rootTable, parentPath);
         if (this.hasPendingComment()) {
            String comment = this.consumeComment();
            if (parentConfig instanceof CommentedConfig) {
               ((CommentedConfig)parentConfig).setComment(lastPath, comment);
            }
         }

         if (isArray) {
            if (parentConfig == null) {
               throw new ParsingException("Cannot create entry " + path + " because of an invalid parent that isn't a table.");
            }

            CommentedConfig table = TableParser.parseNormal(commentedConfig, input, this);
            Object shouldBeArrayOfTables = parentConfig.get(lastPath);
            if (shouldBeArrayOfTables instanceof List) {
               List<CommentedConfig> arrayOfTables = (List<CommentedConfig>)shouldBeArrayOfTables;
               arrayOfTables.add(table);
            } else {
               if (shouldBeArrayOfTables != null) {
                  throw new ParsingException("Cannot create entry " + path + " because of an invalid parent that is not an array of tables");
               }

               List<CommentedConfig> arrayOfTables = this.createList();
               arrayOfTables.add(table);
               parsingMode.put(parentConfig, lastPath, arrayOfTables);
            }
         } else {
            if (parentConfig == null) {
               throw new ParsingException("Cannot create entry " + path + " because of an invalid parent that isn't a table.");
            }

            Object alreadyDeclared = parentConfig.get(lastPath);
            if (alreadyDeclared == null) {
               CommentedConfig table = TableParser.parseNormal(commentedConfig, input, this);
               parsingMode.put(parentConfig, lastPath, table);
            } else if (alreadyDeclared instanceof Config) {
               Config table = (Config)alreadyDeclared;
               this.checkContainsOnlySubtables(table, path);
               CommentedConfig commentedTable = CommentedConfig.fake(table);
               TableParser.parseNormal(input, this, commentedTable);
            } else if (this.configWasEmpty) {
               throw new ParsingException("Entry " + path + " has been defined twice.");
            }
         }
      }

      this.clearParsingState();
      return destination;
   }

   private Config getSubTable(Config parentTable, List<String> path) {
      if (path.isEmpty()) {
         return parentTable;
      } else {
         Config currentConfig = parentTable;

         for (String key : path) {
            List<String> singleKey = Collections.singletonList(key);
            Object value = currentConfig.get(singleKey);
            if (value == null) {
               Config sub = parentTable.createSubConfig();
               currentConfig.set(singleKey, sub);
               currentConfig = sub;
            } else if (value instanceof Config) {
               currentConfig = (Config)value;
            } else {
               if (!(value instanceof List)) {
                  return null;
               }

               List<?> list = (List<?>)value;
               if (list.isEmpty() || !list.stream().allMatch(Config.class::isInstance)) {
                  return null;
               }

               int lastIndex = list.size() - 1;
               currentConfig = (Config)list.get(lastIndex);
            }

            if (this.isInlineTable(currentConfig)) {
               throw new ParsingException("Cannot modify an inline table after its creation. Key path: " + path);
            }
         }

         return currentConfig;
      }
   }

   private void checkContainsOnlySubtables(Config table, List<String> path) {
      for (Config.Entry entry : table.entrySet()) {
         Object value = entry.getValue();
         if (!(value instanceof Config) && (!(value instanceof List) || ((List)value).isEmpty() || !(((List)value).get(0) instanceof Config))) {
            throw new ParsingException("Table with path " + path + " has been declared twice.");
         }
      }
   }

   public boolean isLenientWithSeparators() {
      return this.lenientSeparators;
   }

   public TomlParser setLenientWithSeparators(boolean lenientSeparators) {
      this.lenientSeparators = lenientSeparators;
      return this;
   }

   public boolean isLenientWithBareKeys() {
      return this.lenientBareKeys;
   }

   public TomlParser setLenientWithBareKeys(boolean lenientBareKeys) {
      this.lenientBareKeys = lenientBareKeys;
      return this;
   }

   public TomlParser setInitialStringBuilderCapacity(int initialStringBuilderCapacity) {
      this.initialStringBuilderCapacity = initialStringBuilderCapacity;
      return this;
   }

   public TomlParser setInitialListCapacity(int initialListCapacity) {
      this.initialListCapacity = initialListCapacity;
      return this;
   }

   @Override
   public ConfigFormat<CommentedConfig> getFormat() {
      return TomlFormat.instance();
   }

   boolean configWasEmpty() {
      return this.configWasEmpty;
   }

   ParsingMode getParsingMode() {
      return this.parsingMode;
   }

   <T> List<T> createList() {
      return new ArrayList<>(this.initialListCapacity);
   }

   CharsWrapper.Builder createBuilder() {
      return new CharsWrapper.Builder(this.initialStringBuilderCapacity);
   }

   boolean hasPendingComment() {
      return this.currentComment != null;
   }

   String consumeComment() {
      String comment = this.currentComment;
      this.currentComment = null;
      return comment;
   }

   void setComment(CharsWrapper comment) {
      if (comment != null) {
         String str = comment.toString();
         str.codePoints().forEach(c -> {
            if (c != 9 && c != 10) {
               if (c <= 31 || c == 127) {
                  throw new ParsingException("Invalid control character in comment: " + str);
               } else if (c > 55295 && c < 57344) {
                  throw new ParsingException("Invalid unicode codepoint in comment: " + str);
               }
            }
         });
         if (this.currentComment == null) {
            this.currentComment = str;
         } else {
            this.currentComment = this.currentComment + '\n' + str;
         }
      }
   }

   void setComment(List<CharsWrapper> commentsList) {
      CharsWrapper.Builder builder = new CharsWrapper.Builder(32);
      if (!commentsList.isEmpty()) {
         Iterator<CharsWrapper> it = commentsList.iterator();
         builder.append(it.next());

         while (it.hasNext()) {
            builder.append('\n');
            builder.append(it.next());
         }

         this.setComment(builder.build());
      }
   }
}
