package link.e4steam.steam;

import java.util.Objects;
import java.util.concurrent.CompletionStage;

public interface SteamRuntimeBackend extends AutoCloseable {
   SteamRuntimeBackend.RuntimeKind kind();

   CompletionStage<SteamRuntimeBackend.RuntimeReady> start(SteamRuntimeBackend.Config var1);

   SteamRuntimeBackend.Snapshot snapshot();

   CompletionStage<Void> stop(SteamRuntimeBackend.ShutdownReason var1);

   @Override
   default void close() {
      this.stop(SteamRuntimeBackend.ShutdownReason.NORMAL);
   }

   public static final class Config {
      private final int appId;
      private final int gamePort;
      private final int queryPort;
      private final int maxPeers;
      private final String serverName;
      private final char[] loginToken;

      public Config(int appId, int gamePort, int queryPort, int maxPeers, String serverName, char[] loginToken) {
         if (appId != 480) {
            throw new IllegalArgumentException("Only App ID 480 is supported");
         } else if (gamePort < 1 || gamePort > 65535) {
            throw new IllegalArgumentException("gamePort");
         } else if (queryPort < 0 || queryPort > 65535) {
            throw new IllegalArgumentException("queryPort");
         } else if (maxPeers >= 1 && maxPeers <= 256) {
            String checkedName = Objects.requireNonNull(serverName, "serverName").trim();
            if (checkedName.isEmpty() || checkedName.length() > 64) {
               throw new IllegalArgumentException("serverName");
            } else if (containsControl(checkedName)) {
               throw new IllegalArgumentException("serverName");
            } else {
               this.appId = appId;
               this.gamePort = gamePort;
               this.queryPort = queryPort;
               this.maxPeers = maxPeers;
               this.serverName = checkedName;
               this.loginToken = loginToken == null ? new char[0] : (char[])loginToken.clone();
               if (this.loginToken.length > 512) {
                  throw new IllegalArgumentException("loginToken");
               }
            }
         } else {
            throw new IllegalArgumentException("maxPeers");
         }
      }

      public int appId() {
         return this.appId;
      }

      public int gamePort() {
         return this.gamePort;
      }

      public int queryPort() {
         return this.queryPort;
      }

      public int maxPeers() {
         return this.maxPeers;
      }

      public String serverName() {
         return this.serverName;
      }

      public boolean anonymousLogin() {
         return this.loginToken.length == 0;
      }

      char[] copyLoginToken() {
         return (char[])this.loginToken.clone();
      }

      @Override
      public String toString() {
         return "SteamRuntimeBackend.Config{appId=480, gamePort="
            + this.gamePort
            + ", queryPort="
            + this.queryPort
            + ", maxPeers="
            + this.maxPeers
            + ", login="
            + (this.anonymousLogin() ? "ANONYMOUS" : "SECRET_SOURCE")
            + "}";
      }

      private static boolean containsControl(String value) {
         for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
               return true;
            }
         }

         return false;
      }
   }

   public static enum RuntimeKind {
      INTEGRATED_CLIENT_HOST,
      DEDICATED_GAME_SERVER;
   }

   public static final class RuntimeReady {
      private final long generation;
      private final long internalServerSteamId;

      public RuntimeReady(long generation, long internalServerSteamId) {
         if (generation <= 0L) {
            throw new IllegalArgumentException("generation");
         } else {
            this.generation = generation;
            this.internalServerSteamId = internalServerSteamId;
         }
      }

      public long generation() {
         return this.generation;
      }

      public long internalServerSteamId() {
         return this.internalServerSteamId;
      }

      @Override
      public String toString() {
         return "RuntimeReady{generation=" + this.generation + "}";
      }
   }

   public static enum ShutdownReason {
      NORMAL,
      MINECRAFT_STOPPING,
      STEAM_DISCONNECTED,
      STARTUP_FAILURE;
   }

   public static final class Snapshot {
      private final SteamRuntimeBackend.State state;
      private final long generation;
      private final String failureCategory;

      public Snapshot(SteamRuntimeBackend.State state, long generation, String failureCategory) {
         this.state = Objects.requireNonNull(state, "state");
         this.generation = generation;
         this.failureCategory = failureCategory == null ? "" : failureCategory;
      }

      public SteamRuntimeBackend.State state() {
         return this.state;
      }

      public long generation() {
         return this.generation;
      }

      public String failureCategory() {
         return this.failureCategory;
      }

      @Override
      public String toString() {
         return "SteamRuntimeBackend.Snapshot{state=" + this.state + ", generation=" + this.generation + ", failure=" + this.failureCategory + "}";
      }
   }

   public static enum State {
      OFF,
      CONFIG_VALIDATED,
      NATIVES_READY,
      STEAM_INITIALIZING,
      STEAM_LOGGING_ON,
      TRANSPORT_READY,
      DRAINING,
      STOPPED,
      FAILED;
   }

   public interface StateListener {
      void onState(SteamRuntimeBackend.State var1, String var2);
   }
}
