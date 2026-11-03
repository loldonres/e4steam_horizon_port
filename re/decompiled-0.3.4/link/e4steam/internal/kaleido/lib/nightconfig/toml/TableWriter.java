package link.e4steam.internal.kaleido.lib.nightconfig.toml;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import link.e4steam.internal.kaleido.lib.nightconfig.core.UnmodifiableCommentedConfig;
import link.e4steam.internal.kaleido.lib.nightconfig.core.UnmodifiableConfig;
import link.e4steam.internal.kaleido.lib.nightconfig.core.io.CharacterOutput;
import link.e4steam.internal.kaleido.lib.nightconfig.core.io.WritingException;

final class TableWriter {
   private static final char[] KEY_VALUE_SEPARATOR = new char[]{' ', '=', ' '};
   private static final char[] INLINE_ENTRY_SEPARATOR = ArrayWriter.ELEMENT_SEPARATOR;
   private static final char[] ARRAY_OF_TABLES_NAME_BEGIN = new char[]{'[', '['};
   private static final char[] ARRAY_OF_TABLES_NAME_END = new char[]{']', ']'};
   private static final char[] TABLE_NAME_BEGIN = new char[]{'['};
   private static final char[] TABLE_NAME_END = new char[]{']'};

   static void writeInline(UnmodifiableConfig config, CharacterOutput output, TomlWriter writer) {
      output.write('{');
      Iterator<? extends UnmodifiableConfig.Entry> iterator = config.entrySet().iterator();

      while (iterator.hasNext()) {
         UnmodifiableConfig.Entry entry = iterator.next();
         String key = entry.getKey();
         Object value = entry.getValue();
         writer.writeKey(key, output);
         output.write(KEY_VALUE_SEPARATOR);
         ValueWriter.write(value, output, writer);
         if (iterator.hasNext()) {
            output.write(INLINE_ENTRY_SEPARATOR);
         }
      }

      output.write('}');
   }

   static void writeTopLevel(UnmodifiableConfig config, List<String> configPath, CharacterOutput output, TomlWriter writer) {
      UnmodifiableCommentedConfig commentedConfig = UnmodifiableCommentedConfig.fake(config);
      writeWithHeader(commentedConfig, null, false, false, configPath, output, writer);
   }

   static TableWriter.OrganizedTable prepareTable(UnmodifiableCommentedConfig config, String comment, TomlWriter writer) {
      List<UnmodifiableCommentedConfig.Entry> simpleEntries = new ArrayList<>();
      List<UnmodifiableCommentedConfig.Entry> tablesEntries = new ArrayList<>();
      List<UnmodifiableCommentedConfig.Entry> tableArraysEntries = new ArrayList<>();

      for (UnmodifiableCommentedConfig.Entry entry : config.entrySet()) {
         Object value = entry.getValue();
         if (value instanceof UnmodifiableCommentedConfig) {
            UnmodifiableConfig sub = (UnmodifiableConfig)value;
            if (writer.writesInline(sub)) {
               simpleEntries.add(entry);
            } else {
               tablesEntries.add(entry);
            }
         } else if (value instanceof List) {
            List<?> list = (List<?>)value;
            if (!list.isEmpty() && list.stream().allMatch(UnmodifiableConfig.class::isInstance)) {
               tableArraysEntries.add(entry);
            } else {
               simpleEntries.add(entry);
            }
         } else {
            simpleEntries.add(entry);
         }
      }

      return new TableWriter.OrganizedTable(comment, simpleEntries, tablesEntries, tableArraysEntries);
   }

   private static void writeWithHeader(
      UnmodifiableCommentedConfig config,
      String tableComment,
      boolean inArrayOfTables,
      boolean tableHeader,
      List<String> configPath,
      CharacterOutput output,
      TomlWriter writer
   ) {
      TableWriter.OrganizedTable table = prepareTable(config, tableComment, writer);
      boolean hasSubTables = !table.subTables.isEmpty();
      if (table.canBeSkipped() && writer.isHidingRedundantLevels()) {
         writer.increaseIndentLevel();
         if (inArrayOfTables) {
            writeTableArrayName(configPath, output, writer);
            writer.writeNewline(output);
            writer.increaseIndentLevel();
         }

         writeSubTables(table, configPath, output, writer);
         if (inArrayOfTables) {
            writer.decreaseIndentLevel();
         }

         if (!table.comment.isEmpty()) {
            writer.writeIndentedComment(tableComment, output);
         }

         writeArraysOfTables(table, configPath, output, writer);
         writer.decreaseIndentLevel();
      } else {
         if (!table.comment.isEmpty()) {
            writer.writeIndentedComment(tableComment, output);
         }

         if (inArrayOfTables) {
            writeTableArrayName(configPath, output, writer);
            writer.writeNewline(output);
         } else if (tableHeader) {
            writeTableName(configPath, output, writer);
            writer.writeNewline(output);
         }

         writer.increaseIndentLevel();

         for (UnmodifiableCommentedConfig.Entry entry : table.simples) {
            writer.writeIndentedComment(entry.getComment(), output);
            writer.writeIndentedKey(entry.getKey(), output);
            output.write(KEY_VALUE_SEPARATOR);
            ValueWriter.write(entry.getValue(), output, writer);
            writer.writeNewline(output);
         }

         if (hasSubTables) {
            writer.writeNewline(output);
         }

         writeSubTables(table, configPath, output, writer);
         writeArraysOfTables(table, configPath, output, writer);
         writer.decreaseIndentLevel();
      }
   }

