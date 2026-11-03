package link.e4steam.internal.kaleido.lib.quiltconfig.api.metadata;

import java.util.function.Consumer;

public interface MetadataContainerBuilder<SELF extends MetadataContainerBuilder<SELF>> {
   <M, B extends MetadataType.Builder<M>> SELF metadata(MetadataType<M, B> var1, Consumer<B> var2);
}
