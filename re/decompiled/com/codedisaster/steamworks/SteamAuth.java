package com.codedisaster.steamworks;

public class SteamAuth {
   public static enum AuthSessionResponse {
      OK,
      UserNotConnectedToSteam,
      NoLicenseOrExpired,
      VACBanned,
      LoggedInElseWhere,
      VACCheckTimedOut,
      AuthTicketCanceled,
      AuthTicketInvalidAlreadyUsed,
      AuthTicketInvalid,
      PublisherIssuedBan,
      AuthTicketNetworkIdentityFailure;

      private static final SteamAuth.AuthSessionResponse[] values = values();

      static SteamAuth.AuthSessionResponse byOrdinal(int authSessionResponse) {
         return values[authSessionResponse];
      }
   }

   public static enum BeginAuthSessionResult {
      OK,
      InvalidTicket,
      DuplicateRequest,
      InvalidVersion,
      GameMismatch,
      ExpiredTicket;

      private static final SteamAuth.BeginAuthSessionResult[] values = values();

      static SteamAuth.BeginAuthSessionResult byOrdinal(int authSessionResponse) {
         return values[authSessionResponse];
      }
   }

   public static enum UserHasLicenseForAppResult {
      HasLicense,
      DoesNotHaveLicense,
      NoAuth;

      private static final SteamAuth.UserHasLicenseForAppResult[] values = values();

      static SteamAuth.UserHasLicenseForAppResult byOrdinal(int result) {
         return values[result];
      }
   }
}
