package link.e4steam.internal.kaleido.lib.quiltconfig.api.metadata;

import java.security.InvalidParameterException;
import java.util.Objects;

public class DisplayName {
   private final String name;
   private final boolean translatable;

   public DisplayName(String name, boolean translatable) {
      if (name != null && !name.isEmpty()) {
         this.translatable = translatable;
         this.name = name;
      } else {
         throw new InvalidParameterException("Cannot set serialized name to an empty value!");
      }
   }

   public String getName() {
      return this.name;
   }

   public boolean isTranslatable() {
      return this.translatable;
   }

   @Override
   public boolean equals(Object o) {
      if (this == o) {
         return true;
      } else if (o != null && this.getClass() == o.getClass()) {
         DisplayName that = (DisplayName)o;
         return this.translatable == that.translatable && Objects.equals(this.name, that.name);
      } else {
         return false;
      }
   }

   @Override
   public int hashCode() {
      return Objects.hash(this.name, this.translatable);
   }
}
