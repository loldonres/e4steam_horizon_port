package link.e4steam.api.runtime;

import java.util.Objects;

public final class LoaderInfo {
   private final String id;
   private final String version;

   public LoaderInfo(String id, String version) {
      this.id = safe(id, "loader id", 32);
      this.version = safe(version, "loader version", 64);
   }

   public String id() {
      return this.id;
   }

   public String version() {
      return this.version;
   }

   @Override
   public boolean equals(Object other) {
      if (this == other) {
         return true;
      } else if (!(other instanceof LoaderInfo)) {
         return false;
      } else {
         LoaderInfo info = (LoaderInfo)other;
         return this.id.equals(info.id) && this.version.equals(info.version);
      }
   }

   @Override
   public int hashCode() {
      return Objects.hash(this.id, this.version);
   }

   @Override
   public String toString() {
      return this.id + '-' + this.version;
   }

   private static String safe(String value, String field, int max) {
      if (value == null) {
         throw new NullPointerException(field);
      } else {
         String checked = value.trim();
         if (!checked.isEmpty() && checked.length() <= max) {
            return checked;
         } else {
            throw new IllegalArgumentException(field + " has an invalid length");
         }
      }
   }
}
