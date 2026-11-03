package com.codedisaster.steamworks;

public abstract class SteamMatchmakingPingResponse extends SteamInterface {
   protected SteamMatchmakingPingResponse() {
      super(-1L);
      this.callback = createProxy(this);
   }

   public abstract void serverResponded(SteamMatchmakingGameServerItem var1);

   public abstract void serverFailedToRespond();

   private static native long createProxy(SteamMatchmakingPingResponse var0);
}
