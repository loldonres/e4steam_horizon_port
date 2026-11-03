package link.e4steam.internal.kaleido.lib.nightconfig.core.io;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import link.e4steam.internal.kaleido.lib.nightconfig.core.Config;
import link.e4steam.internal.kaleido.lib.nightconfig.core.ConfigFormat;
import link.e4steam.internal.kaleido.lib.nightconfig.core.file.FileNotFoundAction;
import link.e4steam.internal.kaleido.lib.nightconfig.core.utils.FastStringReader;

public interface ConfigParser<C extends Config> {
   ConfigFormat<C> getFormat();

   C parse(Reader var1);

   void parse(Reader var1, Config var2, ParsingMode var3);

   default C parse(String input) {
      return this.parse(new FastStringReader(input));
   }

   default void parse(String input, Config destination, ParsingMode parsingMode) {
      this.parse(new StringReader(input), destination, parsingMode);
   }

   default C parse(InputStream input) {
      return this.parse(input, StandardCharsets.UTF_8);
   }

   default C parse(InputStream input, Charset charset) {
      CharsetDecoder decoder = charset.newDecoder();
      decoder.onMalformedInput(CodingErrorAction.REPORT);
      decoder.onUnmappableCharacter(CodingErrorAction.REPORT);
      return this.parse(new BufferedReader(new InputStreamReader(input, decoder)));
   }

   default void parse(InputStream input, Config destination, ParsingMode parsingMode) {
      this.parse(input, destination, parsingMode, StandardCharsets.UTF_8);
   }

   default void parse(InputStream input, Config destination, ParsingMode parsingMode, Charset charset) {
      CharsetDecoder decoder = charset.newDecoder();
      decoder.onMalformedInput(CodingErrorAction.REPORT);
      decoder.onUnmappableCharacter(CodingErrorAction.REPORT);
      Reader reader = new BufferedReader(new InputStreamReader(input, decoder));
      this.parse(reader, destination, parsingMode);
   }

   default C parse(File file, FileNotFoundAction notFoundAction) {
      return this.parse(file, notFoundAction, StandardCharsets.UTF_8);
   }

   default C parse(File file, FileNotFoundAction notFoundAction, Charset charset) {
      return this.parse(file.toPath(), notFoundAction, charset);
   }

   default void parse(File file, Config destination, ParsingMode parsingMode, FileNotFoundAction notFoundAction) {
      this.parse(file, destination, parsingMode, notFoundAction, StandardCharsets.UTF_8);
   }

   default void parse(File file, Config destination, ParsingMode parsingMode, FileNotFoundAction notFoundAction, Charset charset) {
      this.parse(file.toPath(), destination, parsingMode, notFoundAction, charset);
   }

   default C parse(Path file, FileNotFoundAction notFoundAction) {
      return this.parse(file, notFoundAction, StandardCharsets.UTF_8);
   }

   default C parse(Path file, FileNotFoundAction notFoundAction, Charset charset) {
      try {
         if (Files.notExists(file) && !notFoundAction.run(file, this.getFormat())) {
            return this.getFormat().createConfig();
         } else {
            Config var6;
            try (InputStream input = Files.newInputStream(file)) {
               var6 = this.parse(input, charset);
            }

            return (C)var6;
         }
      } catch (IOException var18) {
         throw new WritingException("An I/O error occured", var18);
      }
   }

   default void parse(Path file, Config destination, ParsingMode parsingMode, FileNotFoundAction notFoundAction) {
      this.parse(file, destination, parsingMode, notFoundAction, StandardCharsets.UTF_8);
   }

   default void parse(Path file, Config destination, ParsingMode parsingMode, FileNotFoundAction notFoundAction, Charset charset) {
      try {
         if (!Files.notExists(file) || notFoundAction.run(file, this.getFormat())) {
            try (InputStream input = Files.newInputStream(file)) {
               this.parse(input, destination, parsingMode, charset);
            }
         }
      } catch (IOException var19) {
         throw new WritingException("An I/O error occured", var19);
      }
   }

   default C parse(URL url) {
      URLConnection connection;
      try {
         connection = url.openConnection();
      } catch (IOException var19) {
         throw new WritingException("Unable to connect to the URL", var19);
      }

      String encoding = connection.getContentEncoding();
      Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);

      try (Reader reader = new BufferedReader(new InputStreamReader(url.openStream(), charset))) {
         return this.parse(reader);
      } catch (IOException var21) {
         throw new WritingException("An I/O error occured", var21);
      }
   }

   default void parse(URL url, Config destination, ParsingMode parsingMode) {
      URLConnection connection;
      try {
         connection = url.openConnection();
      } catch (IOException var20) {
         throw new WritingException("Unable to connect to the URL", var20);
      }

      String encoding = connection.getContentEncoding();
      Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);

      try (Reader reader = new BufferedReader(new InputStreamReader(url.openStream(), charset))) {
         this.parse(reader, destination, parsingMode);
      } catch (IOException var22) {
         throw new WritingException("An I/O error occured", var22);
      }
   }
}
