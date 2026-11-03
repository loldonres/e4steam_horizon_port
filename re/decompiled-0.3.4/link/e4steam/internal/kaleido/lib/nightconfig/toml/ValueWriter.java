package link.e4steam.internal.kaleido.lib.nightconfig.toml;

import java.time.temporal.Temporal;
import java.util.List;
import link.e4steam.internal.kaleido.lib.nightconfig.core.Config;
import link.e4steam.internal.kaleido.lib.nightconfig.core.NullObject;
import link.e4steam.internal.kaleido.lib.nightconfig.core.io.CharacterOutput;
import link.e4steam.internal.kaleido.lib.nightconfig.core.io.WritingException;

final class ValueWriter {
   private static void writeString(String string, CharacterOutput output, TomlWriter writer) {
      if (writer.writesLiteral(string)) {
         if (writer.writesMultiline(string)) {
            StringWriter.writeLiteralMultiline(string, output);
         } else {
            StringWriter.writeLiteral(string, output);
         }
      } else if (writer.writesMultiline(string)) {
         StringWriter.writeBasicMultiline(string, output, writer);
      } else {
         StringWriter.writeBasic(string, output);
      }
   }

   static void write(Object value, CharacterOutput output, TomlWriter writer) {
      if (value instanceof Config) {
         TableWriter.writeInline((Config)value, output, writer);
      } else if (value instanceof List) {
         ArrayWriter.write((List<?>)value, output, writer);
      } else if (value instanceof CharSequence) {
         writeString(value.toString(), output, writer);
      } else if (value instanceof Enum) {
         writeString(((Enum)value).name(), output, writer);
      } else if (value instanceof Temporal) {
         TemporalWriter.write((Temporal)value, output);
      } else if (!(value instanceof Float) && !(value instanceof Double)) {
         if (!(value instanceof Number) && !(value instanceof Boolean)) {
            if (value != null && value != NullObject.NULL_OBJECT) {
               throw new WritingException("Unsupported value type: " + value.getClass());
            }

            throw new WritingException("TOML doesn't support null values");
         }

         output.write(value.toString());
      } else {
         double d = ((Number)value).doubleValue();
         if (Double.isNaN(d)) {
            output.write("nan");
         } else if (d == Double.POSITIVE_INFINITY) {
            output.write("+inf");
         } else if (d == Double.NEGATIVE_INFINITY) {
            output.write("-inf");
         } else {
            output.write(value.toString());
         }
      }
   }

   private ValueWriter() {
   }
}
