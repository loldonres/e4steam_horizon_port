package link.e4steam.steam;

interface SteamApi {
   boolean loadLibraries(SteamNativeLibraryLoader var1);

   boolean init() throws Exception;

   boolean isInitialized();

   boolean isNativeSteamClientRunning();

   void runCallbacks();

   void shutdown();
}
