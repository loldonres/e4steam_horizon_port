package com.codedisaster.steamworks;

class SteamGameServerStatsCallbackAdapter extends SteamCallbackAdapter<SteamGameServerStatsCallback> {
   SteamGameServerStatsCallbackAdapter(SteamGameServerStatsCallback callback) {
      super(callback);
   }

   void onStatsReceived(int result, long steamIDUser) {
      this.callback.onStatsReceived(SteamResult.byValue(result), new SteamID(steamIDUser));
   }

   void onStatsStored(int result, long steamIDUser) {
      this.callback.onStatsStored(SteamResult.byValue(result), new SteamID(steamIDUser));
   }

   void onStatsUnloaded(long steamIDUser) {
      this.callback.onStatsUnloaded(new SteamID(steamIDUser));
   }
}
