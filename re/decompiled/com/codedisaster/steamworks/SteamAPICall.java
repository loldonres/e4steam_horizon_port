package com.codedisaster.steamworks;

public class SteamAPICall extends SteamNativeHandle {
   SteamAPICall(long handle) {
      super(handle);
   }

   public boolean isValid() {
      return this.handle != 0L;
   }
}
