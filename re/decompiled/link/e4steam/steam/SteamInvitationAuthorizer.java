package link.e4steam.steam;

import java.security.MessageDigest;

final class SteamInvitationAuthorizer {
   private SteamInvitationAuthorizer() {
   }

   static SteamInvitationAuthorizer.Decision authorize(byte[] activeToken, byte[] presentedToken, boolean peerAllowed) {
      if (activeToken == null) {
         return SteamInvitationAuthorizer.Decision.SESSION_CLOSED;
      } else if (presentedToken != null && MessageDigest.isEqual(activeToken, presentedToken)) {
         return peerAllowed ? SteamInvitationAuthorizer.Decision.ALLOWED : SteamInvitationAuthorizer.Decision.PEER_NOT_ALLOWED;
      } else {
         return SteamInvitationAuthorizer.Decision.INVALID_OR_EXPIRED_TOKEN;
      }
   }

   static enum Decision {
      ALLOWED,
      SESSION_CLOSED,
      INVALID_OR_EXPIRED_TOKEN,
      PEER_NOT_ALLOWED;
   }
}
