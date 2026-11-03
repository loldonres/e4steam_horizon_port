package link.e4steam.lan;

import java.lang.ref.WeakReference;
import java.util.Locale;
import java.util.Objects;

public final class LanSettings {
   public static final int MIN_PLAYERS = 2;
   public static final int MAX_PLAYERS = 64;
   public static final int DEFAULT_PORT = 25565;
   private static volatile LanSettings.Snapshot current = defaults();
   private static volatile WeakReference<Object> activeServer = new WeakReference<>(null);
   private static volatile LanSettings.Snapshot active = defaults();
   private static volatile int nonSteamPlayers = 1;

   private LanSettings() {
   }

   public static LanSettings.Snapshot current() {
      return current;
   }

   public static void install(LanSettings.Snapshot replacement) {
      current = Objects.requireNonNull(replacement, "replacement").validated();
   }

   public static LanSettings.Snapshot defaults() {
      return new LanSettings.Snapshot(true, true, 8, 25565, "survival", false);
   }

   public static int steamGuestLimit() {
      return Math.max(0, active.maxPlayers() - nonSteamPlayers);
   }

   public static int steamLobbyCapacity() {
      return active.maxPlayers();
   }

   public static void activate(Object server, LanSettings.Snapshot settings) {
      Objects.requireNonNull(server, "server");
      if (activeServer.get() != server) {
         nonSteamPlayers = 1;
      }

      active = settings.validated();
      activeServer = new WeakReference<>(Objects.requireNonNull(server));
   }

   public static LanSettings.Snapshot active(Object server) {
      return server != null && activeServer.get() == server ? active : null;
   }

   public static void nonSteamPlayers(Object server, int count) {
      if (activeServer.get() == server) {
         nonSteamPlayers = Math.max(1, count);
      }
   }

   public static void closed() {
      activeServer.clear();
      active = defaults();
      nonSteamPlayers = 1;
   }

   public static final class Snapshot {
      private final boolean onlineMode;
      private final boolean pvp;
      private final int maxPlayers;
      private final int port;
      private final String gameMode;
      private final boolean allowCheats;

      public Snapshot(boolean onlineMode, boolean pvp, int maxPlayers, int port, String gameMode, boolean allowCheats) {
         this.onlineMode = onlineMode;
         this.pvp = pvp;
         this.maxPlayers = maxPlayers;
         this.port = port;
         this.gameMode = gameMode == null ? "survival" : gameMode.trim().toLowerCase(Locale.ROOT);
         this.allowCheats = allowCheats;
      }

      public boolean onlineMode() {
         return this.onlineMode;
      }

      public boolean pvp() {
         return this.pvp;
      }

      public int maxPlayers() {
         return this.maxPlayers;
      }

      public int port() {
         return this.port;
      }

      public String gameMode() {
         return this.gameMode;
      }

      public boolean allowCheats() {
         return this.allowCheats;
      }

      public LanSettings.Snapshot withOnlineMode(boolean value) {
         return new LanSettings.Snapshot(value, this.pvp, this.maxPlayers, this.port, this.gameMode, this.allowCheats);
      }

      public LanSettings.Snapshot withPvp(boolean value) {
         return new LanSettings.Snapshot(this.onlineMode, value, this.maxPlayers, this.port, this.gameMode, this.allowCheats);
      }

      public LanSettings.Snapshot withMaxPlayers(int value) {
         return new LanSettings.Snapshot(this.onlineMode, this.pvp, value, this.port, this.gameMode, this.allowCheats).validated();
      }

      public LanSettings.Snapshot withPort(int value) {
         return new LanSettings.Snapshot(this.onlineMode, this.pvp, this.maxPlayers, value, this.gameMode, this.allowCheats).validated();
      }

      public LanSettings.Snapshot withGameMode(String value) {
         return new LanSettings.Snapshot(this.onlineMode, this.pvp, this.maxPlayers, this.port, value, this.allowCheats).validated();
      }

      public LanSettings.Snapshot withAllowCheats(boolean value) {
         return new LanSettings.Snapshot(this.onlineMode, this.pvp, this.maxPlayers, this.port, this.gameMode, value);
      }

      public LanSettings.Snapshot validated() {
         if (this.maxPlayers < 2 || this.maxPlayers > 64) {
            throw new IllegalArgumentException("LAN max players must be between 2 and 64");
         } else if (this.port < 1 || this.port > 65535) {
            throw new IllegalArgumentException("LAN port must be between 1 and 65535");
         } else if (!this.gameMode.equals("survival")
            && !this.gameMode.equals("creative")
            && !this.gameMode.equals("adventure")
            && !this.gameMode.equals("spectator")) {
            throw new IllegalArgumentException("Unsupported LAN game mode: " + this.gameMode);
         } else {
            return this;
         }
      }
   }
}
