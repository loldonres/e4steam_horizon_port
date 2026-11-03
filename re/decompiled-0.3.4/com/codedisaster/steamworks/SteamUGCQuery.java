package com.codedisaster.steamworks;

public class SteamUGCQuery extends SteamNativeHandle {
   public SteamUGCQuery(long handle) {
      super(handle);
   }

   public boolean isValid() {
      return this.handle != -1L;
   }
}
