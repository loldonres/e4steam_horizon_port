package link.e4steam.api;

import java.util.Objects;

public final class ApiError {
   private final ApiErrorCode code;
   private final String messageKey;
   private final Retryability retryability;
   private final String operation;
   private final String correlationId;
   private final String causeCategory;

   public ApiError(ApiErrorCode code, String messageKey, Retryability retryability, String operation, String correlationId, String causeCategory) {
      this.code = Objects.requireNonNull(code, "code");
      this.messageKey = ApiValidation.text(messageKey, "messageKey", 128);
      this.retryability = Objects.requireNonNull(retryability, "retryability");
      this.operation = ApiValidation.text(operation, "operation", 96);
      this.correlationId = optionalSafe(correlationId, 64);
      this.causeCategory = optionalSafe(causeCategory, 64);
   }

   public ApiErrorCode code() {
      return this.code;
   }

   public String messageKey() {
      return this.messageKey;
   }

   public Retryability retryability() {
      return this.retryability;
   }

   public String operation() {
      return this.operation;
   }

   public String correlationId() {
      return this.correlationId;
   }

   public String causeCategory() {
      return this.causeCategory;
   }

   @Override
   public String toString() {
      return "ApiError{code="
         + this.code
         + ", messageKey='"
         + this.messageKey
         + '\''
         + ", retryability="
         + this.retryability
         + ", operation='"
         + this.operation
         + '\''
         + (this.correlationId.isEmpty() ? "" : ", correlationId='" + this.correlationId + '\'')
         + (this.causeCategory.isEmpty() ? "" : ", causeCategory='" + this.causeCategory + '\'')
         + '}';
   }

   private static String optionalSafe(String value, int maximumLength) {
      return value != null && !value.trim().isEmpty() ? ApiValidation.text(value, "optional safe field", maximumLength) : "";
   }
}
