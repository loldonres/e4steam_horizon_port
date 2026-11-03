package link.e4steam.internal.kaleido.lib.quiltconfig.impl.util;

import java.util.Iterator;
import org.jetbrains.annotations.NotNull;

public final class ImmutableIterable<T> implements Iterable<T> {
   private final Iterable<T> itr;

   public ImmutableIterable(Iterable<T> itr) {
      this.itr = itr;
   }

   @NotNull
   @Override
   public Iterator<T> iterator() {
      return new Iterator<T>() {
         private final Iterator<T> itr = ImmutableIterable.this.itr.iterator();

         @Override
         public boolean hasNext() {
            return this.itr.hasNext();
         }

         @Override
         public T next() {
            return this.itr.next();
         }
      };
   }
}
