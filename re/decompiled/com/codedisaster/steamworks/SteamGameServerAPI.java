package com.codedisaster.steamworks;

public class SteamGameServerAPI {
   private static boolean isRunning = false;
   private static boolean isNativeAPILoaded = false;

   public static boolean loadLibraries(SteamLibraryLoader loader) {
      if (!isNativeAPILoaded) {
         isNativeAPILoaded = SteamAPI.loadLibraries(loader);
         isNativeAPILoaded = isNativeAPILoaded && loader.loadLibrary("steamworks4j-server");
      }

      return isNativeAPILoaded;
   }

   public static boolean init(int ip, short gamePort, short queryPort, SteamGameServerAPI.ServerMode serverMode, String versionString) throws SteamException {
      if (!isNativeAPILoaded) {
         throw new SteamException("Native server libraries not loaded.\nEnsure to call SteamGameServerAPI.loadLibraries() first!");
      } else {
         isRunning = SteamGameServerAPINative.nativeInit(ip, gamePort, queryPort, serverMode.ordinal(), versionString);
         return isRunning;
      }
   }

   public static void shutdown() {
      isRunning = false;
      SteamGameServerAPINative.nativeShutdown();
   }

   public static void runCallbacks() {
      SteamGameServerAPINative.runCallbacks();
   }

   public static boolean isSecure() {
      return SteamGameServerAPINative.isSecure();
   }

   public static SteamID getSteamID() {
      return new SteamID(SteamGameServerAPINative.nativeGetSteamID());
   }

   public static enum ServerMode {
      Invalid,
      NoAuthentication,
      Authentication,
      AuthenticationAndSecure;
   }
}
