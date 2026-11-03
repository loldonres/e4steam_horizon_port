package com.codedisaster.steamworks;

public class SteamServerListRequest extends SteamNativeHandle {
   SteamServerListRequest(long handle) {
      super(handle);
   }

   public boolean isValid() {
      return this.handle != 0L;
   }
}
