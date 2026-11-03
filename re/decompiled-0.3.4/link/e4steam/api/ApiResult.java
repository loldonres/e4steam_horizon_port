package link.e4steam.api;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

public final class ApiResult<T> {
   private final T value;
   private final ApiError error;

   private ApiResult(T value, ApiError error) {
      this.value = value;
      this.error = error;
   }

   public static <T> ApiResult<T> success(T value) {
      return new ApiResult<>(Objects.requireNonNull(value, "value"), null);
   }

   public static <T> ApiResult<T> failure(ApiError error) {
      return new ApiResult<>(null, Objects.requireNonNull(error, "error"));
   }

   public boolean isSuccess() {
      return this.error == null;
   }

   public Optional<T> value() {
      return Optional.ofNullable(this.value);
   }

   public Optional<ApiError> error() {
      return Optional.ofNullable(this.error);
   }

   public <R> ApiResult<R> map(Function<? super T, ? extends R> mapper) {
      Objects.requireNonNull(mapper, "mapper");
      return (ApiResult<R>)(this.isSuccess() ? success(mapper.apply(this.value)) : failure(this.error));
   }

   @Override
   public String toString() {
      return this.isSuccess() ? "ApiResult{success}" : "ApiResult{" + this.error + '}';
   }
}
