package link.e4steam.internal.kaleido.lib.nightconfig.core.utils;

import java.util.Objects;
import java.util.Map.Entry;
import java.util.function.Function;

final class TransformingMapEntry<K, InternalV, ExternalV> implements Entry<K, ExternalV> {
   private final Function<? super InternalV, ? extends ExternalV> readTransformation;
   private final Function<? super ExternalV, ? extends InternalV> writeTransformation;
   private final Entry<K, InternalV> internalEntry;

   TransformingMapEntry(
      Entry<K, InternalV> internalEntry,
      Function<? super InternalV, ? extends ExternalV> readTransformation,
      Function<? super ExternalV, ? extends InternalV> writeTransformation
   ) {
      this.readTransformation = readTransformation;
      this.writeTransformation = writeTransformation;
      this.internalEntry = internalEntry;
   }

   @Override
   public K getKey() {
      return this.internalEntry.getKey();
   }

   @Override
   public ExternalV getValue() {
      return (ExternalV)this.readTransformation.apply(this.internalEntry.getValue());
   }

   @Override
   public ExternalV setValue(ExternalV value) {
      return (ExternalV)this.readTransformation.apply(this.internalEntry.setValue((InternalV)this.writeTransformation.apply(value)));
   }

   @Override
   public boolean equals(Object obj) {
      if (obj == this) {
         return true;
      } else if (!(obj instanceof Entry)) {
         return false;
      } else {
         Entry<?, ?> entry = (Entry<?, ?>)obj;
         return Objects.equals(this.getKey(), entry.getKey()) && Objects.equals(this.getValue(), entry.getValue());
      }
   }

   @Override
   public int hashCode() {
      return Objects.hashCode(this.getKey()) ^ Objects.hashCode(this.getValue());
   }
}
