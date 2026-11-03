package com.codedisaster.steamworks;

public class SteamGameServerNetworking extends SteamNetworking {
   public SteamGameServerNetworking(SteamNetworkingCallback callback) {
      super(true, SteamGameServerNetworkingNative.createCallback(new SteamNetworkingCallbackAdapter(callback)));
   }
}
