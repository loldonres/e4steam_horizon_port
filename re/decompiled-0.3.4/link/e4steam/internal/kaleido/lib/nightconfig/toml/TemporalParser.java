package link.e4steam.internal.kaleido.lib.nightconfig.toml;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.Temporal;
import link.e4steam.internal.kaleido.lib.nightconfig.core.io.CharsWrapper;
import link.e4steam.internal.kaleido.lib.nightconfig.core.io.ParsingException;
import link.e4steam.internal.kaleido.lib.nightconfig.core.io.Utils;

final class TemporalParser {
   private static final char[] ALLOWED_DT_SEPARATORS = new char[]{'T', 't', ' '};
   private static final char[] OFFSET_INDICATORS = new char[]{'Z', 'z', '+', '-'};

   static Temporal parse(CharsWrapper chars) {
      chars = chars.trimmedView();

      try {
         if (chars.get(2) == ':') {
            return parseTime(chars);
         } else {
            LocalDate date = parseDate(chars);
            if (chars.length() == 10) {
               return date;
            } else {
               char dateTimeSeparator = chars.get(10);
               if (!Utils.arrayContains(ALLOWED_DT_SEPARATORS, dateTimeSeparator)) {
                  throw new ParsingException("Invalid separator between date and time: '" + dateTimeSeparator + "'.");
               } else {
                  CharsWrapper afterDate = chars.subView(11);
                  int offsetIndicatorIndex = afterDate.indexOfFirst(OFFSET_INDICATORS);
                  if (offsetIndicatorIndex == -1) {
                     LocalTime time = parseTime(afterDate);
                     return LocalDateTime.of(date, time);
                  } else {
                     if (afterDate.get(offsetIndicatorIndex) == 'z') {
                        afterDate.set(offsetIndicatorIndex, 'Z');
                     }

                     LocalTime time = parseTime(afterDate.subView(0, offsetIndicatorIndex));
                     ZoneOffset offset = ZoneOffset.of(afterDate.subView(offsetIndicatorIndex).trimmedView().toString());
                     return OffsetDateTime.of(date, time, offset);
                  }
               }
            }
         }
      } catch (DateTimeException | ArrayIndexOutOfBoundsException var7) {
         throw new ParsingException("Invalid temporal value " + chars, var7);
      }
   }

   private static LocalDate parseDate(CharsWrapper chars) {
      CharsWrapper yearChars = chars.subView(0, 4);
      CharsWrapper monthChars = chars.subView(5, 7);
      CharsWrapper dayChars = chars.subView(8, 10);
      int year = Utils.parseInt(yearChars, 10);
      int month = Utils.parseInt(monthChars, 10);
      int day = Utils.parseInt(dayChars, 10);
      return LocalDate.of(year, month, day);
   }

   private static LocalTime parseTime(CharsWrapper chars) {
      CharsWrapper hourChars = chars.subView(0, 2);
      CharsWrapper minuteChars = chars.subView(3, 5);
      CharsWrapper secondChars = chars.subView(6, 8);
      int hour = Utils.parseInt(hourChars, 10);
      int minutes = Utils.parseInt(minuteChars, 10);
      int seconds = Utils.parseInt(secondChars, 10);
      int nanos;
      if (chars.length() > 8) {
         CharsWrapper fractionChars = new CharsWrapper(chars.subView(9));
         if (fractionChars.length() > 9) {
            fractionChars = fractionChars.subView(0, 9);
         }

         int value = Utils.parseInt(fractionChars, 10);
         int coeff = (int)Math.pow(10.0, 9 - fractionChars.length());
         nanos = value * coeff;
      } else {
         nanos = 0;
      }

      return LocalTime.of(hour, minutes, seconds, nanos);
   }

   private TemporalParser() {
   }
}
