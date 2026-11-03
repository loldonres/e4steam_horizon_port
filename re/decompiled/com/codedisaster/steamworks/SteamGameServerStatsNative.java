package com.codedisaster.steamworks;

class SteamGameServerStatsNative {
   static native long createCallback(Object var0);

   static native long requestUserStats(long var0);

   static native boolean getUserStat(long var0, String var2, int[] var3);

   static native boolean getUserStat(long var0, String var2, float[] var3);

   static native boolean getUserAchievement(long var0, String var2, boolean[] var3);

   static native boolean setUserStat(long var0, String var2, int var3);

   static native boolean setUserStat(long var0, String var2, float var3);

   static native boolean updateUserAvgRateStat(long var0, String var2, float var3, double var4);

   static native boolean setUserAchievement(long var0, String var2);

   static native boolean clearUserAchievement(long var0, String var2);

   static native long storeUserStats(long var0);
}
