package link.e4steam.api.ui;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Map.Entry;
import java.util.concurrent.CompletionStage;
import java.util.regex.Pattern;
import link.e4steam.api.ApiResult;
import link.e4steam.api.ApiValidation;
import link.e4steam.api.Registration;

public interface UiService {
   UiService.Availability availability();

   ApiResult<Registration> registerAction(UiService.ActionDescriptor var1, UiService.ActionHandler var2);

   ApiResult<Registration> registerSettings(UiService.SettingsCategory var1);

   ApiResult<Registration> registerStatus(UiService.StatusBadge var1);

   CompletionStage<ApiResult<Boolean>> toast(UiService.Message var1);

   CompletionStage<ApiResult<Boolean>> confirm(UiService.Confirmation var1);

   CompletionStage<ApiResult<UiService.FormResult>> form(UiService.FormDescriptor var1);

   public static enum ActionContext {
      MAIN_MENU,
      MULTIPLAYER,
      CURRENT_WORLD,
      CURRENT_SESSION,
      SETTINGS;
   }

   public static final class ActionDescriptor {
      private final UiService.UiId id;
      private final UiService.ActionContext context;
      private final UiService.Message label;
      private final String safeUrl;

      public ActionDescriptor(UiService.UiId id, UiService.ActionContext context, UiService.Message label) {
         this(id, context, label, "");
      }

      public ActionDescriptor(UiService.UiId id, UiService.ActionContext context, UiService.Message label, String safeUrl) {
         this.id = Objects.requireNonNull(id, "id");
         this.context = Objects.requireNonNull(context, "context");
         this.label = Objects.requireNonNull(label, "label");
         this.safeUrl = validateUrl(safeUrl);
      }

      public UiService.UiId id() {
         return this.id;
      }

      public UiService.ActionContext context() {
         return this.context;
      }

      public UiService.Message label() {
         return this.label;
      }

      public String safeUrl() {
         return this.safeUrl;
      }

