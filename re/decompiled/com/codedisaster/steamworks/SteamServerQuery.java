package com.codedisaster.steamworks;

public class SteamServerQuery extends SteamNativeIntHandle {
   public static final SteamServerQuery INVALID = new SteamServerQuery(-1);

   SteamServerQuery(int handle) {
      super(handle);
   }
}
