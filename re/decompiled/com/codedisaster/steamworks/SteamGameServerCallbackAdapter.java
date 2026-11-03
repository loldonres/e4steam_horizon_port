package com.codedisaster.steamworks;

class SteamGameServerCallbackAdapter extends SteamCallbackAdapter<SteamGameServerCallback> {
   SteamGameServerCallbackAdapter(SteamGameServerCallback callback) {
      super(callback);
   }

   void onValidateAuthTicketResponse(long steamID, int authSessionResponse, long ownerSteamID) {
      this.callback.onValidateAuthTicketResponse(new SteamID(steamID), SteamAuth.AuthSessionResponse.byOrdinal(authSessionResponse), new SteamID(ownerSteamID));
   }

   void onSteamServersConnected() {
      this.callback.onSteamServersConnected();
   }

   void onSteamServerConnectFailure(int result, boolean stillRetrying) {
      this.callback.onSteamServerConnectFailure(SteamResult.byValue(result), stillRetrying);
   }

   void onSteamServersDisconnected(int result) {
      this.callback.onSteamServersDisconnected(SteamResult.byValue(result));
   }

   void onClientApprove(long steamID, long ownerSteamID) {
      this.callback.onClientApprove(new SteamID(steamID), new SteamID(ownerSteamID));
   }

   void onClientDeny(long steamID, int denyReason, String optionalText) {
      this.callback.onClientDeny(new SteamID(steamID), SteamGameServer.DenyReason.byOrdinal(denyReason), optionalText);
   }

   void onClientKick(long steamID, int denyReason) {
      this.callback.onClientKick(new SteamID(steamID), SteamGameServer.DenyReason.byOrdinal(denyReason));
   }

   void onClientGroupStatus(long steamID, long steamIDGroup, boolean isMember, boolean isOfficer) {
      this.callback.onClientGroupStatus(new SteamID(steamID), new SteamID(steamIDGroup), isMember, isOfficer);
   }

   void onAssociateWithClanResult(int result) {
      this.callback.onAssociateWithClanResult(SteamResult.byValue(result));
   }

   void onComputeNewPlayerCompatibilityResult(
      int result, int playersThatDontLikeCandidate, int playersThatCandidateDoesntLike, int clanPlayersThatDontLikeCandidate, long steamIDCandidate
   ) {
      this.callback
         .onComputeNewPlayerCompatibilityResult(
            SteamResult.byValue(result),
            playersThatDontLikeCandidate,
            playersThatCandidateDoesntLike,
            clanPlayersThatDontLikeCandidate,
            new SteamID(steamIDCandidate)
         );
   }
}
