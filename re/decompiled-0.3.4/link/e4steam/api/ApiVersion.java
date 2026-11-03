package link.e4steam.api;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ApiVersion implements Comparable<ApiVersion> {
   private static final Pattern SEMVER = Pattern.compile(
      "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$"
   );
   private final int major;
   private final int minor;
   private final int patch;
   private final String preRelease;
   private final String buildMetadata;

   public ApiVersion(int major, int minor, int patch, String preRelease, String buildMetadata) {
      if (major >= 0 && minor >= 0 && patch >= 0) {
         String text = major + "." + minor + "." + patch + (empty(preRelease) ? "" : "-" + preRelease) + (empty(buildMetadata) ? "" : "+" + buildMetadata);
         Matcher matcher = SEMVER.matcher(text);
         if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid semantic version");
         } else {
            validateNumericPrerelease(preRelease);
            this.major = major;
            this.minor = minor;
            this.patch = patch;
            this.preRelease = empty(preRelease) ? "" : preRelease;
            this.buildMetadata = empty(buildMetadata) ? "" : buildMetadata;
         }
      } else {
         throw new IllegalArgumentException("Version components must be non-negative");
      }
   }

   public static ApiVersion parse(String value) {
      if (value == null) {
         throw new NullPointerException("value");
      } else {
         Matcher matcher = SEMVER.matcher(value);
         if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid semantic version");
         } else {
            try {
               return new ApiVersion(
                  Integer.parseInt(matcher.group(1)),
                  Integer.parseInt(matcher.group(2)),
                  Integer.parseInt(matcher.group(3)),
                  nullToEmpty(matcher.group(4)),
                  nullToEmpty(matcher.group(5))
               );
            } catch (NumberFormatException var3) {
               throw new IllegalArgumentException("Semantic version component is too large");
            }
         }
      }
   }

   public int major() {
      return this.major;
   }

   public int minor() {
      return this.minor;
   }

   public int patch() {
      return this.patch;
   }

   public String preRelease() {
      return this.preRelease;
   }

   public String buildMetadata() {
      return this.buildMetadata;
   }

   public boolean isStable() {
      return this.preRelease.isEmpty();
   }

   public boolean isSameMajor(ApiVersion other) {
      return other != null && this.major == other.major;
   }

   public int compareTo(ApiVersion other) {
      Objects.requireNonNull(other, "other");
      int result = Integer.compare(this.major, other.major);
      if (result == 0) {
         result = Integer.compare(this.minor, other.minor);
      }

      if (result == 0) {
         result = Integer.compare(this.patch, other.patch);
      }

      return result != 0 ? result : comparePrerelease(this.preRelease, other.preRelease);
   }

   @Override
   public boolean equals(Object other) {
      if (this == other) {
         return true;
      } else if (!(other instanceof ApiVersion)) {
         return false;
      } else {
         ApiVersion version = (ApiVersion)other;
         return this.major == version.major
            && this.minor == version.minor
            && this.patch == version.patch
            && this.preRelease.equals(version.preRelease)
            && this.buildMetadata.equals(version.buildMetadata);
      }
   }

   @Override
   public int hashCode() {
      return Objects.hash(this.major, this.minor, this.patch, this.preRelease, this.buildMetadata);
   }

   @Override
   public String toString() {
      return this.major
         + "."
         + this.minor
         + "."
         + this.patch
         + (this.preRelease.isEmpty() ? "" : "-" + this.preRelease)
         + (this.buildMetadata.isEmpty() ? "" : "+" + this.buildMetadata);
   }

   private static int comparePrerelease(String left, String right) {
      if (left.isEmpty()) {
         return right.isEmpty() ? 0 : 1;
      } else if (right.isEmpty()) {
         return -1;
      } else {
         String[] leftParts = left.split("\\.");
         String[] rightParts = right.split("\\.");
         int count = Math.min(leftParts.length, rightParts.length);

         for (int index = 0; index < count; index++) {
            String a = leftParts[index];
            String b = rightParts[index];
            boolean aNumeric = numeric(a);
            boolean bNumeric = numeric(b);
            int result;
            if (aNumeric && bNumeric) {
               result = compareNumericIdentifiers(a, b);
            } else if (aNumeric != bNumeric) {
               result = aNumeric ? -1 : 1;
            } else {
               result = a.compareTo(b);
            }

            if (result != 0) {
               return result;
            }
         }

         return Integer.compare(leftParts.length, rightParts.length);
      }
   }

   private static int compareNumericIdentifiers(String left, String right) {
      int result = Integer.compare(left.length(), right.length());
      return result == 0 ? left.compareTo(right) : result;
   }

   private static void validateNumericPrerelease(String value) {
      if (!empty(value)) {
         for (String part : value.split("\\.")) {
            if (numeric(part) && part.length() > 1 && part.charAt(0) == '0') {
               throw new IllegalArgumentException("Numeric pre-release identifiers cannot have leading zeroes");
            }
         }
      }
   }

   private static boolean numeric(String value) {
      for (int index = 0; index < value.length(); index++) {
         if (!Character.isDigit(value.charAt(index))) {
            return false;
         }
      }

      return !value.isEmpty();
   }

   private static boolean empty(String value) {
      return value == null || value.isEmpty();
   }

   private static String nullToEmpty(String value) {
      return value == null ? "" : value;
   }
}
