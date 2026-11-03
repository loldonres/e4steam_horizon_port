package com.codedisaster.steamworks;

public abstract class SteamMatchmakingServerListResponse extends SteamInterface {
   protected SteamMatchmakingServerListResponse() {
      super(-1L);
      this.callback = createProxy(this);
   }

   public abstract void serverResponded(SteamServerListRequest var1, int var2);

   void serverResponded(long request, int server) {
      this.serverResponded(new SteamServerListRequest(request), server);
   }

   public abstract void serverFailedToRespond(SteamServerListRequest var1, int var2);

   void serverFailedToRespond(long request, int server) {
      this.serverFailedToRespond(new SteamServerListRequest(request), server);
   }

   public abstract void refreshComplete(SteamServerListRequest var1, SteamMatchmakingServerListResponse.Response var2);

   void refreshComplete(long request, int response) {
      this.refreshComplete(new SteamServerListRequest(request), SteamMatchmakingServerListResponse.Response.byOrdinal(response));
   }

   private static native long createProxy(SteamMatchmakingServerListResponse var0);

   public static enum Response {
      ServerResponded,
      ServerFailedToRespond,
      NoServersListedOnMasterServer;

      private static final SteamMatchmakingServerListResponse.Response[] values = values();

      static SteamMatchmakingServerListResponse.Response byOrdinal(int ordinal) {
         return values[ordinal];
      }
   }
}
