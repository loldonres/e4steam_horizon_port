package link.e4steam.internal.kaleido.lib.quiltconfig.api;

import java.nio.file.Path;

@Deprecated
public final class ConfigEnvironment extends link.e4steam.internal.kaleido.lib.quiltconfig.implementor_api.ConfigEnvironment {
   public ConfigEnvironment(Path saveFolder, String globalSerializer, Serializer defaultSerializer, Serializer... serializers) {
      super(saveFolder, globalSerializer, defaultSerializer, serializers);
   }

   public ConfigEnvironment(Path saveFolder, Serializer defaultSerializer, Serializer... serializers) {
      this(saveFolder, null, defaultSerializer, serializers);
   }

   @Override
   public Path getSaveDir() {
      return super.getSaveDir();
   }

   @Override
   public String getDefaultFormat() {
      return super.getDefaultFormat();
   }

   @Override
   public String getGlobalFormat() {
      return super.getGlobalFormat();
   }

   @Override
   public Serializer registerSerializer(Serializer serializer) {
      return super.registerSerializer(serializer);
   }

   @Override
   public Serializer getActualSerializer(String fileType) {
      return super.getActualSerializer(fileType);
   }

   @Override
   public Serializer getSerializer(String fileType) {
      return super.getSerializer(fileType);
   }
}
