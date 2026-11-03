package com.codedisaster.steamworks;

public abstract class SteamMatchmakingRulesResponse extends SteamInterface {
   protected SteamMatchmakingRulesResponse() {
      super(-1L);
      this.callback = createProxy(this);
   }

   public abstract void rulesResponded(String var1, String var2);

   public abstract void rulesFailedToRespond();

   public abstract void rulesRefreshComplete();

   private static native long createProxy(SteamMatchmakingRulesResponse var0);
}
