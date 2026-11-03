package link.e4steam.internal.kaleido.lib.quiltconfig.api;

import java.util.Comparator;
import java.util.Optional;
import java.util.regex.Pattern;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.values.CompoundConfigValue;

public interface Constraint<T> {
   Optional<String> test(T var1);

   String getRepresentation();

   static Constraint<Integer> range(int from, int to) {
      return new Constraint.Range<>(from, to, Integer::compareTo);
   }

   static Constraint<Long> range(long from, long to) {
      return new Constraint.Range<>(from, to, Long::compareTo);
   }

   static Constraint<Float> range(float from, float to) {
      return new Constraint.Range<>(from, to, Float::compareTo);
   }

   static Constraint<Double> range(double from, double to) {
      return new Constraint.Range<>(from, to, Double::compareTo);
   }

   static <T> Constraint<CompoundConfigValue<T>> all(Constraint<T> constraint) {
      return new Constraint.All<>(constraint);
   }

   static Constraint<String> matching(String regex) {
      return new Constraint<String>() {
         private final Pattern pattern = Pattern.compile(regex);

         public Optional<String> test(String value) {
            return this.pattern.matcher(value).matches()
               ? Optional.empty()
               : Optional.of(String.format("Value '%s' does not match pattern '%s'", value, regex));
         }

         @Override
         public String getRepresentation() {
            return "matches r'" + regex + "'";
         }
      };
   }

   public static final class All<T> implements Constraint<CompoundConfigValue<T>> {
      private final Constraint<T> constraint;

      public All(Constraint<T> constraint) {
         this.constraint = constraint;
      }

      public Optional<String> test(CompoundConfigValue<T> value) {
         StringBuilder builder = new StringBuilder();

         for (T t : value.values()) {
            Optional<String> error = this.constraint.test(t);
            if (error.isPresent()) {
               if (builder.length() != 0) {
                  builder.append(", ");
               }

               builder.append(error.get());
            }
         }

         return builder.length() == 0 ? Optional.empty() : Optional.of(builder.toString());
      }

      @Override
      public String getRepresentation() {
         return "all(" + this.constraint.getRepresentation() + ")";
      }
   }

   public static final class Range<T> implements Constraint<T> {
      private final T min;
      private final T max;
      private final Comparator<T> comparator;

      public Range(T min, T max, Comparator<T> comparator) {
         this.min = min;
         this.max = max;
         this.comparator = comparator;
      }

      public T min() {
         return this.min;
      }

      public T max() {
         return this.max;
      }

      @Override
      public Optional<String> test(T value) {
         int minTest = this.comparator.compare(this.min, value);
         int maxTest = this.comparator.compare(this.max, value);
         return minTest <= 0 && maxTest >= 0 ? Optional.empty() : Optional.of(String.format("Value '%s' outside of range [%s, %s]", value, this.min, this.max));
      }

      @Override
      public String getRepresentation() {
         return "range: " + this.min + " - " + this.max;
      }
   }
}
