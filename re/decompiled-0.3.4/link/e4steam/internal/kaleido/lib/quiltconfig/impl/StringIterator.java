package link.e4steam.internal.kaleido.lib.quiltconfig.impl;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import link.e4steam.internal.kaleido.lib.quiltconfig.impl.util.ImmutableIterable;
import org.jetbrains.annotations.NotNull;

public class StringIterator implements Iterable<String> {
   private final List<String> strings;

   public StringIterator(List<String> strings) {
      this.strings = new ArrayList<>(strings);
   }

   @NotNull
   @Override
   public Iterator<String> iterator() {
      return new ImmutableIterable<>(this.strings).iterator();
   }

   @Override
   public boolean equals(Object o) {
      if (this == o) {
         return true;
      } else if (o != null && this.getClass() == o.getClass()) {
         StringIterator iterator = (StringIterator)o;
         return Objects.equals(this.strings, iterator.strings);
      } else {
         return false;
      }
   }

   @Override
   public int hashCode() {
      return Objects.hash(this.strings);
   }
}
