package link.e4steam.steam;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import link.e4steam.HexCodec;

public final class SteamAddress {
   private static final Pattern SHORT_PATTERN = Pattern.compile("^s-([0-9a-z]{1,13})-([0-9a-z]{1,25})\\.steam\\.?$", 2);
   private static final Pattern LEGACY_PATTERN = Pattern.compile("^e4steam-([0-9]{1,20})-([0-9a-f]{32})\\.steam\\.?$", 2);
   public static final int TOKEN_LENGTH = 16;
   private final long steamId;
   private final byte[] token;

   public SteamAddress(long steamId, byte[] token) {
      if (steamId == 0L) {
         throw new IllegalArgumentException("Steam ID must be non-zero");
      } else if (token.length != 16) {
         throw new IllegalArgumentException("Steam invite tokens must be 128 bits");
      } else {
         this.steamId = steamId;
         this.token = (byte[])token.clone();
      }
   }

   public long steamId() {
      return this.steamId;
   }

   public byte[] token() {
      return (byte[])this.token.clone();
   }

   public static Optional<SteamAddress> tryParse(String value) {
      if (value == null) {
         return Optional.empty();
      } else {
         String normalized = value.trim().toLowerCase(Locale.ROOT);
         Matcher shortMatcher = SHORT_PATTERN.matcher(normalized);
         if (shortMatcher.matches()) {
            return parseShort(shortMatcher);
         } else {
            Matcher legacyMatcher = LEGACY_PATTERN.matcher(normalized);
            return legacyMatcher.matches() ? parseLegacy(legacyMatcher) : Optional.empty();
         }
      }
   }

   private static Optional<SteamAddress> parseShort(Matcher matcher) {
      try {
         long steamId = Long.parseUnsignedLong(matcher.group(1), 36);
         if (steamId == 0L) {
            return Optional.empty();
         } else {
            byte[] token = decodeBase36Token(matcher.group(2));
            return Optional.of(new SteamAddress(steamId, token));
         }
      } catch (IllegalArgumentException var4) {
         return Optional.empty();
      }
   }

   private static Optional<SteamAddress> parseLegacy(Matcher matcher) {
      try {
         long steamId = Long.parseUnsignedLong(matcher.group(1));
         return steamId == 0L ? Optional.empty() : Optional.of(new SteamAddress(steamId, HexCodec.decode(matcher.group(2))));
      } catch (IllegalArgumentException var3) {
         return Optional.empty();
      }
   }

   private static byte[] decodeBase36Token(String encoded) {
      BigInteger value = new BigInteger(encoded, 36);
      if (value.signum() >= 0 && value.bitLength() <= 128) {
         byte[] compact = value.toByteArray();
         int sourceOffset = compact.length > 16 ? compact.length - 16 : 0;
         int copyLength = compact.length - sourceOffset;
         byte[] decoded = new byte[16];
         System.arraycopy(compact, sourceOffset, decoded, decoded.length - copyLength, copyLength);
         return decoded;
      } else {
         throw new IllegalArgumentException("Steam invite token exceeds 128 bits");
      }
   }

   public String inviteString() {
      return "s-" + Long.toUnsignedString(this.steamId, 36) + "-" + new BigInteger(1, this.token).toString(36) + ".steam";
   }

   @Override
   public String toString() {
      return "SteamAddress{steamId=" + Long.toUnsignedString(this.steamId) + ", token=<redacted>}";
   }

   @Override
   public boolean equals(Object other) {
      if (this == other) {
         return true;
      } else {
         return !(other instanceof SteamAddress that) ? false : this.steamId == that.steamId && Arrays.equals(this.token, that.token);
      }
   }

   @Override
   public int hashCode() {
      return 31 * Objects.hash(this.steamId) + Arrays.hashCode(this.token);
   }
}
