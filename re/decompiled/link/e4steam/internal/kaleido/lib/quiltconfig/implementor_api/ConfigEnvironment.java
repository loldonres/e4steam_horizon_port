package link.e4steam.internal.kaleido.lib.quiltconfig.implementor_api;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import link.e4steam.internal.kaleido.lib.quiltconfig.api.Serializer;

public class ConfigEnvironment {
   private final Map<String, Serializer> serializers = new HashMap<>();
   private final Path saveFolder;
   private final String defaultFileFormat;
   private final String globalSerializer;

   public ConfigEnvironment(Path saveFolder, String globalSerializer, Serializer defaultSerializer, Serializer... serializers) {
      this.serializers.put(defaultSerializer.getFileExtension(), defaultSerializer);
      this.defaultFileFormat = defaultSerializer.getFileExtension();

      for (Serializer serializer : serializers) {
         this.serializers.put(serializer.getFileExtension(), serializer);
      }

      this.saveFolder = saveFolder;
      this.globalSerializer = globalSerializer;
   }

   public ConfigEnvironment(Path saveFolder, Serializer defaultSerializer, Serializer... serializers) {
      this(saveFolder, null, defaultSerializer, serializers);
   }

   public Path getSaveDir() {
      return this.saveFolder;
   }

   public String getDefaultFormat() {
      return this.defaultFileFormat;
   }

   public String getGlobalFormat() {
      return this.globalSerializer;
   }

   public Serializer registerSerializer(Serializer serializer) {
      return this.serializers.put(serializer.getFileExtension(), serializer);
   }

   public Serializer getActualSerializer(String fileType) {
      if (this.serializers.containsKey(fileType)) {
         return this.serializers.get(fileType);
      } else {
         throw new RuntimeException("No serializer registered for extension '." + fileType + "'");
      }
   }

   public Serializer getSerializer(String fileType) {
      return this.getActualSerializer(this.globalSerializer == null ? fileType : this.globalSerializer);
   }
}
