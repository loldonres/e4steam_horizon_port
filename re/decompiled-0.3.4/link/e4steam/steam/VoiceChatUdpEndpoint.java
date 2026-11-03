package link.e4steam.steam;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import link.e4steam.Agnos;
import link.e4steam.E4steamClient;

final class VoiceChatUdpEndpoint {
   static final byte CLIENT_PORT_SAME_AS_SERVER = 1;
   static final byte CLIENT_PORT_SAME_AS_MINECRAFT = 2;
   private final int hostPort;
   private final byte clientPortMode;
   private final String source;

   private VoiceChatUdpEndpoint(int hostPort, byte clientPortMode, String source) {
      this.hostPort = hostPort;
      this.clientPortMode = clientPortMode;
      this.source = source;
   }

   static VoiceChatUdpEndpoint resolve(int minecraftPort, int fallbackPort) {
      int simpleVoiceChatPort = simpleVoiceChatPort();
      if (simpleVoiceChatPort > 0) {
         return samePort(simpleVoiceChatPort, "Simple Voice Chat");
      } else {
         VoiceChatUdpEndpoint.PlasmoPort plasmo = plasmoVoicePort();
         if (plasmo.detected()) {
            return plasmo.port() > 0 ? samePort(plasmo.port(), "Plasmo Voice") : new VoiceChatUdpEndpoint(minecraftPort, (byte)2, "Plasmo Voice");
         } else {
            return fallbackPort > 0 ? samePort(fallbackPort, "configured UDP service") : new VoiceChatUdpEndpoint(0, (byte)1, "disabled");
         }
      }
   }

   static VoiceChatUdpEndpoint fromHandshake(int hostPort, byte clientPortMode) {
      if (hostPort < 0 || hostPort > 65535) {
         throw new IllegalArgumentException("Invalid UDP host port: " + hostPort);
      } else if (clientPortMode != 1 && clientPortMode != 2) {
         throw new IllegalArgumentException("Invalid UDP client port mode: " + clientPortMode);
      } else {
         return new VoiceChatUdpEndpoint(hostPort, clientPortMode, "Steam host");
      }
   }

   private static VoiceChatUdpEndpoint samePort(int port, String source) {
      return new VoiceChatUdpEndpoint(port, (byte)1, source);
   }

   int hostPort() {
      return this.hostPort;
   }

   int clientPort(int minecraftBridgePort) {
      return this.clientPortMode == 2 ? minecraftBridgePort : this.hostPort;
   }

   byte clientPortMode() {
      return this.clientPortMode;
   }

   String source() {
      return this.source;
   }

   private static int simpleVoiceChatPort() {
      ClassLoader[] loaders = new ClassLoader[]{contextClassLoader(), VoiceChatUdpEndpoint.class.getClassLoader()};

      for (ClassLoader loader : loaders) {
         try {
            Class<?> voicechat = Class.forName("de.maxhenkel.voicechat.Voicechat", false, loader);
            Object events = voicechat.getField("SERVER").get(null);
            int port = simpleVoiceChatPort(events);
            if (port > 0) {
               return port;
            }
         } catch (ClassNotFoundException var8) {
         } catch (LinkageError | RuntimeException | ReflectiveOperationException var9) {
            E4steamClient.LOGGER.debug("Could not read the active Simple Voice Chat UDP port", var9);
         }
      }

      return 0;
   }

   static int simpleVoiceChatPort(Object serverEvents) throws ReflectiveOperationException {
      if (serverEvents == null) {
         return 0;
      } else {
         Object server = serverEvents.getClass().getMethod("getServer").invoke(serverEvents);
         if (server == null) {
            return 0;
         } else {
            Object result = server.getClass().getMethod("getPort").invoke(server);
            if (!(result instanceof Number)) {
               return 0;
            } else {
               int port = ((Number)result).intValue();
               return port > 0 && port <= 65535 ? port : 0;
            }
         }
      }
   }

   private static VoiceChatUdpEndpoint.PlasmoPort plasmoVoicePort() {
      Path configFolder = Agnos.configDir().resolve("plasmovoice");
      boolean detected = Files.isDirectory(configFolder) || classExists("su.plo.voice.api.server.PlasmoVoiceServer") || classExists("su.plo.voice.PlasmoVoice");
      if (!detected) {
         return new VoiceChatUdpEndpoint.PlasmoPort(false, 0);
      } else {
         for (String fileName : Arrays.asList("server.toml", "config.toml")) {
            Integer configured = readHostPort(configFolder.resolve(fileName));
            if (configured != null) {
               return new VoiceChatUdpEndpoint.PlasmoPort(true, configured);
            }
         }

         return new VoiceChatUdpEndpoint.PlasmoPort(true, 0);
      }
   }

   private static Integer readHostPort(Path path) {
      if (!Files.isRegularFile(path)) {
         return null;
      } else {
         try {
            boolean hostSection = false;

            for (String rawLine : Files.readAllLines(path)) {
               String line = rawLine.trim();
               if (line.startsWith("[") && line.endsWith("]")) {
                  hostSection = line.equals("[host]");
               } else if (hostSection && line.startsWith("port")) {
                  int equals = line.indexOf(61);
                  if (equals >= 0) {
                     String value = line.substring(equals + 1).split("#", 2)[0].trim();
                     int port = Integer.parseInt(value);
                     return port >= 0 && port <= 65535 ? port : null;
                  }
               }
            }
         } catch (Exception var8) {
            E4steamClient.LOGGER.debug("Could not read Plasmo Voice UDP configuration from {}", path, var8);
         }

         return null;
      }
   }

   private static boolean classExists(String className) {
      try {
         Class.forName(className, false, contextClassLoader());
         return true;
      } catch (LinkageError | ClassNotFoundException var2) {
         return false;
      }
   }

   private static ClassLoader contextClassLoader() {
      ClassLoader loader = Thread.currentThread().getContextClassLoader();
      return loader == null ? VoiceChatUdpEndpoint.class.getClassLoader() : loader;
   }

   private static final class PlasmoPort {
      private final boolean detected;
      private final int port;

      private PlasmoPort(boolean detected, int port) {
         this.detected = detected;
         this.port = port;
      }

      private boolean detected() {
         return this.detected;
      }

      private int port() {
         return this.port;
      }
   }
}
