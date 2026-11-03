package link.e4steam.internal.dedicated;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import link.e4steam.api.dedicated.DedicatedServerService;
import link.e4steam.steam.SteamRuntimeBackend;

public final class DedicatedRuntimeConfig {
   private static final int APP_ID = 480;
   private final boolean enabled;
   private final DedicatedServerService.DedicatedAccessMode accessMode;
   private final int maxPeers;
   private final int queryPort;
   private final String serverName;
   private final Set<Long> whitelist;

   public DedicatedRuntimeConfig(boolean enabled, DedicatedServerService.DedicatedAccessMode accessMode, int maxPeers, int queryPort, String serverName) {
      this(enabled, accessMode, maxPeers, queryPort, serverName, Collections.emptySet());
   }

   public DedicatedRuntimeConfig(
      boolean enabled, DedicatedServerService.DedicatedAccessMode accessMode, int maxPeers, int queryPort, String serverName, Set<Long> whitelist
   ) {
      this.enabled = enabled;
      this.accessMode = Objects.requireNonNull(accessMode, "accessMode");
      if (accessMode == DedicatedServerService.DedicatedAccessMode.CUSTOM) {
         throw new IllegalArgumentException("Custom access requires an installed provider");
      } else if (maxPeers < 1 || maxPeers > 64) {
         throw new IllegalArgumentException("maxPeers");
      } else if (queryPort >= 0 && queryPort <= 65535) {
         String name = Objects.requireNonNull(serverName, "serverName").trim();
         if (!name.isEmpty() && name.length() <= 64) {
            for (int index = 0; index < name.length(); index++) {
               if (Character.isISOControl(name.charAt(index))) {
                  throw new IllegalArgumentException("serverName");
               }
            }

            this.maxPeers = maxPeers;
            this.queryPort = queryPort;
            this.serverName = name;
            LinkedHashSet<Long> checkedWhitelist = new LinkedHashSet<>();
            if (whitelist != null) {
               if (whitelist.size() > 1024) {
                  throw new IllegalArgumentException("whitelist");
               }

               for (Long value : whitelist) {
                  if (value == null || value == 0L) {
                     throw new IllegalArgumentException("whitelist");
                  }

                  checkedWhitelist.add(value);
               }
            }

            this.whitelist = Collections.unmodifiableSet(checkedWhitelist);
         } else {
            throw new IllegalArgumentException("serverName");
         }
      } else {
         throw new IllegalArgumentException("queryPort");
      }
   }

   public static DedicatedRuntimeConfig fromEnvironment(Map<String, String> environment, Properties properties) {
      Map<String, String> env = environment == null ? Collections.emptyMap() : environment;
      Properties props = properties == null ? new Properties() : properties;
      boolean enabled = bool(first(props.getProperty("e4steam.dedicated.enabled"), env.get("E4STEAM_DEDICATED_ENABLED")), false);
      String modeText = first(props.getProperty("e4steam.dedicated.access"), env.get("E4STEAM_DEDICATED_ACCESS"));
      DedicatedServerService.DedicatedAccessMode mode = modeText == null
         ? DedicatedServerService.DedicatedAccessMode.PRIVATE
         : DedicatedServerService.DedicatedAccessMode.valueOf(modeText.trim().toUpperCase(Locale.ROOT));
      int maxPeers = number(first(props.getProperty("e4steam.dedicated.maxPeers"), env.get("E4STEAM_DEDICATED_MAX_PEERS")), 8, 1, 64);
      int queryPort = number(first(props.getProperty("e4steam.dedicated.queryPort"), env.get("E4STEAM_DEDICATED_QUERY_PORT")), 65535, 0, 65535);
      String name = first(props.getProperty("e4steam.dedicated.name"), env.get("E4STEAM_DEDICATED_NAME"));
      Set<Long> whitelist = parseSteamIds(first(props.getProperty("e4steam.dedicated.whitelist"), env.get("E4STEAM_DEDICATED_WHITELIST")));
      return new DedicatedRuntimeConfig(enabled, mode, maxPeers, queryPort, name == null ? "e4steam Minecraft server" : name, whitelist);
   }

