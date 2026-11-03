package link.e4steam.internal.kaleido.lib.nightconfig.core.utils;

import java.util.Set;
import java.util.function.Function;

public final class TransformingSet<InternalV, ExternalV> extends TransformingCollection<InternalV, ExternalV> implements Set<ExternalV> {
   public TransformingSet(
      Set<InternalV> internalCollection,
      Function<? super InternalV, ? extends ExternalV> readTransformation,
      Function<? super ExternalV, ? extends InternalV> writeTransformation,
      Function<Object, Object> searchTransformation
   ) {
      super(internalCollection, readTransformation, writeTransformation, searchTransformation);
   }
}
