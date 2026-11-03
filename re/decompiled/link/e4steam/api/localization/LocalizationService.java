package link.e4steam.api.localization;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import link.e4steam.api.ApiResult;
import link.e4steam.api.ApiValidation;

public interface LocalizationService {
   LocalizationService.LocaleSnapshot locale();

   ApiResult<String> resolve(LocalizationService.LocalizedMessage var1);

   public static final class LocaleSnapshot {
      private final String current;
      private final String fallback;

      public LocaleSnapshot(String current, String fallback) {
         this.current = normalize(current);
         this.fallback = normalize(fallback);
      }

      public String current() {
         return this.current;
      }

      public String fallback() {
         return this.fallback;
      }

      private static String normalize(String value) {
         String checked = ApiValidation.text(value, "locale", 32).replace('_', '-');
         String tag = Locale.forLanguageTag(checked).toLanguageTag();
         if ("und".equals(tag)) {
            throw new IllegalArgumentException("invalid locale");
         } else {
            return tag;
         }
      }
   }

   public static final class LocalizedMessage {
      private final LocalizationService.TranslationKey key;
      private final String fallback;
      private final List<LocalizationService.MessageArgument> arguments;

      public LocalizedMessage(LocalizationService.TranslationKey key, String fallback, List<LocalizationService.MessageArgument> arguments) {
         this.key = Objects.requireNonNull(key, "key");
         this.fallback = ApiValidation.text(fallback, "fallback", 4096);
         this.arguments = ApiValidation.immutableList(arguments, 32, "arguments");
      }

      public LocalizationService.TranslationKey key() {
         return this.key;
      }

      public String fallback() {
         return this.fallback;
      }

      public List<LocalizationService.MessageArgument> arguments() {
         return this.arguments;
      }

      @Override
      public String toString() {
         return "LocalizedMessage{key=" + this.key + ", arguments=" + this.arguments.size() + '}';
      }
   }

   public static final class MessageArgument {
      private final String name;
      private final LocalizationService.MessageArgument.Type type;
      private final String value;

      public MessageArgument(String name, LocalizationService.MessageArgument.Type type, String value) {
         this.name = ApiValidation.text(name, "name", 32);
         ApiValidation.rejectSensitiveName(this.name, "name");
         this.type = Objects.requireNonNull(type, "type");
         this.value = ApiValidation.text(value, "value", 256);
         if (type == LocalizationService.MessageArgument.Type.INTEGER) {
            Long.parseLong(this.value);
         } else if (type == LocalizationService.MessageArgument.Type.DECIMAL) {
            double parsed = Double.parseDouble(this.value);
            if (Double.isNaN(parsed) || Double.isInfinite(parsed)) {
               throw new IllegalArgumentException("invalid decimal");
            }
         } else if (type == LocalizationService.MessageArgument.Type.BOOLEAN && !"true".equals(this.value) && !"false".equals(this.value)) {
            throw new IllegalArgumentException("invalid boolean");
         }
      }

      public String name() {
         return this.name;
      }

      public LocalizationService.MessageArgument.Type type() {
         return this.type;
      }

      public String value() {
         return this.value;
      }

      @Override
      public String toString() {
         return "MessageArgument{name='" + this.name + "', type=" + this.type + '}';
      }

      public static enum Type {
         TEXT,
         INTEGER,
         DECIMAL,
         BOOLEAN;
      }
   }

   public static final class TranslationKey {
      private static final Pattern FORMAT = Pattern.compile("^[a-z][a-z0-9_.-]{0,31}:[a-z][a-z0-9_.-]{0,95}$");
      private final String value;

      public TranslationKey(String value) {
         this.value = ApiValidation.identifier(value, "translationKey", FORMAT);
      }

      public String value() {
         return this.value;
      }

      @Override
      public boolean equals(Object other) {
         return this == other || other instanceof LocalizationService.TranslationKey && this.value.equals(((LocalizationService.TranslationKey)other).value);
      }

      @Override
      public int hashCode() {
         return this.value.hashCode();
      }

      @Override
      public String toString() {
         return this.value;
      }
   }
}
