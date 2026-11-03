package link.e4steam.internal.kaleido.lib.quiltconfig.api;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.metadata.MetadataType;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.values.TrackedValue;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.values.ValueList;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.values.ValueMap;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.values.ValueTreeNode;
import link.e4steam.internal.kaleido.lib.quiltconfig.impl.builders.ValueMapBuilderImpl;
import link.e4steam.internal.kaleido.lib.quiltconfig.impl.tree.TrackedValueImpl;
import link.e4steam.internal.kaleido.lib.quiltconfig.impl.util.ConfigUtils;

public abstract class ReflectiveConfig implements Config {
   private Config wrapped;

   @Override
   public final String family() {
      return this.wrapped.family();
   }

   @Override
   public final String id() {
      return this.wrapped.id();
   }

   @Override
   public final Path savePath() {
      return this.wrapped.savePath();
   }

   @Override
   public final void registerCallback(Config.UpdateCallback callback) {
      this.wrapped.registerCallback(callback);
   }

   @Override
   public final <M> M metadata(MetadataType<M, ?> type) {
      return this.wrapped.metadata(type);
   }

   @Override
   public final <M> boolean hasMetadata(MetadataType<M, ?> type) {
      return this.wrapped.hasMetadata(type);
   }

   @Override
   public Map<MetadataType<?, ?>, Object> metadata() {
      return this.wrapped.metadata();
   }

   @Override
   public final void save() {
      this.wrapped.save();
   }

   @Override
   public final Iterable<TrackedValue<?>> values() {
      return this.wrapped.values();
   }

   @Override
   public final TrackedValue<?> getValue(Iterable<String> key) {
      return this.wrapped.getValue(key);
   }

   @Override
   public final Iterable<ValueTreeNode> nodes() {
      return this.wrapped.nodes();
   }

   @Override
   public final ValueTreeNode getNode(Iterable<String> key) {
      return this.wrapped.getNode(key);
   }

   final void setWrappedConfig(Config config) {
      this.wrapped = config;
   }

   public final <T> TrackedValue<T> value(T defaultValue) {
      ConfigUtils.assertValueType(defaultValue);
      return new TrackedValueImpl<>(null, defaultValue, new LinkedHashMap<>(0), new ArrayList<>(0), new ArrayList<>(0));
   }

   @SafeVarargs
   public final <T> TrackedValue<ValueList<T>> list(T defaultValue, T... values) {
      return this.value(ValueList.create(defaultValue, values));
   }

   public final <T> ValueMap.TrackedBuilder<T> map(T defaultValue) {
      return new ValueMapBuilderImpl.TrackedValueMapBuilderImpl<>(defaultValue, this::value);
   }

   public static class Section {
      public final <T> TrackedValue<T> value(T defaultValue) {
         ConfigUtils.assertValueType(defaultValue);
         return new TrackedValueImpl<>(null, defaultValue, new LinkedHashMap<>(0), new ArrayList<>(0), new ArrayList<>(0));
      }

      @SafeVarargs
      public final <T> TrackedValue<ValueList<T>> list(T defaultValue, T... values) {
         return this.value(ValueList.create(defaultValue, values));
      }

      public final <T> ValueMap.TrackedBuilder<T> map(T defaultValue) {
         return new ValueMapBuilderImpl.TrackedValueMapBuilderImpl<>(defaultValue, this::value);
      }
   }
}
