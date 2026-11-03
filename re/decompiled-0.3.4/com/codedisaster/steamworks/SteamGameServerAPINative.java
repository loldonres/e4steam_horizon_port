package com.codedisaster.steamworks;

class SteamGameServerAPINative {
   static native boolean nativeInit(int var0, short var1, short var2, int var3, String var4);

   static native void nativeShutdown();

   static native void runCallbacks();

   static native boolean isSecure();

   static native long nativeGetSteamID();
}
