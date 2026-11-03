package link.e4steam.steam;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SteamDedicatedAddress {
   private static final Pattern PATTERN = Pattern.compile("^d-([0-9a-z]{1,13})-([0-9a-z]{1,13})\\.steam\\.?$", 2);
   private final long steamId;
   private final long generation;

   public SteamDedicatedAddress(long steamId, long generation) {
      if (steamId == 0L) {
         throw new IllegalArgumentException("Steam ID must be non-zero");
      } else if (generation <= 0L) {
         throw new IllegalArgumentException("generation must be positive");
      } else {
         this.steamId = steamId;
         this.generation = generation;
      }
   }

   public long steamId() {
      return this.steamId;
   }

   public long generation() {
      return this.generation;
   }

   public String descriptor() {
      return "d-" + Long.toUnsignedString(this.steamId, 36) + "-" + Long.toUnsignedString(this.generation, 36) + ".steam";
   }

   public static Optional<SteamDedicatedAddress> tryParse(String value) {
      if (value == null) {
         return Optional.empty();
      } else {
         Matcher matcher = PATTERN.matcher(value.trim().toLowerCase(Locale.ROOT));
         if (!matcher.matches()) {
            return Optional.empty();
         } else {
            try {
               long steamId = Long.parseUnsignedLong(matcher.group(1), 36);
               long generation = Long.parseUnsignedLong(matcher.group(2), 36);
               return steamId != 0L && generation > 0L ? Optional.of(new SteamDedicatedAddress(steamId, generation)) : Optional.empty();
            } catch (IllegalArgumentException var6) {
               return Optional.empty();
            }
         }
      }
   }

   @Override
   public String toString() {
      return "SteamDedicatedAddress{steamId=" + Long.toUnsignedString(this.steamId) + ", generation=" + Long.toUnsignedString(this.generation) + "}";
   }

   @Override
   public boolean equals(Object other) {
      if (this == other) {
         return true;
      } else {
         return !(other instanceof SteamDedicatedAddress that) ? false : this.steamId == that.steamId && this.generation == that.generation;
      }
   }

   @Override
   public int hashCode() {
      return Objects.hash(this.steamId, this.generation);
   }
}
