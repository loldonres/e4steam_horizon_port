package link.e4steam.steam;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.UUID;

public final class SteamMinecraftProfile {
   private SteamMinecraftProfile() {
   }

   public static <T> T bind(T original, long authenticatedSteamId) {
      if (authenticatedSteamId != 0L && original != null) {
         UUID uuid = SteamMinecraftIdentity.uuid(authenticatedSteamId);
         String name = SteamMinecraftIdentity.profileName(original);
         Class<?> type = original.getClass();

         try {
            Object properties = properties(original);

            for (Constructor<?> constructor : type.getConstructors()) {
               Class<?>[] args = constructor.getParameterTypes();
               if (args.length == 3 && args[0] == UUID.class && args[1] == String.class && properties != null && args[2].isInstance(properties)) {
                  return (T)constructor.newInstance(uuid, name, properties);
               }
            }

            T result = (T)type.getConstructor(UUID.class, String.class).newInstance(uuid, name);
            if (properties != null) {
               Object destination = properties(result);
               if (destination != null) {
                  for (Method method : destination.getClass().getMethods()) {
                     if (method.getName().equals("putAll") && method.getParameterTypes().length == 1 && method.getParameterTypes()[0].isInstance(properties)) {
                        method.invoke(destination, properties);
                        break;
                     }
                  }
               }
            }

            return result;
         } catch (RuntimeException | ReflectiveOperationException var14) {
            try {
               return (T)type.getConstructor(UUID.class, String.class).newInstance(uuid, name);
            } catch (ReflectiveOperationException var13) {
               throw new IllegalStateException("Cannot construct authenticated Minecraft profile", var13);
            }
         }
      } else {
         return original;
      }
   }

   private static Object properties(Object profile) throws ReflectiveOperationException {
      for (String name : new String[]{"properties", "getProperties"}) {
         try {
            return profile.getClass().getMethod(name).invoke(profile);
         } catch (NoSuchMethodException var6) {
         }
      }

      return null;
   }
}
