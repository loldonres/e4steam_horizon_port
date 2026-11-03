package link.e4steam.internal.kaleido.lib.quiltconfig.api;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Consumer;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.metadata.MetadataContainer;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.metadata.MetadataContainerBuilder;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.metadata.MetadataType;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.values.TrackedValue;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.values.ValueTreeNode;
import link.e4steam.internal.kaleido.lib.quiltconfig.impl.ConfigImpl;
import org.jetbrains.annotations.ApiStatus.NonExtendable;

@NonExtendable
public interface Config extends MetadataContainer {
   String family();

   String id();

   Path savePath();

   void registerCallback(Config.UpdateCallback var1);

   @Override
   <M> M metadata(MetadataType<M, ?> var1);

   @Override
   <M> boolean hasMetadata(MetadataType<M, ?> var1);

   void save();

   Iterable<TrackedValue<?>> values();

   TrackedValue<?> getValue(Iterable<String> var1);

   Iterable<ValueTreeNode> nodes();

   ValueTreeNode getNode(Iterable<String> var1);

   @Deprecated
   static Config create(ConfigEnvironment environment, String family, String id, Path path, Config.Creator... creators) {
      return ConfigImpl.create(environment, family, id, path, creators);
   }

   @Deprecated
   static Config create(ConfigEnvironment environment, String family, String id, Config.Creator... creators) {
      return create(environment, family, id, Paths.get(""), creators);
   }

   @Deprecated
   static <C extends WrappedConfig> C create(
      ConfigEnvironment environment, String family, String id, Path path, Config.Creator before, Class<C> configCreatorClass, Config.Creator after
   ) {
      return ConfigImpl.create(environment, family, id, path, before, configCreatorClass, after);
   }

   @Deprecated
   static <C extends WrappedConfig> C create(
      ConfigEnvironment environment, String family, String id, Path path, Config.Creator before, Class<C> configCreatorClass
   ) {
      return create(environment, family, id, path, before, configCreatorClass, builder -> {});
   }

   @Deprecated
   static <C extends WrappedConfig> C create(
      ConfigEnvironment environment, String family, String id, Path path, Class<C> configCreatorClass, Config.Creator after
   ) {
      return create(environment, family, id, path, builder -> {}, configCreatorClass, after);
   }

   @Deprecated
   static <C extends WrappedConfig> C create(ConfigEnvironment environment, String family, String id, Path path, Class<C> configCreatorClass) {
      return create(environment, family, id, path, builder -> {}, configCreatorClass, builder -> {});
   }

   @Deprecated
   static <C extends WrappedConfig> C create(
      ConfigEnvironment environment, String family, String id, Config.Creator before, Class<C> configCreatorClass, Config.Creator after
   ) {
      return create(environment, family, id, Paths.get(""), before, configCreatorClass, after);
   }

   @Deprecated
   static <C extends WrappedConfig> C create(ConfigEnvironment environment, String family, String id, Config.Creator before, Class<C> configCreatorClass) {
      return create(environment, family, id, Paths.get(""), before, configCreatorClass, builder -> {});
   }

   @Deprecated
   static <C extends WrappedConfig> C create(ConfigEnvironment environment, String family, String id, Class<C> configCreatorClass, Config.Creator after) {
      return create(environment, family, id, Paths.get(""), builder -> {}, configCreatorClass, after);
   }

   @Deprecated
   static <C extends WrappedConfig> C create(ConfigEnvironment environment, String family, String id, Class<C> configCreatorClass) {
      return create(environment, family, id, Paths.get(""), builder -> {}, configCreatorClass, builder -> {});
   }

   @NonExtendable
   public interface Builder extends Config.SectionBuilder {
      Config.Builder field(TrackedValue<?> var1);

      Config.Builder section(String var1, Consumer<Config.SectionBuilder> var2);

      <M, B extends MetadataType.Builder<M>> Config.Builder metadata(MetadataType<M, B> var1, Consumer<B> var2);

      Config.Builder callback(Config.UpdateCallback var1);

      Config.Builder format(String var1);
   }

   public interface Creator {
      void create(Config.Builder var1);
   }

   @Deprecated
   public interface Section {
   }

   @NonExtendable
   public interface SectionBuilder extends MetadataContainerBuilder<Config.SectionBuilder> {
      Config.SectionBuilder field(TrackedValue<?> var1);

      Config.SectionBuilder section(String var1, Consumer<Config.SectionBuilder> var2);

      <M, B extends MetadataType.Builder<M>> Config.SectionBuilder metadata(MetadataType<M, B> var1, Consumer<B> var2);
   }

   public interface UpdateCallback {
      void onUpdate(Config var1);
   }
}
