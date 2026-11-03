package link.e4steam.internal.kaleido.lib.nightconfig.core.file;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import link.e4steam.internal.kaleido.lib.nightconfig.core.ConfigFormat;

@FunctionalInterface
public interface FileNotFoundAction {
   FileNotFoundAction CREATE_EMPTY = (f, c) -> {
      Files.createFile(f);
      c.initEmptyFile(f);
      return false;
   };
   FileNotFoundAction READ_NOTHING = (f, c) -> false;
   FileNotFoundAction THROW_ERROR = (f, c) -> {
      throw new NoSuchFileException(f.toAbsolutePath().toString());
   };

   boolean run(Path var1, ConfigFormat<?> var2) throws IOException;

   static FileNotFoundAction copyData(URL url) {
      return (f, c) -> {
         Files.copy(url.openStream(), f);
         return true;
      };
   }

   static FileNotFoundAction copyData(File file) {
      return (f, c) -> {
         Files.copy(new FileInputStream(file), f);
         return true;
      };
   }

   static FileNotFoundAction copyData(Path file) {
      return (f, c) -> {
         Files.copy(file, f);
         return true;
      };
   }

   static FileNotFoundAction copyData(InputStream data) {
      return (f, c) -> {
         Files.copy(data, f);
         return true;
      };
   }

   static FileNotFoundAction copyResource(String resourcePath) {
      return copyData(FileNotFoundAction.class.getResource(resourcePath));
   }
}
