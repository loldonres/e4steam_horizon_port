package com.codedisaster.steamworks;

class SteamUserCallbackAdapter extends SteamCallbackAdapter<SteamUserCallback> {
   SteamUserCallbackAdapter(SteamUserCallback callback) {
      super(callback);
   }

   void onAuthSessionTicket(long authTicket, int result) {
      this.callback.onAuthSessionTicket(new SteamAuthTicket(authTicket), SteamResult.byValue(result));
   }

   void onValidateAuthTicket(long steamID, int authSessionResponse, long ownerSteamID) {
      this.callback.onValidateAuthTicket(new SteamID(steamID), SteamAuth.AuthSessionResponse.byOrdinal(authSessionResponse), new SteamID(ownerSteamID));
   }

   void onMicroTxnAuthorization(int appID, long orderID, boolean authorized) {
      this.callback.onMicroTxnAuthorization(appID, orderID, authorized);
   }

   void onEncryptedAppTicket(int result) {
      this.callback.onEncryptedAppTicket(SteamResult.byValue(result));
   }

   void onGetTicketForWebApi(long authTicket, int result, byte[] ticketData) {
      this.callback.onGetTicketForWebApi(new SteamAuthTicket(authTicket), SteamResult.byValue(result), ticketData);
   }
}
