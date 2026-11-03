package link.e4steam.internal.kaleido.lib.nightconfig.core.utils;

import java.util.Comparator;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.function.Function;

public final class TransformingSpliterator<InternalV, ExternalV> implements Spliterator<ExternalV> {
   private final Function<? super InternalV, ? extends ExternalV> readTransformation;
   private final Function<? super ExternalV, ? extends InternalV> writeTransformation;
   private final Spliterator<InternalV> internalSpliterator;

   public TransformingSpliterator(
      Spliterator<InternalV> internalSpliterator,
      Function<? super InternalV, ? extends ExternalV> readTransformation,
      Function<? super ExternalV, ? extends InternalV> writeTransformation
   ) {
      this.readTransformation = readTransformation;
      this.writeTransformation = writeTransformation;
      this.internalSpliterator = internalSpliterator;
   }

   @Override
   public boolean tryAdvance(Consumer<? super ExternalV> action) {
      return this.internalSpliterator.tryAdvance(internalV -> action.accept((ExternalV)this.readTransformation.apply(internalV)));
   }

   @Override
   public void forEachRemaining(Consumer<? super ExternalV> action) {
      this.internalSpliterator.forEachRemaining(internalV -> action.accept((ExternalV)this.readTransformation.apply(internalV)));
   }

   @Override
   public Spliterator<ExternalV> trySplit() {
      return new TransformingSpliterator<>(this.internalSpliterator.trySplit(), this.readTransformation, this.writeTransformation);
   }

   @Override
   public long estimateSize() {
      return this.internalSpliterator.estimateSize();
   }

   @Override
   public long getExactSizeIfKnown() {
      return this.internalSpliterator.getExactSizeIfKnown();
   }

   @Override
   public int characteristics() {
      return this.internalSpliterator.characteristics();
   }

   @Override
   public boolean hasCharacteristics(int characteristics) {
      return this.internalSpliterator.hasCharacteristics(characteristics);
   }

   @Override
   public Comparator<? super ExternalV> getComparator() {
      return (o1, o2) -> this.internalSpliterator
         .getComparator()
         .compare((InternalV)this.writeTransformation.apply(o1), (InternalV)this.writeTransformation.apply(o2));
   }

   @Override
   public int hashCode() {
      return this.internalSpliterator.hashCode();
   }

   @Override
   public boolean equals(Object obj) {
      return this.internalSpliterator.equals(obj);
   }

   @Override
   public String toString() {
      return this.internalSpliterator.toString();
   }
}