   public static DedicatedRuntimeConfig load(Path configFile, Map<String, String> environment, Properties systemProperties) throws IOException {
      Properties merged = DedicatedConfigFile.load(configFile);
      if (systemProperties != null) {
         for (String name : systemProperties.stringPropertyNames()) {
            if (name.startsWith("e4steam.dedicated.")) {
               merged.setProperty(name, systemProperties.getProperty(name));
            }
         }
      }

      return fromEnvironment(environment, merged);
   }

   public boolean enabled() {
      return this.enabled;
   }

   public DedicatedServerService.DedicatedAccessMode accessMode() {
      return this.accessMode;
   }

   public int maxPeers() {
      return this.maxPeers;
   }

   public int queryPort() {
      return this.queryPort;
   }

   public String serverName() {
      return this.serverName;
   }

   public boolean isWhitelisted(long steamId) {
      return this.whitelist.contains(steamId);
   }

   public SteamRuntimeBackend.Config backend(int minecraftPort) {
      return new SteamRuntimeBackend.Config(480, minecraftPort, this.queryPort, this.maxPeers, this.serverName, null);
   }

   public DedicatedServerService.DedicatedConfigSnapshot safeSnapshot() {
      return new DedicatedServerService.DedicatedConfigSnapshot(1, this.accessMode, this.maxPeers, false, "ANONYMOUS");
   }

   public void validateMinecraftBind(InetAddress bindAddress) {
      if (bindAddress == null || !bindAddress.isLoopbackAddress()) {
         throw new IllegalStateException("Dedicated e4steam requires server-ip=127.0.0.1 or ::1");
      }
   }

   @Override
   public String toString() {
      return "DedicatedRuntimeConfig{enabled="
         + this.enabled
         + ", access="
         + this.accessMode
         + ", maxPeers="
         + this.maxPeers
         + ", queryPort="
         + this.queryPort
         + ", whitelistEntries="
         + this.whitelist.size()
         + ", login=ANONYMOUS, publication=false}";
   }

   private static String first(String first, String second) {
      if (first != null && !first.trim().isEmpty()) {
         return first.trim();
      } else {
         return second != null && !second.trim().isEmpty() ? second.trim() : null;
      }
   }

   private static int number(String value, int fallback, int minimum, int maximum) {
      if (value == null) {
         return fallback;
      } else {
         int parsed;
         try {
            parsed = Integer.parseInt(value);
         } catch (NumberFormatException var6) {
            throw new IllegalArgumentException("Invalid number");
         }

         if (parsed >= minimum && parsed <= maximum) {
            return parsed;
         } else {
            throw new IllegalArgumentException("Out of range");
         }
      }
   }

   private static boolean bool(String value, boolean fallback) {
      if (value == null) {
         return fallback;
      } else if ("true".equalsIgnoreCase(value) || "1".equals(value)) {
         return true;
      } else if (!"false".equalsIgnoreCase(value) && !"0".equals(value)) {
         throw new IllegalArgumentException("Invalid boolean");
      } else {
         return false;
      }
   }

   private static Set<Long> parseSteamIds(String value) {
      if (value == null) {
         return Collections.emptySet();
      } else {
         LinkedHashSet<Long> ids = new LinkedHashSet<>();
         String[] parts = value.split(",", -1);
         if (parts.length > 1024) {
            throw new IllegalArgumentException("Too many whitelist entries");
         } else {
            for (String part : parts) {
               String trimmed = part.trim();
               if (trimmed.isEmpty()) {
                  throw new IllegalArgumentException("Invalid whitelist entry");
               }

               try {
                  long parsed = Long.parseUnsignedLong(trimmed);
                  if (parsed == 0L) {
                     throw new IllegalArgumentException("Invalid whitelist entry");
                  }

                  ids.add(parsed);
               } catch (NumberFormatException var10) {
                  throw new IllegalArgumentException("Invalid whitelist entry");
               }
            }

            return ids;
         }
      }
   }
}
