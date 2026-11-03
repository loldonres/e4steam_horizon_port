package link.e4steam.internal.kaleido.lib.quiltconfig.api.values;

import java.util.Map;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.metadata.MetadataContainer;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.metadata.MetadataType;
import org.jetbrains.annotations.ApiStatus.NonExtendable;

public interface ValueTreeNode extends MetadataContainer {
   ValueKey key();

   void propagateInheritedMetadata(Map<MetadataType<?, ?>, Object> var1);

   @NonExtendable
   public interface Section extends ValueTreeNode, Iterable<ValueTreeNode> {
   }
}
