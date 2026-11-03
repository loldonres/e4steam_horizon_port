package com.codedisaster.steamworks;

import java.nio.ByteBuffer;

class SteamGameServerNative {
   static native long createCallback(Object var0);

   static native void setProduct(String var0);

   static native void setGameDescription(String var0);

   static native void setModDir(String var0);

   static native void setDedicatedServer(boolean var0);

   static native void logOn(String var0);

   static native void logOnAnonymous();

   static native void logOff();

   static native boolean isLoggedOn();

   static native boolean isSecure();

   static native long getSteamID();

   static native boolean wasRestartRequested();

   static native void setMaxPlayerCount(int var0);

   static native void setBotPlayerCount(int var0);

   static native void setServerName(String var0);

   static native void setMapName(String var0);

   static native void setPasswordProtected(boolean var0);

   static native void setSpectatorPort(short var0);

   static native void setSpectatorServerName(String var0);

   static native void clearAllKeyValues();

   static native void setKeyValue(String var0, String var1);

   static native void setGameTags(String var0);

   static native void setGameData(String var0);

   static native void setRegion(String var0);

   static native void setAdvertiseServerActive(boolean var0);

   static native boolean sendUserConnectAndAuthenticate(int var0, ByteBuffer var1, int var2, int var3, long[] var4);

   static native long createUnauthenticatedUserConnection();

   static native void sendUserDisconnect(long var0);

   static native boolean updateUserData(long var0, String var2, int var3);

   static native int getAuthSessionTicket(ByteBuffer var0, int var1, int var2, int[] var3);

   static native int beginAuthSession(ByteBuffer var0, int var1, int var2, long var3);

   static native void endAuthSession(long var0);

   static native void cancelAuthTicket(int var0);

   static native int userHasLicenseForApp(long var0, int var2);

   static native boolean requestUserGroupStatus(long var0, long var2);

   static native int getPublicIP();

   static native boolean handleIncomingPacket(ByteBuffer var0, int var1, int var2, int var3, short var4);

   static native int getNextOutgoingPacket(ByteBuffer var0, int var1, int var2, int[] var3, short[] var4);

   static native long associateWithClan(long var0);

   static native long computeNewPlayerCompatibility(long var0);
}
