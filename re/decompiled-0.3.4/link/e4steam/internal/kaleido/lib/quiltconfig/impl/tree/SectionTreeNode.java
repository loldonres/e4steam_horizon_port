package link.e4steam.internal.kaleido.lib.quiltconfig.impl.tree;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.metadata.MetadataType;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.values.ValueKey;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.values.ValueTreeNode;
import link.e4steam.internal.kaleido.lib.quiltconfig.impl.AbstractMetadataContainer;
import org.jetbrains.annotations.NotNull;

public final class SectionTreeNode extends AbstractMetadataContainer implements ValueTreeNode.Section {
   private final Trie.Node node;

   public SectionTreeNode(Trie.Node node, Map<MetadataType<?, ?>, Object> metadata) {
      super(metadata);
      this.node = node;
   }

   @Override
   public ValueKey key() {
      return this.node.getKey();
   }

   @Override
   public void propagateInheritedMetadata(Map<MetadataType<?, ?>, Object> inheritedMetadata) {
      for (Entry<MetadataType<?, ?>, Object> entry : inheritedMetadata.entrySet()) {
         this.metadata.putIfAbsent(entry.getKey(), entry.getValue());
      }

      Map<MetadataType<?, ?>, Object> inheritorMetadata = new LinkedHashMap<>();

      for (Entry<MetadataType<?, ?>, Object> entry : this.metadata.entrySet()) {
         if (entry.getKey().isInherited()) {
            inheritorMetadata.put(entry.getKey(), entry.getValue());
         }
      }

      for (ValueTreeNode node : this) {
         node.propagateInheritedMetadata(inheritorMetadata);
      }
   }

   @NotNull
   @Override
   public Iterator<ValueTreeNode> iterator() {
      return new Iterator<ValueTreeNode>() {
         private final Iterator<Trie.Node> itr = SectionTreeNode.this.node.iterator();

         @Override
         public boolean hasNext() {
            return this.itr.hasNext();
         }

         public ValueTreeNode next() {
            return this.itr.next().getValue();
         }
      };
   }
}