      private static String validateUrl(String value) {
         if (value != null && !value.trim().isEmpty()) {
            String checked = ApiValidation.text(value, "safeUrl", 1024);

            try {
               URI uri = new URI(checked);
               if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getUserInfo() == null && uri.getHost() != null) {
                  return checked;
               } else {
                  throw new IllegalArgumentException("only credential-free HTTPS URLs are supported");
               }
            } catch (URISyntaxException var3) {
               throw new IllegalArgumentException("invalid URL");
            }
         } else {
            return "";
         }
      }
   }

   public interface ActionHandler {
      CompletionStage<ApiResult<Boolean>> execute();
   }

   public static enum Availability {
      AVAILABLE,
      HEADLESS,
      UNSUPPORTED;
   }

   public static final class Confirmation {
      private final UiService.UiId id;
      private final UiService.Message title;
      private final UiService.Message body;

      public Confirmation(UiService.UiId id, UiService.Message title, UiService.Message body) {
         this.id = Objects.requireNonNull(id, "id");
         this.title = Objects.requireNonNull(title, "title");
         this.body = Objects.requireNonNull(body, "body");
      }

      public UiService.UiId id() {
         return this.id;
      }

      public UiService.Message title() {
         return this.title;
      }

      public UiService.Message body() {
         return this.body;
      }
   }

   public static enum FieldType {
      TEXT,
      BOOLEAN,
      INTEGER,
      CHOICE;
   }

   public static final class FormDescriptor {
      private final UiService.UiId id;
      private final UiService.Message title;
      private final List<UiService.FormField> fields;

      public FormDescriptor(UiService.UiId id, UiService.Message title, List<UiService.FormField> fields) {
         this.id = Objects.requireNonNull(id, "id");
         this.title = Objects.requireNonNull(title, "title");
         this.fields = ApiValidation.immutableList(fields, 32, "fields");
      }

      public UiService.UiId id() {
         return this.id;
      }

      public UiService.Message title() {
         return this.title;
      }

      public List<UiService.FormField> fields() {
         return this.fields;
      }
   }

   public static final class FormField {
      private final UiService.UiId id;
      private final UiService.FieldType type;
      private final UiService.Message label;
      private final List<String> choices;

      public FormField(UiService.UiId id, UiService.FieldType type, UiService.Message label, List<String> choices) {
         this.id = Objects.requireNonNull(id, "id");
         this.type = Objects.requireNonNull(type, "type");
         this.label = Objects.requireNonNull(label, "label");
         this.choices = ApiValidation.immutableList(choices, 32, "choices");
      }

      public UiService.UiId id() {
         return this.id;
      }

      public UiService.FieldType type() {
         return this.type;
      }

      public UiService.Message label() {
         return this.label;
      }

      public List<String> choices() {
         return this.choices;
      }
   }

   public static final class FormResult {
      private final Map<String, String> values;

      public FormResult(Map<String, String> values) {
         if (values != null && values.size() <= 32) {
            LinkedHashMap<String, String> copy = new LinkedHashMap<>();

            for (Entry<String, String> entry : values.entrySet()) {
               copy.put(ApiValidation.text(entry.getKey(), "field", 96), ApiValidation.text(entry.getValue(), "value", 4096));
            }

            this.values = Collections.unmodifiableMap(copy);
         } else {
            throw new IllegalArgumentException("invalid form values");
         }
      }

      public Map<String, String> values() {
         return this.values;
      }

      @Override
      public String toString() {
         return "FormResult{fields=" + this.values.keySet() + '}';
      }
   }

   public static final class Message {
      private final String translationKey;
      private final String fallback;
      private final List<String> arguments;

      public Message(String translationKey, String fallback, List<String> arguments) {
         this.translationKey = ApiValidation.text(translationKey, "translationKey", 128);
         this.fallback = ApiValidation.text(fallback, "fallback", 4096);
         this.arguments = ApiValidation.immutableList(arguments, 16, "arguments");

         for (String argument : this.arguments) {
            if (argument.length() > 256 || ApiValidation.containsControls(argument)) {
               throw new IllegalArgumentException("invalid message argument");
            }

            ApiValidation.rejectSensitiveName(argument, "message argument");
         }
      }

      public String translationKey() {
         return this.translationKey;
      }

      public String fallback() {
         return this.fallback;
      }

      public List<String> arguments() {
         return this.arguments;
      }

      @Override
      public String toString() {
         return "Message{key='" + this.translationKey + "'}";
      }
   }

   public static final class SettingsCategory {
      private final UiService.UiId id;
      private final UiService.Message title;

      public SettingsCategory(UiService.UiId id, UiService.Message title) {
         this.id = Objects.requireNonNull(id, "id");
         this.title = Objects.requireNonNull(title, "title");
      }

      public UiService.UiId id() {
         return this.id;
      }

      public UiService.Message title() {
         return this.title;
      }
   }

   public static final class StatusBadge {
      private final UiService.UiId id;
      private final UiService.Message text;
      private final String state;

      public StatusBadge(UiService.UiId id, UiService.Message text, String state) {
         this.id = Objects.requireNonNull(id, "id");
         this.text = Objects.requireNonNull(text, "text");
         this.state = ApiValidation.text(state, "state", 32);
      }

      public UiService.UiId id() {
         return this.id;
      }

      public UiService.Message text() {
         return this.text;
      }

      public String state() {
         return this.state;
      }
   }

   public static final class UiId {
      private static final Pattern FORMAT = Pattern.compile("^[a-z][a-z0-9_.-]{0,31}:[a-z][a-z0-9_./-]{0,63}$");
      private final String value;

      public UiId(String value) {
         this.value = ApiValidation.identifier(value, "uiId", FORMAT);
      }

      public String value() {
         return this.value;
      }

      @Override
      public boolean equals(Object other) {
         return this == other || other instanceof UiService.UiId && this.value.equals(((UiService.UiId)other).value);
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
