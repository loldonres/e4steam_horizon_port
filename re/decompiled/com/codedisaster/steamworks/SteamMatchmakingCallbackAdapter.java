package com.codedisaster.steamworks;

class SteamMatchmakingCallbackAdapter extends SteamCallbackAdapter<SteamMatchmakingCallback> {
   private static final SteamMatchmaking.ChatMemberStateChange[] stateChangeValues = SteamMatchmaking.ChatMemberStateChange.values();

   SteamMatchmakingCallbackAdapter(SteamMatchmakingCallback callback) {
      super(callback);
   }

   void onFavoritesListChanged(int ip, int queryPort, int connPort, int appID, int flags, boolean add, int accountID) {
      this.callback.onFavoritesListChanged(ip, queryPort, connPort, appID, flags, add, accountID);
   }

   void onLobbyInvite(long steamIDUser, long steamIDLobby, long gameID) {
      this.callback.onLobbyInvite(new SteamID(steamIDUser), new SteamID(steamIDLobby), gameID);
   }

   void onLobbyEnter(long steamIDLobby, int chatPermissions, boolean blocked, int response) {
      this.callback.onLobbyEnter(new SteamID(steamIDLobby), chatPermissions, blocked, SteamMatchmaking.ChatRoomEnterResponse.byValue(response));
   }

   void onLobbyDataUpdate(long steamIDLobby, long steamIDMember, boolean success) {
      this.callback.onLobbyDataUpdate(new SteamID(steamIDLobby), new SteamID(steamIDMember), success);
   }

   void onLobbyChatUpdate(long steamIDLobby, long steamIDUserChanged, long steamIDMakingChange, int stateChange) {
      SteamID lobby = new SteamID(steamIDLobby);
      SteamID userChanged = new SteamID(steamIDUserChanged);
      SteamID makingChange = new SteamID(steamIDMakingChange);

      for (SteamMatchmaking.ChatMemberStateChange value : stateChangeValues) {
         if (SteamMatchmaking.ChatMemberStateChange.isSet(value, stateChange)) {
            this.callback.onLobbyChatUpdate(lobby, userChanged, makingChange, value);
         }
      }
   }

   void onLobbyChatMessage(long steamIDLobby, long steamIDUser, int entryType, int chatID) {
      this.callback.onLobbyChatMessage(new SteamID(steamIDLobby), new SteamID(steamIDUser), SteamMatchmaking.ChatEntryType.byValue(entryType), chatID);
   }

   void onLobbyGameCreated(long steamIDLobby, long steamIDGameServer, int ip, short port) {
      this.callback.onLobbyGameCreated(new SteamID(steamIDLobby), new SteamID(steamIDGameServer), ip, port);
   }

   void onLobbyMatchList(int lobbiesMatching) {
      this.callback.onLobbyMatchList(lobbiesMatching);
   }

   void onLobbyKicked(long steamIDLobby, long steamIDAdmin, boolean kickedDueToDisconnect) {
      this.callback.onLobbyKicked(new SteamID(steamIDLobby), new SteamID(steamIDAdmin), kickedDueToDisconnect);
   }

   void onLobbyCreated(int result, long steamIDLobby) {
      this.callback.onLobbyCreated(SteamResult.byValue(result), new SteamID(steamIDLobby));
   }

   void onFavoritesListAccountsUpdated(int result) {
      this.callback.onFavoritesListAccountsUpdated(SteamResult.byValue(result));
   }
}
