package com.codedisaster.steamworks;

public abstract class SteamNativeIntHandle {
   int handle;

   SteamNativeIntHandle(int handle) {
      this.handle = handle;
   }

   public static <T extends SteamNativeIntHandle> int getNativeHandle(T handle) {
      return handle.handle;
   }

   @Override
   public int hashCode() {
      return Integer.valueOf(this.handle).hashCode();
   }

   @Override
   public boolean equals(Object other) {
      return other instanceof SteamNativeIntHandle ? this.handle == ((SteamNativeIntHandle)other).handle : false;
   }

   @Override
   public String toString() {
      return Integer.toHexString(this.handle);
   }
}
