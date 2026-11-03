package link.e4steam.internal.kaleido.lib.quiltconfig.impl.builders;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Consumer;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.Config;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.metadata.MetadataType;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.values.TrackedValue;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.values.ValueKey;
import link.e4steam.internal.kaleido.lib.quiltconfig.impl.tree.SectionTreeNode;
import link.e4steam.internal.kaleido.lib.quiltconfig.impl.tree.TrackedValueImpl;
import link.e4steam.internal.kaleido.lib.quiltconfig.impl.tree.Trie;

public class SectionBuilderImpl implements Config.SectionBuilder {
   private final ValueKey key;
   private final ConfigBuilderImpl builder;
   final Map<MetadataType<?, ?>, MetadataType.Builder<?>> metadata = new LinkedHashMap<>();
   private final Map<ValueKey, TrackedValueImpl<?>> values = new LinkedHashMap<>();

   public SectionBuilderImpl(ValueKey key, ConfigBuilderImpl builder) {
      this.key = key;
      this.builder = builder;
   }

   @Override
   public Config.SectionBuilder field(TrackedValue<?> value) {
      ValueKey key = this.key.child(value.key());
      this.values.put(key, ((TrackedValueImpl)value).setKey(key));
      return this;
   }

   @Override
   public Config.SectionBuilder section(String key, Consumer<Config.SectionBuilder> creator) {
      ValueKey valueKey = this.key.child(key);
      SectionBuilderImpl sectionBuilder = new SectionBuilderImpl(valueKey, this.builder);
      creator.accept(sectionBuilder);
      this.builder.values.put(valueKey, sectionBuilder);
      return this;
   }

   @Override
   public <M, B extends MetadataType.Builder<M>> Config.SectionBuilder metadata(MetadataType<M, B> type, Consumer<B> builderConsumer) {
      builderConsumer.accept((B)this.metadata.computeIfAbsent(type, t -> type.newBuilder()));
      return this;
   }

   public Map<MetadataType<?, ?>, Object> buildMetadata() {
      Map<MetadataType<?, ?>, Object> metadata = new LinkedHashMap<>();

      for (Entry<MetadataType<?, ?>, MetadataType.Builder<?>> entry : this.metadata.entrySet()) {
         metadata.put(entry.getKey(), entry.getValue().build());
      }

      return metadata;
   }

   public void build(Trie.Node node) {
      node.setValue(new SectionTreeNode(node, this.buildMetadata()));

      for (Entry<ValueKey, TrackedValueImpl<?>> entry : this.values.entrySet()) {
         this.builder.values.put(entry.getKey(), entry.getValue());
      }
   }
}
