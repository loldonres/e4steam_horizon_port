package link.e4steam.internal.kaleido.lib.nightconfig.core.utils;

import java.util.AbstractMap;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

public final class TransformingMap<K, InternalV, ExternalV> extends AbstractMap<K, ExternalV> {
   private final Function<? super InternalV, ? extends ExternalV> readTransformation;
   private final Function<? super ExternalV, ? extends InternalV> writeTransformation;
   private final Function<Object, Object> searchTransformation;
   private final Map<K, InternalV> internalMap;

   public TransformingMap(
      Map<K, InternalV> map,
      Function<? super InternalV, ? extends ExternalV> readTransformation,
      Function<? super ExternalV, ? extends InternalV> writeTransformation,
      Function<Object, Object> searchTransformation
   ) {
      this.internalMap = map;
      this.readTransformation = readTransformation;
      this.writeTransformation = writeTransformation;
      this.searchTransformation = searchTransformation;
   }

   @Override
   public int size() {
      return this.internalMap.size();
   }

   @Override
   public boolean isEmpty() {
      return this.internalMap.isEmpty();
   }

   @Override
   public boolean containsKey(Object key) {
      return this.internalMap.containsKey(key);
   }

   @Override
   public boolean containsValue(Object value) {
      return this.internalMap.containsValue(this.searchTransformation.apply(value));
   }

   @Override
   public ExternalV get(Object key) {
      return (ExternalV)this.readTransformation.apply(this.internalMap.get(key));
   }

   @Override
   public ExternalV put(K key, ExternalV value) {
      return (ExternalV)this.readTransformation.apply(this.internalMap.put(key, (InternalV)this.writeTransformation.apply(value)));
   }

   @Override
   public ExternalV remove(Object key) {
      return (ExternalV)this.readTransformation.apply(this.internalMap.remove(key));
   }

   @Override
   public void putAll(Map<? extends K, ? extends ExternalV> m) {
      this.internalMap.putAll(new TransformingMap<>(m, this.writeTransformation, o -> (ExternalV)o, o -> o));
   }

   @Override
   public void clear() {
      this.internalMap.clear();
   }

   @Override
   public Set<K> keySet() {
      return this.internalMap.keySet();
   }

   @Override
   public Collection<ExternalV> values() {
      return new TransformingCollection<>(this.internalMap.values(), this.readTransformation, this.writeTransformation, this.searchTransformation);
   }

   @Override
   public Set<Entry<K, ExternalV>> entrySet() {
      Function<Entry<K, InternalV>, Entry<K, ExternalV>> internalToExternal = internalEntry -> new TransformingMapEntry<>(
         internalEntry, this.readTransformation, this.writeTransformation
      );
      Function<Entry<K, ExternalV>, Entry<K, InternalV>> externalToInternal = externalEntry -> new TransformingMapEntry<>(
         externalEntry, this.writeTransformation, this.readTransformation
      );
      Function<Object, Object> searchTranformation = o -> {
         if (o instanceof Entry) {
            Entry<K, InternalV> entry = (Entry<K, InternalV>)o;
            return new TransformingMapEntry<>(entry, this.readTransformation, this.writeTransformation);
         } else {
            return o;
         }
      };
      return new TransformingSet<>(this.internalMap.entrySet(), internalToExternal, externalToInternal, searchTranformation);
   }

   @Override
   public ExternalV getOrDefault(Object key, ExternalV defaultValue) {
      InternalV result = this.internalMap.get(key);
      return result == defaultValue ? defaultValue : this.readTransformation.apply(result);
   }

   @Override
   public void forEach(BiConsumer<? super K, ? super ExternalV> action) {
      this.internalMap.forEach((k, o) -> action.accept(k, (ExternalV)this.readTransformation.apply(o)));
   }

   @Override
   public void replaceAll(BiFunction<? super K, ? super ExternalV, ? extends ExternalV> function) {
      this.internalMap.replaceAll(this.transform(function));
   }

   @Override
   public ExternalV putIfAbsent(K key, ExternalV value) {
      return (ExternalV)this.readTransformation.apply(this.internalMap.putIfAbsent(key, (InternalV)this.writeTransformation.apply(value)));
   }

   @Override
   public boolean remove(Object key, Object value) {
      return this.internalMap.remove(key, this.searchTransformation.apply(value));
   }

   @Override
   public boolean replace(K key, ExternalV oldValue, ExternalV newValue) {
      return this.internalMap.replace(key, (InternalV)this.writeTransformation.apply(oldValue), (InternalV)this.writeTransformation.apply(newValue));
   }

   @Override
   public ExternalV replace(K key, ExternalV value) {
      return (ExternalV)this.readTransformation.apply(this.internalMap.replace(key, (InternalV)this.writeTransformation.apply(value)));
   }

   @Override
   public ExternalV computeIfAbsent(K key, Function<? super K, ? extends ExternalV> mappingFunction) {
      Function<K, InternalV> function = k -> (InternalV)this.writeTransformation.apply((ExternalV)mappingFunction.apply(k));
      return (ExternalV)this.readTransformation.apply(this.internalMap.computeIfAbsent(key, function));
   }

   @Override
   public ExternalV computeIfPresent(K key, BiFunction<? super K, ? super ExternalV, ? extends ExternalV> remappingFunction) {
      return (ExternalV)this.readTransformation.apply(this.internalMap.computeIfPresent(key, this.transform(remappingFunction)));
   }

   @Override
   public ExternalV compute(K key, BiFunction<? super K, ? super ExternalV, ? extends ExternalV> remappingFunction) {
      return (ExternalV)this.readTransformation.apply(this.internalMap.compute(key, this.transform(remappingFunction)));
   }

   @Override
   public ExternalV merge(K key, ExternalV value, BiFunction<? super ExternalV, ? super ExternalV, ? extends ExternalV> remappingFunction) {
      return (ExternalV)this.readTransformation
         .apply(this.internalMap.merge(key, (InternalV)this.writeTransformation.apply(value), this.transform2(remappingFunction)));
   }

   private BiFunction<K, InternalV, InternalV> transform(BiFunction<? super K, ? super ExternalV, ? extends ExternalV> remappingFunction) {
      return (k, internalV) -> (InternalV)this.writeTransformation
         .apply((ExternalV)remappingFunction.apply(k, (ExternalV)this.readTransformation.apply(internalV)));
   }

   private BiFunction<InternalV, InternalV, InternalV> transform2(BiFunction<? super ExternalV, ? super ExternalV, ? extends ExternalV> remappingFunction) {
      return (internalV1, internalV2) -> (InternalV)this.writeTransformation
         .apply((ExternalV)remappingFunction.apply((ExternalV)this.readTransformation.apply(internalV1), (ExternalV)this.readTransformation.apply(internalV2)));
   }
}
