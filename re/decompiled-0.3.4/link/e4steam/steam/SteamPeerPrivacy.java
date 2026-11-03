package link.e4steam.steam;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

public final class SteamPeerPrivacy {
   private SteamPeerPrivacy() {
   }

   public static String opaquePeerId(long generation, long steamId) {
      if (generation > 0L && steamId != 0L) {
         try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update("e4steam:opaque-peer:v1:".getBytes(StandardCharsets.UTF_8));
            digest.update(Long.toUnsignedString(generation).getBytes(StandardCharsets.UTF_8));
            digest.update((byte)58);
            digest.update(Long.toUnsignedString(steamId).getBytes(StandardCharsets.UTF_8));
            byte[] full = digest.digest();
            byte[] shortened = new byte[18];
            System.arraycopy(full, 0, shortened, 0, shortened.length);
            return "p_" + Base64.getUrlEncoder().withoutPadding().encodeToString(shortened);
         } catch (NoSuchAlgorithmException var7) {
            throw new IllegalStateException("SHA-256 is unavailable", var7);
         }
      } else {
         throw new IllegalArgumentException("Current generation and authenticated Steam ID required");
      }
   }

   public static String dedicatedSessionId(long generation) {
      if (generation <= 0L) {
         throw new IllegalArgumentException("generation");
      } else {
         return "dedicated_" + Long.toUnsignedString(generation, 36);
      }
   }
}
