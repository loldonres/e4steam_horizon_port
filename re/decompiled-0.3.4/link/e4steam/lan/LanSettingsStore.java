package link.e4steam.lan;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import link.e4steam.Agnos;

public final class LanSettingsStore {
   private static final String FILE_NAME = "e4steam-lan.properties";

   private LanSettingsStore() {
   }

   public static LanSettings.Snapshot load() {
      Path file = Agnos.configDir().resolve("e4steam-lan.properties");
      if (!Files.isRegularFile(file)) {
         return LanSettings.defaults();
      } else {
         Properties values = new Properties();

         try {
            LanSettings.Snapshot var3;
            try (InputStream input = Files.newInputStream(file)) {
               values.load(input);
               var3 = new LanSettings.Snapshot(
                     bool(values, "onlineMode", true),
                     bool(values, "pvp", true),
                     integer(values, "maxPlayers", 8),
                     integer(values, "port", 25565),
                     values.getProperty("gameMode", "survival"),
                     bool(values, "allowCheats", false)
                  )
                  .validated();
            }

            return var3;
         } catch (RuntimeException | IOException var7) {
            return LanSettings.defaults();
         }
      }
   }

   public static synchronized void save(LanSettings.Snapshot settings) {
      LanSettings.Snapshot value = settings.validated();
      Path directory = Agnos.configDir();
      Path file = directory.resolve("e4steam-lan.properties");
      Path temporary = directory.resolve("e4steam-lan.properties.tmp");
      Properties properties = new Properties();
      properties.setProperty("onlineMode", Boolean.toString(value.onlineMode()));
      properties.setProperty("pvp", Boolean.toString(value.pvp()));
      properties.setProperty("maxPlayers", Integer.toString(value.maxPlayers()));
      properties.setProperty("port", Integer.toString(value.port()));
      properties.setProperty("gameMode", value.gameMode());
      properties.setProperty("allowCheats", Boolean.toString(value.allowCheats()));

      try {
         Files.createDirectories(directory);

         try (OutputStream output = Files.newOutputStream(temporary)) {
            properties.store(output, "e4steam integrated LAN settings");
         }

         try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
         } catch (IOException var10) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
         }
      } catch (IOException var12) {
         throw new IllegalStateException("Could not save e4steam LAN settings", var12);
      }
   }

   public static void update(LanSettings.Snapshot settings) {
      LanSettings.install(settings);
      save(settings);
   }

   private static boolean bool(Properties values, String key, boolean fallback) {
      String raw = values.getProperty(key);
      return raw == null ? fallback : Boolean.parseBoolean(raw.trim());
   }

   private static int integer(Properties values, String key, int fallback) {
      String raw = values.getProperty(key);
      if (raw == null) {
         return fallback;
      } else {
         try {
            return Integer.parseInt(raw.trim());
         } catch (NumberFormatException var5) {
            return fallback;
         }
      }
   }
}
