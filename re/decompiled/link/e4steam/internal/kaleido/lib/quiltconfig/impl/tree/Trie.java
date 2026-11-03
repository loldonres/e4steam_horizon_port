package link.e4steam.internal.kaleido.lib.quiltconfig.impl.tree;

import java.util.ArrayDeque;
import java.util.ConcurrentModificationException;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.values.TrackedValue;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.values.ValueKey;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.values.ValueTreeNode;
import link.e4steam.internal.kaleido.lib.quiltconfig.impl.builders.SectionBuilderImpl;
import link.e4steam.internal.kaleido.lib.quiltconfig.impl.values.ValueKeyImpl;
import org.jetbrains.annotations.NotNull;

public final class Trie {
   private final Trie.Node root = new Trie.Node(null, null, null);
   private int modCount;

   public Iterable<ValueTreeNode> leaves() {
      return () -> new Trie.LeafItr();
   }

   public Iterable<ValueTreeNode> nodes() {
      return new Iterable<ValueTreeNode>() {
         @NotNull
         @Override
         public Iterator<ValueTreeNode> iterator() {
            return new Iterator<ValueTreeNode>() {
               private final Iterator<Trie.Node> itr = Trie.this.root.iterator();

               @Override
               public boolean hasNext() {
                  return this.itr.hasNext();
               }

               public ValueTreeNode next() {
                  return this.itr.next().getValue();
               }
            };
         }
      };
   }

   public ValueTreeNode put(Iterable<String> key, ValueTreeNode value) {
      int modifiedCount = this.modCount;
      Trie.Node node = this.root;

      for (String keyComponent : key) {
         if (node.value == null) {
            node.setValue(new SectionTreeNode(node, new LinkedHashMap<>(0)));
         }

         node = node.getOrCreateChild(keyComponent);
      }

      node.setValue(value);
      if (modifiedCount == this.modCount) {
         this.modCount++;
      }

      return null;
   }

   public void put(Iterable<String> key, SectionBuilderImpl sectionBuilder) {
      int modifiedCount = this.modCount;
      Trie.Node node = this.root;

      for (String keyComponent : key) {
         node = node.getOrCreateChild(keyComponent);
      }

      sectionBuilder.build(node);
      if (modifiedCount == this.modCount) {
         this.modCount++;
      }
   }

   public TrackedValue<?> get(Iterable<String> key) {
      return (TrackedValue<?>)this.getNode(key);
   }

   public ValueTreeNode getNode(Iterable<String> key) {
      Trie.Node node = this.root;

      for (String k : key) {
         node = node.getOrCreateChild(k);
      }

      return node.getValue();
   }

   private class LeafItr implements Iterator<ValueTreeNode> {
      private final int modCount;
      private final Deque<Iterator<Trie.Node>> iterators;

      private LeafItr() {
         this.modCount = Trie.this.modCount;
         this.iterators = new ArrayDeque<>();
         this.iterators.addFirst(Trie.this.root.children.values().iterator());
      }

      @Override
      public boolean hasNext() {
         this.checkForComodification();

         while (!this.iterators.isEmpty() && !this.iterators.peek().hasNext()) {
            this.iterators.pop();
         }

         return !this.iterators.isEmpty();
      }

      public ValueTreeNode next() {
         if (!this.hasNext()) {
            throw new NoSuchElementException();
         } else {
            this.checkForComodification();
            Iterator<Trie.Node> itr = this.iterators.removeFirst();
            Trie.Node n = itr.next();
            if (itr.hasNext()) {
               this.iterators.addFirst(itr);
            }

            while (n.hasChildren()) {
               itr = n.children.values().iterator();
               n = itr.next();
               this.iterators.addFirst(itr);
            }

            this.checkForComodification();
            return n.getValue();
         }
      }

      private void checkForComodification() {
         if (this.modCount != Trie.this.modCount) {
            throw new ConcurrentModificationException();
         }
      }
   }

   public class Node implements Iterable<Trie.Node> {
      private final Trie.Node parent;
      private final ValueKey key;
      private final Map<String, Trie.Node> children = new LinkedHashMap<>();
      private ValueTreeNode value;

      private Node(Trie.Node parent, ValueKey key, ValueTreeNode value) {
         this.parent = parent;
         this.key = key;
         this.value = value;
      }

      private Node(Trie.Node parent, ValueKey key) {
         this(parent, key, null);
      }

      public boolean hasChildren() {
         return !this.children.isEmpty();
      }

      public ValueKey getKey() {
         return this.key;
      }

      public Trie.Node getParent() {
         return this.parent;
      }

      private Trie.Node getOrCreateChild(String key) {
         if (this.children.containsKey(key)) {
            return this.children.get(key);
         } else {
            ++Trie.this.modCount;
            Trie.Node child = Trie.this.new Node(this, (ValueKey)(this.key == null ? new ValueKeyImpl(key) : this.key.child(key)));
            this.children.put(key, child);
            return child;
         }
      }

      public ValueTreeNode getValue() {
         return this.value;
      }

      @NotNull
      @Override
      public Iterator<Trie.Node> iterator() {
         return this.children.values().iterator();
      }

      public void setValue(ValueTreeNode value) {
         if (this.value != null && !(value instanceof SectionTreeNode)) {
            throw new UnsupportedOperationException("Cannot put node '" + value.key() + "': Node already exists");
         } else {
            this.value = value;
         }
      }
   }
}
