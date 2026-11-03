package link.e4steam.api;

import java.util.Objects;

public final class ApiVersionRange {
   private final ApiVersion minimumInclusive;
   private final ApiVersion maximumExclusive;

   public ApiVersionRange(ApiVersion minimumInclusive, ApiVersion maximumExclusive) {
      this.minimumInclusive = Objects.requireNonNull(minimumInclusive, "minimumInclusive");
      this.maximumExclusive = Objects.requireNonNull(maximumExclusive, "maximumExclusive");
      if (minimumInclusive.compareTo(maximumExclusive) >= 0) {
         throw new IllegalArgumentException("API version range must be non-empty");
      }
   }

   public ApiVersion minimumInclusive() {
      return this.minimumInclusive;
   }

   public ApiVersion maximumExclusive() {
      return this.maximumExclusive;
   }

   public boolean contains(ApiVersion version) {
      Objects.requireNonNull(version, "version");
      return version.compareTo(this.minimumInclusive) >= 0 && version.compareTo(this.maximumExclusive) < 0;
   }

   @Override
   public boolean equals(Object other) {
      if (this == other) {
         return true;
      } else if (!(other instanceof ApiVersionRange)) {
         return false;
      } else {
         ApiVersionRange range = (ApiVersionRange)other;
         return this.minimumInclusive.equals(range.minimumInclusive) && this.maximumExclusive.equals(range.maximumExclusive);
      }
   }

   @Override
   public int hashCode() {
      return Objects.hash(this.minimumInclusive, this.maximumExclusive);
   }

   @Override
   public String toString() {
      return "[" + this.minimumInclusive + ", " + this.maximumExclusive + ")";
   }
}