   private static void writeSubTables(TableWriter.OrganizedTable table, List<String> configPath, CharacterOutput output, TomlWriter writer) {
      boolean hasArraysOfTables = !table.arraysOfTables.isEmpty();
      Iterator<UnmodifiableCommentedConfig.Entry> it = table.subTables.iterator();

      while (it.hasNext()) {
         UnmodifiableCommentedConfig.Entry entry = it.next();
         UnmodifiableCommentedConfig sub = UnmodifiableCommentedConfig.fake(entry.getRawValue());
         configPath.add(entry.getKey());
         writeWithHeader(sub, entry.getComment(), false, true, configPath, output, writer);
         configPath.remove(configPath.size() - 1);
         if (hasArraysOfTables || it.hasNext()) {
            writer.writeNewline(output);
         }
      }
   }

   private static void writeArraysOfTables(TableWriter.OrganizedTable table, List<String> configPath, CharacterOutput output, TomlWriter writer) {
      Iterator<UnmodifiableCommentedConfig.Entry> it = table.arraysOfTables.iterator();

      while (it.hasNext()) {
         UnmodifiableCommentedConfig.Entry entry = it.next();
         configPath.add(entry.getKey());

         for (UnmodifiableConfig sub : (List)entry.getRawValue()) {
            writeWithHeader(UnmodifiableCommentedConfig.fake(sub), entry.getComment(), true, true, configPath, output, writer);
         }

         configPath.remove(configPath.size() - 1);
         if (it.hasNext()) {
            writer.writeNewline(output);
         }
      }
   }

   private static void writeTableArrayName(List<String> name, CharacterOutput output, TomlWriter writer) {
      writeTableName(name, output, writer, ARRAY_OF_TABLES_NAME_BEGIN, ARRAY_OF_TABLES_NAME_END);
   }

   private static void writeTableName(List<String> name, CharacterOutput output, TomlWriter writer) {
      writeTableName(name, output, writer, TABLE_NAME_BEGIN, TABLE_NAME_END);
   }

   private static void writeTableName(List<String> name, CharacterOutput output, TomlWriter writer, char[] begin, char[] end) {
      if (name.isEmpty()) {
         throw new WritingException("Invalid empty table name.");
      } else {
         writer.writeIndent(output);
         output.write(begin);
         Iterator<String> it = name.iterator();
         writer.writeKey(it.next(), output);

         while (it.hasNext()) {
            output.write('.');
            writer.writeKey(it.next(), output);
         }

         output.write(end);
      }
   }

   private TableWriter() {
   }

   static class OrganizedTable {
      List<UnmodifiableCommentedConfig.Entry> simples;
      List<UnmodifiableCommentedConfig.Entry> subTables;
      List<UnmodifiableCommentedConfig.Entry> arraysOfTables;
      String comment;

      OrganizedTable(
         String comment,
         List<UnmodifiableCommentedConfig.Entry> simpleEntries,
         List<UnmodifiableCommentedConfig.Entry> tablesEntries,
         List<UnmodifiableCommentedConfig.Entry> tableArraysEntries
      ) {
         this.comment = comment == null ? "" : comment;
         this.simples = simpleEntries;
         this.subTables = tablesEntries;
         this.arraysOfTables = tableArraysEntries;
      }

      boolean canBeSkipped() {
         return (this.comment.isEmpty() || !this.arraysOfTables.isEmpty())
            && this.simples.isEmpty()
            && (!this.subTables.isEmpty() || !this.arraysOfTables.isEmpty());
      }

      @Override
      public String toString() {
         return "OrganizedTable [simples="
            + this.simples
            + ", subTables="
            + this.subTables
            + ", arraysOfTables="
            + this.arraysOfTables
            + ", comment="
            + this.comment
            + ", canBeSkipped()="
            + this.canBeSkipped()
            + "]";
      }
   }
}
