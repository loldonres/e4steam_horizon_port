package link.e4steam.internal.kaleido.lib.nightconfig.core.utils;

import java.util.Collection;
import java.util.Iterator;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

public class TransformingCollection<InternalV, ExternalV> implements Collection<ExternalV> {
   protected final Function<? super InternalV, ? extends ExternalV> readTransformation;
   protected final Function<? super ExternalV, ? extends InternalV> writeTransformation;
   protected final Function<Object, Object> searchTransformation;
   protected final Collection<InternalV> internalCollection;

   public TransformingCollection(
      Collection<InternalV> internalCollection,
      Function<? super InternalV, ? extends ExternalV> readTransformation,
      Function<? super ExternalV, ? extends InternalV> writeTransformation,
      Function<Object, Object> searchTransformation
   ) {
      this.internalCollection = internalCollection;
      this.readTransformation = readTransformation;
      this.writeTransformation = writeTransformation;
      this.searchTransformation = searchTransformation;
   }

   @Override
   public int size() {
      return this.internalCollection.size();
   }

   @Override
   public boolean isEmpty() {
      return this.internalCollection.isEmpty();
   }

   @Override
   public boolean contains(Object o) {
      return this.internalCollection.contains(this.searchTransformation.apply(o));
   }

   @Override
   public Iterator<ExternalV> iterator() {
      return new TransformingIterator<>(this.internalCollection.iterator(), this.readTransformation);
   }

   @Override
   public Object[] toArray() {
      Object[] array = this.internalCollection.toArray();

      for (int i = 0; i < array.length; i++) {
         array[i] = this.readTransformation.apply((InternalV)array[i]);
      }

      return array;
   }

   @Override
   public <T> T[] toArray(T[] a) {
      T[] array = (T[])this.internalCollection.toArray(a);

      for (int i = 0; i < array.length; i++) {
         array[i] = (T)this.readTransformation.apply((InternalV)array[i]);
      }

      return array;
   }

   @Override
   public boolean add(ExternalV value) {
      return this.internalCollection.add((InternalV)this.writeTransformation.apply(value));
   }

   @Override
   public boolean remove(Object o) {
      return this.internalCollection.remove(this.searchTransformation.apply(o));
   }

   @Override
   public boolean containsAll(Collection<?> c) {
      return this.internalCollection
         .containsAll(new TransformingCollection<>((Collection<Object>)c, this.searchTransformation, o -> (Object)o, this.searchTransformation));
   }

   @Override
   public boolean addAll(Collection<? extends ExternalV> c) {
      return this.internalCollection.addAll(new TransformingCollection<>(c, this.writeTransformation, this.readTransformation, this.searchTransformation));
   }

   @Override
   public boolean removeAll(Collection<?> c) {
      return this.internalCollection
         .removeAll(new TransformingCollection<>((Collection<Object>)c, this.searchTransformation, o -> (Object)o, this.searchTransformation));
   }

   @Override
   public boolean removeIf(Predicate<? super ExternalV> filter) {
      return this.internalCollection.removeIf(internalV -> filter.test((ExternalV)this.readTransformation.apply(internalV)));
   }

   @Override
   public boolean retainAll(Collection<?> c) {
      return this.internalCollection
         .retainAll(new TransformingCollection<>((Collection<Object>)c, this.searchTransformation, o -> (Object)o, this.searchTransformation));
   }

   @Override
   public void clear() {
      this.internalCollection.clear();
   }

   @Override
   public Spliterator<ExternalV> spliterator() {
      return new TransformingSpliterator<>(this.internalCollection.spliterator(), this.readTransformation, this.writeTransformation);
   }

   @Override
   public Stream<ExternalV> stream() {
      return this.internalCollection.stream().map(this.readTransformation);
   }

   @Override
   public Stream<ExternalV> parallelStream() {
      return this.internalCollection.parallelStream().map(this.readTransformation);
   }

   @Override
   public void forEach(Consumer<? super ExternalV> action) {
      this.internalCollection.forEach(internalV -> action.accept((ExternalV)this.readTransformation.apply(internalV)));
   }

   @Override
   public int hashCode() {
      return this.internalCollection.hashCode();
   }

   @Override
   public boolean equals(Object obj) {
      return this.internalCollection.equals(obj);
   }

   @Override
   public String toString() {
      return this.internalCollection.toString();
   }
}
