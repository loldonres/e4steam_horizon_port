package com.codedisaster.steamworks;

import java.io.PrintStream;

public class SteamAPI {
   private static boolean isRunning = false;
   private static boolean isNativeAPILoaded = false;

   public static boolean loadLibraries(SteamLibraryLoader loader) {
      if (!isNativeAPILoaded) {
         isNativeAPILoaded = loader.loadLibrary("steam_api");
         isNativeAPILoaded = isNativeAPILoaded && loader.loadLibrary("steamworks4j");
      }

      return isNativeAPILoaded;
   }

   public static boolean restartAppIfNecessary(int appId) throws SteamException {
      if (!isNativeAPILoaded) {
         throw new SteamException("Native libraries not loaded.\nEnsure to call SteamAPI.loadLibraries() first!");
      } else {
         return nativeRestartAppIfNecessary(appId);
      }
   }

   public static boolean init() throws SteamException {
      return initEx() == SteamAPI.InitResult.OK;
   }

   public static SteamAPI.InitResult initEx() throws SteamException {
      if (!isNativeAPILoaded) {
         throw new SteamException("Native libraries not loaded.\nEnsure to call SteamAPI.loadLibraries() first!");
      } else {
         SteamAPI.InitResult result = SteamAPI.InitResult.byOrdinal(nativeInit());
         isRunning = result == SteamAPI.InitResult.OK;
         return result;
      }
   }

   public static void shutdown() {
      isRunning = false;
      nativeShutdown();
   }

   public static boolean isSteamRunning() {
      return isSteamRunning(false);
   }

   public static boolean isSteamRunning(boolean checkNative) {
      return isRunning && (!checkNative || isSteamRunningNative());
   }

   public static void printDebugInfo(PrintStream stream) {
      stream.println("  Steam API initialized: " + isRunning);
      stream.println("  Steam client active: " + isSteamRunning());
   }

   static boolean isIsNativeAPILoaded() {
      return isNativeAPILoaded;
   }

   private static native boolean nativeRestartAppIfNecessary(int var0);

   public static native void releaseCurrentThreadMemory();

   private static native int nativeInit();

   private static native void nativeShutdown();

   public static native void runCallbacks();

   private static native boolean isSteamRunningNative();

   public static enum InitResult {
      OK,
      FailedGeneric,
      NoSteamClient,
      VersionMismatch;

      private static final SteamAPI.InitResult[] values = values();

      static SteamAPI.InitResult byOrdinal(int result) {
         return values[result];
      }
   }
}
