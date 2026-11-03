package link.e4steam.internal.kaleido.lib.nightconfig.core.io;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import link.e4steam.internal.kaleido.lib.nightconfig.core.UnmodifiableConfig;

public interface ConfigWriter {
   void write(UnmodifiableConfig var1, Writer var2);

   default void write(UnmodifiableConfig config, OutputStream output, Charset charset) {
      try (Writer writer = new BufferedWriter(new OutputStreamWriter(output, charset))) {
         this.write(config, writer);
      } catch (IOException var17) {
         throw new WritingException("An I/O error occured", var17);
      }
   }

   default void write(UnmodifiableConfig config, OutputStream output) {
      this.write(config, output, StandardCharsets.UTF_8);
   }

   default void write(UnmodifiableConfig config, Path file, WritingMode writingMode) {
      this.write(config, file, writingMode, StandardCharsets.UTF_8);
   }

   default void write(UnmodifiableConfig config, Path file, WritingMode writingMode, Charset charset) {
      if (writingMode == WritingMode.REPLACE_ATOMIC) {
         Path tmp = file.resolveSibling(file.getFileName().toString() + ".new.tmp");

         try (OutputStream output = Files.newOutputStream(tmp, StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            this.write(config, output, charset);
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
         } catch (AtomicMoveNotSupportedException var41) {
            String msg = String.format(
               "Failed to atomically move the config from '%s' to '%s': WritingMode.REPLACE_ATOMIC is not supported for this path, use WritingMode.REPLACE instead.\n%s",
               tmp.toString(),
               file.toString(),
               "Note: you may see *.new.tmp files after this error, they contain the \"new version\" of your configurations and can be safely removed.If you want, you can manually copy their content into your regular configuration files (replacing the old config)."
            );
            throw new WritingException(msg, var41);
         } catch (IOException var42) {
            String msgx = String.format("Failed to write (%s) the config to: %s", writingMode.toString(), file.toString());
            throw new WritingException(msgx, var42);
         }
      } else {
         StandardOpenOption lastOption = writingMode == WritingMode.APPEND ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING;

         try (OutputStream output = Files.newOutputStream(file, StandardOpenOption.WRITE, StandardOpenOption.CREATE, lastOption)) {
            this.write(config, output, charset);
         } catch (IOException var39) {
            String msg = String.format("Failed to write (%s) the config to: %s", writingMode.toString(), file.toString());
            throw new WritingException(msg, var39);
         }
      }
   }

   default void write(UnmodifiableConfig config, File file, WritingMode writingMode) {
      this.write(config, file, writingMode, StandardCharsets.UTF_8);
   }

   default void write(UnmodifiableConfig config, File file, WritingMode writingMode, Charset charset) {
      this.write(config, file.toPath(), writingMode, charset);
   }

   default void write(UnmodifiableConfig config, URL url) {
      URLConnection connection;
      try {
         connection = url.openConnection();
      } catch (IOException var19) {
         throw new WritingException("Unable to connect to the URL", var19);
      }

      String encoding = connection.getContentEncoding();
      Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);

      try (OutputStream output = connection.getOutputStream()) {
         this.write(config, output, charset);
      } catch (IOException var21) {
         throw new WritingException("An I/O error occured", var21);
      }
   }

   default String writeToString(UnmodifiableConfig config) {
      CharsWrapper.Builder builder = new CharsWrapper.Builder(64);
      this.write(config, builder);
      return builder.toString();
   }
}
