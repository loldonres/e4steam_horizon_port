package link.e4steam.internal.kaleido.lib.quiltconfig.impl;

import java.util.Map;
import java.util.Optional;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.metadata.MetadataContainer;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.metadata.MetadataType;

public abstract class AbstractMetadataContainer implements MetadataContainer {
   public Map<MetadataType<?, ?>, Object> metadata;

   protected AbstractMetadataContainer(Map<MetadataType<?, ?>, Object> metadata) {
      this.metadata = metadata;
   }

   @Override
   public <M> M metadata(MetadataType<M, ?> type) {
      if (this.metadata.containsKey(type)) {
         return (M)this.metadata.get(type);
      } else {
         Optional<M> defaultValue = type.getDefaultValue(this);
         if (defaultValue.isPresent()) {
            this.metadata.put(type, defaultValue.get());
            return (M)this.metadata.get(type);
         } else {
            return null;
         }
      }
   }

   @Override
   public <M> boolean hasMetadata(MetadataType<M, ?> type) {
      return this.metadata.containsKey(type) || type.getDefaultValue(this).isPresent();
   }

   @Override
   public Map<MetadataType<?, ?>, Object> metadata() {
      return this.metadata;
   }
}
