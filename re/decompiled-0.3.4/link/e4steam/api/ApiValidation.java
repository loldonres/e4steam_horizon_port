package link.e4steam.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class ApiValidation {
   private ApiValidation() {
   }

   public static String text(String value, String field, int maximumLength) {
      if (value == null) {
         throw new NullPointerException(field);
      } else {
         String trimmed = value.trim();
         if (!trimmed.isEmpty() && trimmed.length() <= maximumLength && !containsControls(trimmed)) {
            return trimmed;
         } else {
            throw new IllegalArgumentException(field + " has an invalid length");
         }
      }
   }

   public static String identifier(String value, String field, Pattern pattern) {
      String checked = text(value, field, 96);
      if (!pattern.matcher(checked).matches()) {
         throw new IllegalArgumentException(field + " has an invalid format");
      } else {
         return checked;
      }
   }

   public static <T> List<T> immutableList(List<T> values, int maximumSize, String field) {
      if (values == null) {
         throw new NullPointerException(field);
      } else if (values.size() > maximumSize) {
         throw new IllegalArgumentException(field + " exceeds its size limit");
      } else {
         ArrayList<T> copy = new ArrayList<>(values.size());

         for (T value : values) {
            if (value == null) {
               throw new NullPointerException(field + " contains null");
            }

            copy.add(value);
         }

         return Collections.unmodifiableList(copy);
      }
   }

   public static <T> Set<T> immutableSet(Set<T> values, int maximumSize, String field) {
      if (values == null) {
         throw new NullPointerException(field);
      } else if (values.size() > maximumSize) {
         throw new IllegalArgumentException(field + " exceeds its size limit");
      } else {
         LinkedHashSet<T> copy = new LinkedHashSet<>();

         for (T value : values) {
            if (value == null) {
               throw new NullPointerException(field + " contains null");
            }

            copy.add(value);
         }

         return Collections.unmodifiableSet(copy);
      }
   }

   public static String optionalText(String value, String field, int maximumLength) {
      return value != null && !value.trim().isEmpty() ? text(value, field, maximumLength) : "";
   }

   public static byte[] bytes(byte[] value, int maximumSize, String field) {
      if (value == null) {
         throw new NullPointerException(field);
      } else if (value.length > maximumSize) {
         throw new IllegalArgumentException(field + " exceeds its size limit");
      } else {
         return (byte[])value.clone();
      }
   }

   public static void rejectSensitiveName(String value, String field) {
      String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT);
      String[] forbidden = new String[]{"password", "token", "ticket", "secret", "cookie", "authorization", "joinaddress", "gslt"};

      for (String candidate : forbidden) {
         if (normalized.contains(candidate)) {
            throw new IllegalArgumentException(field + " contains a forbidden sensitive name");
         }
      }
   }

   public static boolean containsControls(String value) {
      for (int index = 0; index < value.length(); index++) {
         if (Character.isISOControl(value.charAt(index))) {
            return true;
         }
      }

      return false;
   }
}
