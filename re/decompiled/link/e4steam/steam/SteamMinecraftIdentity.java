package link.e4steam.steam;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class SteamMinecraftIdentity {
   private static final String UUID_NAMESPACE = "e4steam:steam-identity:v1:";
   private static final String NAME_PREFIX = "s_";

   private SteamMinecraftIdentity() {
   }

   public static UUID uuid(long steamId) {
      requireSteamId(steamId);
      String material = "e4steam:steam-identity:v1:" + Long.toUnsignedString(steamId);
      return UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8));
   }

   public static String safeName(long steamId) {
      requireSteamId(steamId);
      return "s_" + Long.toUnsignedString(steamId, 36);
   }

   public static String preserveMinecraftName(String minecraftName) {
      if (minecraftName == null) {
         throw new IllegalArgumentException("Minecraft name is required");
      } else {
         return minecraftName;
      }
   }

   public static String profileName(Object gameProfile) {
      if (gameProfile == null) {
         throw new IllegalArgumentException("Game profile is required");
      } else {
         Class<?> profileType = gameProfile.getClass();
         String[] var2 = new String[]{"name", "getName"};
         int var3 = var2.length;
         int var4 = 0;

         while (var4 < var3) {
            String accessorName = var2[var4];

            try {
               Method accessor = profileType.getMethod(accessorName);
               Object value = accessor.invoke(gameProfile);
               if (value instanceof String) {
                  return preserveMinecraftName((String)value);
               }

               throw new IllegalStateException("Game profile name accessor did not return a string");
            } catch (NoSuchMethodException var8) {
               var4++;
            } catch (IllegalAccessException var9) {
               throw new IllegalStateException("Game profile name accessor is not accessible", var9);
            } catch (InvocationTargetException var10) {
               throw new IllegalStateException("Game profile name accessor failed", var10.getCause());
            }
         }

         throw new IllegalStateException("Unsupported Authlib GameProfile: no name accessor");
      }
   }

   public static boolean allowSingleplayerOwnerBypass(long authenticatedSteamId, boolean vanillaOwnerMatch) {
      return authenticatedSteamId == 0L && vanillaOwnerMatch;
   }

   private static void requireSteamId(long steamId) {
      if (steamId == 0L) {
         throw new IllegalArgumentException("SteamID must be authenticated");
      }
   }
}
