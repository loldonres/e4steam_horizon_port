package com.codedisaster.steamworks;

public class SteamID extends SteamNativeHandle {
   private static final long InvalidSteamID = getInvalidSteamID();

   public SteamID() {
      super(InvalidSteamID);
   }

   public SteamID(SteamID steamID) {
      super(steamID.handle);
   }

   SteamID(long id) {
      super(id);
   }

   public boolean isValid() {
      return isValid(this.handle);
   }

   public int getAccountID() {
      return (int)(this.handle % 4294967296L);
   }

   public static SteamID createFromNativeHandle(long id) {
      return new SteamID(id);
   }

   private static native boolean isValid(long var0);

   private static native long getInvalidSteamID();
}
