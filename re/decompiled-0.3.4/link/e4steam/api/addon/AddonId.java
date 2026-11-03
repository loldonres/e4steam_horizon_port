package link.e4steam.api.addon;

import java.util.Objects;
import java.util.regex.Pattern;

public final class AddonId implements Comparable<AddonId> {
   private static final Pattern PATTERN = Pattern.compile("^[a-z][a-z0-9_.-]{0,31}:[a-z][a-z0-9_./-]{0,63}$");
   private final String value;

   public AddonId(String value) {
      if (value == null) {
         throw new NullPointerException("value");
      } else {
         String checked = value.trim();
         if (!checked.isEmpty() && checked.length() <= 96 && PATTERN.matcher(checked).matches()) {
            this.value = checked;
         } else {
            throw new IllegalArgumentException("Invalid namespaced addon id");
         }
      }
   }

   public String value() {
      return this.value;
   }

   public int compareTo(AddonId other) {
      return this.value.compareTo(Objects.requireNonNull(other, "other").value);
   }

   @Override
   public boolean equals(Object other) {
      return this == other || other instanceof AddonId && this.value.equals(((AddonId)other).value);
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
