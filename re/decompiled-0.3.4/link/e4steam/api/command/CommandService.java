package link.e4steam.api.command;

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
import link.e4steam.api.ui.UiService;

public interface CommandService {
   ApiResult<Registration> register(CommandService.CommandDescriptor var1, CommandService.CommandHandler var2);

   public static final class ArgumentDescriptor {
      private final String name;
      private final CommandService.ArgumentType type;
      private final boolean required;
      private final List<String> choices;

      public ArgumentDescriptor(String name, CommandService.ArgumentType type, boolean required, List<String> choices) {
         this.name = ApiValidation.text(name, "name", 48);
         this.type = Objects.requireNonNull(type, "type");
         this.required = required;
         this.choices = ApiValidation.immutableList(choices, 64, "choices");
      }

      public String name() {
         return this.name;
      }

      public CommandService.ArgumentType type() {
         return this.type;
      }

      public boolean required() {
         return this.required;
      }

      public List<String> choices() {
         return this.choices;
      }
   }

   public static enum ArgumentType {
      STRING,
      INTEGER,
      BOOLEAN,
      PLAYER,
      UUID,
      ENUM;
   }

   public static final class CommandContext {
      private final CommandService.SourceType sourceType;
      private final Map<String, String> arguments;
      private final boolean permitted;

      public CommandContext(CommandService.SourceType sourceType, Map<String, String> arguments, boolean permitted) {
         this.sourceType = Objects.requireNonNull(sourceType, "sourceType");
         if (arguments != null && arguments.size() <= 32) {
            LinkedHashMap<String, String> copy = new LinkedHashMap<>();

            for (Entry<String, String> entry : arguments.entrySet()) {
               String key = ApiValidation.text(entry.getKey(), "argument name", 48);
               String value = ApiValidation.text(entry.getValue(), "argument value", 4096);
               ApiValidation.rejectSensitiveName(key, "argument name");
               copy.put(key, value);
            }

            this.arguments = Collections.unmodifiableMap(copy);
            this.permitted = permitted;
         } else {
            throw new IllegalArgumentException("invalid arguments");
         }
      }

      public CommandService.SourceType sourceType() {
         return this.sourceType;
      }

      public Map<String, String> arguments() {
         return this.arguments;
      }

      public boolean permitted() {
         return this.permitted;
      }

      @Override
      public String toString() {
         return "CommandContext{source=" + this.sourceType + ", argumentNames=" + this.arguments.keySet() + '}';
      }
   }

   public static final class CommandDescriptor {
      private final CommandService.CommandId id;
      private final UiService.Message description;
      private final List<CommandService.ArgumentDescriptor> arguments;
      private final boolean hostOnly;

      public CommandDescriptor(CommandService.CommandId id, UiService.Message description, List<CommandService.ArgumentDescriptor> arguments, boolean hostOnly) {
         this.id = Objects.requireNonNull(id, "id");
         this.description = Objects.requireNonNull(description, "description");
         this.arguments = ApiValidation.immutableList(arguments, 32, "arguments");
         this.hostOnly = hostOnly;
      }

      public CommandService.CommandId id() {
         return this.id;
      }

      public UiService.Message description() {
         return this.description;
      }

      public List<CommandService.ArgumentDescriptor> arguments() {
         return this.arguments;
      }

      public boolean hostOnly() {
         return this.hostOnly;
      }
   }

   public interface CommandHandler {
      CompletionStage<ApiResult<CommandService.CommandOutput>> execute(CommandService.CommandContext var1);

      CompletionStage<ApiResult<List<String>>> suggest(String var1, String var2);
   }

   public static final class CommandId {
      private static final Pattern FORMAT = Pattern.compile("^[a-z][a-z0-9_.-]{0,31}:[a-z][a-z0-9_.-]{0,63}$");
      private final String value;

      public CommandId(String value) {
         this.value = ApiValidation.identifier(value, "commandId", FORMAT);
      }

      public String value() {
         return this.value;
      }

      @Override
      public boolean equals(Object other) {
         return this == other || other instanceof CommandService.CommandId && this.value.equals(((CommandService.CommandId)other).value);
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

   public static final class CommandOutput {
      private final boolean success;
      private final String safeCode;
      private final UiService.Message message;

      public CommandOutput(boolean success, String safeCode, UiService.Message message) {
         this.success = success;
         this.safeCode = ApiValidation.text(safeCode, "safeCode", 96);
         this.message = Objects.requireNonNull(message, "message");
      }

      public boolean success() {
         return this.success;
      }

      public String safeCode() {
         return this.safeCode;
      }

      public UiService.Message message() {
         return this.message;
      }
   }

   public static enum SourceType {
      PLAYER,
      INTEGRATED_HOST,
      DEDICATED_CONSOLE,
      COMMAND_BLOCK;
   }
}
