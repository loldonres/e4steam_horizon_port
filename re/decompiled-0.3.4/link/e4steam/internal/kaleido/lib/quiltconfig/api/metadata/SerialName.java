package link.e4steam.internal.kaleido.lib.quiltconfig.api.metadata;

import java.security.InvalidParameterException;
import java.util.Objects;

public class SerialName {
   private final String name;

   public SerialName(String name) {
      if (name == null || name.isEmpty()) {
         throw new InvalidParameterException("Cannot set serialized name to an empty value!");
      } else if (name.contains(" ")) {
         throw new InvalidParameterException("Cannot set serialized name to a value with spaces!");
      } else {
         this.name = name;
      }
   }

   public String getName() {
      return this.name;
   }

   @Override
   public boolean equals(Object o) {
      if (this == o) {
         return true;
      } else if (o != null && this.getClass() == o.getClass()) {
         SerialName that = (SerialName)o;
         return Objects.equals(this.name, that.name);
      } else {
         return false;
      }
   }

   @Override
   public int hashCode() {
      return Objects.hash(this.name);
   }
}
