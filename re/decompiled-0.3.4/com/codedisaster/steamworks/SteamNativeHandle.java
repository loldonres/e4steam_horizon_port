package com.codedisaster.steamworks;

public abstract class SteamNativeHandle {
   long handle;

   SteamNativeHandle(long handle) {
      this.handle = handle;
   }

   public static <T extends SteamNativeHandle> long getNativeHandle(T handle) {
      return handle.handle;
   }

   @Override
   public int hashCode() {
      return Long.valueOf(this.handle).hashCode();
   }

   @Override
   public boolean equals(Object other) {
      return other instanceof SteamNativeHandle ? this.handle == ((SteamNativeHandle)other).handle : false;
   }

   @Override
   public String toString() {
      return Long.toHexString(this.handle);
   }
}
