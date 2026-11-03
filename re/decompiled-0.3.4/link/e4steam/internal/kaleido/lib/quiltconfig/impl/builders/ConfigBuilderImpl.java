package link.e4steam.internal.kaleido.lib.quiltconfig.impl.builders;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Consumer;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.Config;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.Serializer;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.exceptions.ConfigParseException;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.metadata.MetadataType;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.values.TrackedValue;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.values.ValueKey;
import link.e4steam.internal.kaleido.lib.quiltconfig.impl.ConfigImpl;
import link.e4steam.internal.kaleido.lib.quiltconfig.impl.tree.TrackedValueImpl;
import link.e4steam.internal.kaleido.lib.quiltconfig.impl.tree.Trie;
import link.e4steam.internal.kaleido.lib.quiltconfig.impl.util.ConfigsImpl;
import link.e4steam.internal.kaleido.lib.quiltconfig.impl.values.ValueKeyImpl;
import link.e4steam.internal.kaleido.lib.quiltconfig.implementor_api.ConfigEnvironment;

public final class ConfigBuilderImpl implements Config.Builder {
   private final ConfigEnvironment environment;
   private final String familyId;
   private final String id;
   private final Path path;
   private final Map<MetadataType<?, ?>, MetadataType.Builder<?>> metadata = new LinkedHashMap<>();
   private final List<Config.UpdateCallback> callbacks = new ArrayList<>();
   final Trie values = new Trie();
   private String format;

   public ConfigBuilderImpl(ConfigEnvironment environment, String familyId, String id, Path path) {
      this.environment = environment;
      this.familyId = familyId;
      this.id = id;
      this.path = path;
      this.format = environment.getDefaultFormat();
   }

   @Override
   public Config.Builder field(TrackedValue<?> value) {
      this.values.put(value.key(), value);
      return this;
   }

   @Override
   public Config.Builder section(String key, Consumer<Config.SectionBuilder> creator) {
      ValueKey valueKey = new ValueKeyImpl(key);
      SectionBuilderImpl sectionBuilder = new SectionBuilderImpl(valueKey, this);
      creator.accept(sectionBuilder);
      this.values.put(valueKey, sectionBuilder);
      return this;
   }

   @Override
   public <M, B extends MetadataType.Builder<M>> Config.Builder metadata(MetadataType<M, B> type, Consumer<B> builderConsumer) {
      builderConsumer.accept((B)this.metadata.computeIfAbsent(type, t -> type.newBuilder()));
      return this;
   }

   @Override
   public Config.Builder callback(Config.UpdateCallback callback) {
      this.callbacks.add(callback);
      return this;
   }

   @Override
   public Config.Builder format(String format) {
      this.format = format;
      return this;
   }

   public ConfigImpl build() {
      Map<MetadataType<?, ?>, Object> metadata = new LinkedHashMap<>();

      for (Entry<MetadataType<?, ?>, MetadataType.Builder<?>> entry : this.metadata.entrySet()) {
         metadata.put(entry.getKey(), entry.getValue().build());
      }

      ConfigImpl config = new ConfigImpl(this.environment, this.id, this.path, metadata, this.familyId, this.callbacks, this.values, this.format);
      ConfigsImpl.put(this.familyId, config);

      for (TrackedValue<?> value : config.values()) {
         ((TrackedValueImpl)value).setConfig(config);
      }

      doInitialSerialization(config);
      return config;
   }

   public static void doInitialSerialization(ConfigImpl config) {
      ConfigEnvironment environment = config.getEnvironment();
      Serializer defaultSerializer = environment.getActualSerializer(config.getDefaultFileType());
      Serializer serializer = environment.getSerializer(config.getDefaultFileType());
      Path directory = environment.getSaveDir().resolve(config.family()).resolve(config.savePath());
      Path defaultPath = directory.resolve(config.id() + "." + defaultSerializer.getFileExtension());
      Path path = directory.resolve(config.id() + "." + serializer.getFileExtension());

      try {
         Files.createDirectories(path.getParent());
         if ((defaultSerializer == serializer || !Files.exists(defaultPath)) && Files.exists(path)) {
            serializer.deserialize(config, Files.newInputStream(path));
         } else if (Files.exists(defaultPath)) {
            defaultSerializer.deserialize(config, Files.newInputStream(defaultPath));

            try {
               Files.delete(defaultPath);
            } catch (IOException var8) {
               throw new ConfigParseException(var8);
            }
         }

         serializer.serialize(config, Files.newOutputStream(path));
      } catch (IOException var9) {
         throw new ConfigParseException(var9);
      }
   }
}
