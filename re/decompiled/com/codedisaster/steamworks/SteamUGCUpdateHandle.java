package com.codedisaster.steamworks;

public class SteamUGCUpdateHandle extends SteamNativeHandle {
   SteamUGCUpdateHandle(long handle) {
      super(handle);
   }

   public boolean isValid() {
      return this.handle != -1L;
   }
}
