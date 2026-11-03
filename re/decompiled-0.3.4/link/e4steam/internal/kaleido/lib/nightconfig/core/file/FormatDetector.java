package link.e4steam.internal.kaleido.lib.nightconfig.core.file;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import link.e4steam.internal.kaleido.lib.nightconfig.core.ConfigFormat;
import link.e4steam.internal.kaleido.lib.nightconfig.core.utils.StringUtils;

public final class FormatDetector {
   private static final Map<String, Supplier<ConfigFormat<?>>> registry = new ConcurrentHashMap<>();

   public static void registerExtension(String fileExtension, ConfigFormat<?> format) {
      registry.put(fileExtension, () -> format);
   }

   public static void registerExtension(String fileExtension, Supplier<ConfigFormat<?>> formatSupplier) {
      registry.put(fileExtension, formatSupplier);
   }

   public static ConfigFormat<?> detect(File file) {
      return detectByName(file.getName());
   }

   public static ConfigFormat<?> detect(Path file) {
      return detectByName(file.getFileName().toString());
   }

   public static ConfigFormat<?> detectByName(String fileName) {
      List<String> splitted = StringUtils.split(fileName, '.');
      String fileExtension = splitted.get(splitted.size() - 1);
      Supplier<ConfigFormat<?>> supplier = registry.get(fileExtension);
      return supplier == null ? null : supplier.get();
   }

   private static void tryLoad(String className) {
      try {
         Class.forName(className);
      } catch (ClassNotFoundException var2) {
      }
   }

   private FormatDetector() {
   }

   static {
      tryLoad("link.e4steam.internal.kaleido.lib.nightconfig.toml.TomlFormat");
      tryLoad("link.e4steam.internal.kaleido.lib.nightconfig.hocon.HoconFormat");
      tryLoad("link.e4steam.internal.kaleido.lib.nightconfig.json.JsonFormat");
      tryLoad("link.e4steam.internal.kaleido.lib.nightconfig.yaml.YamlFormat");
   }
}
