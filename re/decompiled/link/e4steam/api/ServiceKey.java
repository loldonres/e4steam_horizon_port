package link.e4steam.api;

import java.util.Objects;
import java.util.regex.Pattern;

public final class ServiceKey<T> {
   private static final Pattern IDENTIFIER = Pattern.compile("^[a-z][a-z0-9_.-]{0,31}:[a-z][a-z0-9_./-]{0,63}$");
   private final String id;
   private final Class<T> serviceType;

   public ServiceKey(String id, Class<T> serviceType) {
      this.id = ApiValidation.identifier(id, "service id", IDENTIFIER);
      this.serviceType = Objects.requireNonNull(serviceType, "serviceType");
   }

   public String id() {
      return this.id;
   }

   public Class<T> serviceType() {
      return this.serviceType;
   }

   @Override
   public boolean equals(Object other) {
      if (this == other) {
         return true;
      } else if (!(other instanceof ServiceKey)) {
         return false;
      } else {
         ServiceKey<?> key = (ServiceKey<?>)other;
         return this.id.equals(key.id) && this.serviceType.equals(key.serviceType);
      }
   }

   @Override
   public int hashCode() {
      return Objects.hash(this.id, this.serviceType);
   }

   @Override
   public String toString() {
      return this.id;
   }
}
