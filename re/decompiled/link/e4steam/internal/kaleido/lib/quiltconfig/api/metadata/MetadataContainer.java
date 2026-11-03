package link.e4steam.internal.kaleido.lib.quiltconfig.api.metadata;

import java.util.Map;

public interface MetadataContainer {
   <M> M metadata(MetadataType<M, ?> var1);

   <M> boolean hasMetadata(MetadataType<M, ?> var1);

   Map<MetadataType<?, ?>, Object> metadata();
}
