package link.e4steam.internal.kaleido.lib.nightconfig.core;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Supplier;
import link.e4steam.internal.kaleido.lib.nightconfig.core.io.ConfigParser;
import link.e4steam.internal.kaleido.lib.nightconfig.core.io.ConfigWriter;
import link.e4steam.internal.kaleido.lib.nightconfig.core.utils.WriterSupplier;

public interface ConfigFormat<C extends Config> {
   ConfigWriter createWriter();

   ConfigParser<C> createParser();

   default C createConfig() {
      return this.createConfig(Config.getDefaultMapCreator(false));
   }

   default C createConcurrentConfig() {
      return this.createConfig(Config.getDefaultMapCreator(true));
   }

   C createConfig(Supplier<Map<String, Object>> var1);

   boolean supportsComments();

   default boolean supportsType(Class<?> type) {
      return InMemoryFormat.DEFAULT_PREDICATE.test(type);
   }

   default boolean isInMemory() {
      return false;
   }

   default void initEmptyFile(Path f) throws IOException {
      this.initEmptyFile(() -> Files.newBufferedWriter(f));
   }

   default void initEmptyFile(File f) throws IOException {
      this.initEmptyFile(f.toPath());
   }

   default void initEmptyFile(WriterSupplier ws) throws IOException {
      this.initEmptyFile(ws.get());
   }

   default void initEmptyFile(Writer writer) throws IOException {
   }
}
