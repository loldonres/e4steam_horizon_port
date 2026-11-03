package link.e4steam.internal.kaleido.lib.quiltconfig.impl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.Config;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.InternalsHelper;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.ReflectiveConfig;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.WrappedConfig;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.metadata.MetadataType;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.values.TrackedValue;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.values.ValueTreeNode;
import link.e4steam.internal.kaleido.lib.quiltconfig.impl.builders.ConfigBuilderImpl;
import link.e4steam.internal.kaleido.lib.quiltconfig.impl.builders.ReflectiveConfigCreator;
import link.e4steam.internal.kaleido.lib.quiltconfig.impl.builders.WrappedConfigCreator;
import link.e4steam.internal.kaleido.lib.quiltconfig.impl.tree.Trie;
import link.e4steam.internal.kaleido.lib.quiltconfig.impl.util.ImmutableIterable;
import link.e4steam.internal.kaleido.lib.quiltconfig.implementor_api.ConfigEnvironment;
import org.jetbrains.annotations.NotNull;

public final class ConfigImpl extends AbstractMetadataContainer implements Config {
   private final ConfigEnvironment environment;
   private final String family;
   private final String id;
   private final Path path;
   private final List<Config.UpdateCallback> callbacks;
   private final Trie values;
   private final String defaultFileType;

   public ConfigImpl(
      ConfigEnvironment environment,
      String id,
      Path path,
      Map<MetadataType<?, ?>, Object> metadata,
      String family,
      List<Config.UpdateCallback> callbacks,
      Trie values,
      String defaultFileType
   ) {
      super(metadata);
      this.environment = environment;
      this.family = family;
      this.id = id;
      this.path = path;
      this.callbacks = callbacks;
      this.values = values;
      Map<MetadataType<?, ?>, Object> inheritedMetadata = new LinkedHashMap<>();

      for (Entry<MetadataType<?, ?>, Object> entry : metadata.entrySet()) {
         if (entry.getKey().isInherited()) {
            inheritedMetadata.put(entry.getKey(), entry.getValue());
         }
      }

      for (ValueTreeNode node : this.values.nodes()) {
         node.propagateInheritedMetadata(inheritedMetadata);
      }

      this.defaultFileType = defaultFileType;
   }

   @Override
   public String family() {
      return this.family;
   }

   @Override
   public String id() {
      return this.id;
   }

   @Override
   public Path savePath() {
      return this.path;
   }

   @Override
   public void registerCallback(Config.UpdateCallback callback) {
      this.callbacks.add(callback);
   }

   public String getDefaultFileType() {
      return this.defaultFileType;
   }

   public ConfigEnvironment getEnvironment() {
      return this.environment;
   }

   private Path getPath() {
      return this.environment
         .getSaveDir()
         .resolve(this.family)
         .resolve(this.path)
         .resolve(this.id + "." + this.environment.getSerializer(this.defaultFileType).getFileExtension());
   }

   @Override
   public void save() {
      Path path = this.getPath();

      try {
         Files.createDirectories(path.getParent());
         this.environment.getSerializer(this.defaultFileType).serialize(this, Files.newOutputStream(path));
      } catch (IOException var3) {
         var3.printStackTrace();
      }
   }

   public void invokeCallbacks() {
      for (Config.UpdateCallback callback : this.callbacks) {
         callback.onUpdate(this);
      }
   }

   @Override
   public Iterable<TrackedValue<?>> values() {
      return new Iterable<TrackedValue<?>>() {
         @NotNull
         @Override
         public Iterator<TrackedValue<?>> iterator() {
            return new Iterator<TrackedValue<?>>() {
               private final Iterator<ValueTreeNode> itr = ConfigImpl.this.values.leaves().iterator();
               private ValueTreeNode next;

               @Override
               public boolean hasNext() {
                  while (this.itr.hasNext() && !(this.next instanceof TrackedValue)) {
                     this.next = this.itr.next();
                  }

                  return this.next != null;
               }

               public TrackedValue<?> next() {
                  TrackedValue<?> value = (TrackedValue<?>)this.next;
                  this.next = null;
                  return value;
               }
            };
         }
      };
   }

   @Override
   public TrackedValue<?> getValue(Iterable<String> key) {
      return this.values.get(key);
   }

   @Override
   public Iterable<ValueTreeNode> nodes() {
      return new ImmutableIterable<>(this.values.nodes());
   }

   @Override
   public ValueTreeNode getNode(Iterable<String> key) {
      return this.values.getNode(key);
   }

   public static Config create(ConfigEnvironment environment, String familyId, String id, Config.Creator... creators) {
      return create(environment, familyId, id, Paths.get(""), creators);
   }

   public static Config create(ConfigEnvironment environment, String familyId, String id, Path path, Config.Creator... creators) {
      ConfigBuilderImpl builder = new ConfigBuilderImpl(environment, familyId, id, path);

      for (Config.Creator creator : creators) {
         creator.create(builder);
      }

      return builder.build();
   }

   @Deprecated
   public static <C extends WrappedConfig> C create(
      ConfigEnvironment environment, String familyId, String id, Path path, Config.Creator before, Class<C> configCreatorClass, Config.Creator after
   ) {
      WrappedConfigCreator<C> creator = WrappedConfigCreator.of(configCreatorClass);
      Config config = create(environment, familyId, id, path, before, creator, after);
      C c = (C)creator.getInstance();
      c.setWrappedConfig(config);
      return c;
   }

   public static <C extends ReflectiveConfig> C createReflective(
      ConfigEnvironment environment, String familyId, String id, Path path, Config.Creator before, Class<C> configCreatorClass, Config.Creator after
   ) {
      ReflectiveConfigCreator<C> creator = ReflectiveConfigCreator.of(configCreatorClass);
      Config config = create(environment, familyId, id, path, before, creator, after);
      C c = (C)creator.getInstance();
      InternalsHelper.setWrappedConfig(c, config);
      return c;
   }
}
