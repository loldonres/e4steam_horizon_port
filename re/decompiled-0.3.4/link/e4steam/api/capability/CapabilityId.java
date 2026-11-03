package link.e4steam.api.capability;

import java.util.Objects;
import java.util.regex.Pattern;

public final class CapabilityId implements Comparable<CapabilityId> {
   private static final Pattern PATTERN = Pattern.compile("^[a-z][a-z0-9]*(?:[._-][a-z0-9]+)+$");
   private final String value;

   public CapabilityId(String value) {
      if (value == null) {
         throw new NullPointerException("value");
      } else {
         String checked = value.trim();
         if (!checked.isEmpty() && checked.length() <= 96 && PATTERN.matcher(checked).matches()) {
            this.value = checked;
         } else {
            throw new IllegalArgumentException("Invalid capability id");
         }
      }
   }

   public String value() {
      return this.value;
   }

   public int compareTo(CapabilityId other) {
      return this.value.compareTo(Objects.requireNonNull(other, "other").value);
   }

   @Override
   public boolean equals(Object other) {
      return this == other || other instanceof CapabilityId && this.value.equals(((CapabilityId)other).value);
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
