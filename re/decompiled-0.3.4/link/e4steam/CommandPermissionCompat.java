package link.e4steam;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

final class CommandPermissionCompat {
   private CommandPermissionCompat() {
   }

   static boolean hasPermission(Object source, int level) {
      if (source == null) {
         return false;
      } else if (level <= 0) {
         return true;
      } else {
         Boolean legacy = invokeLegacyPermission(source, level);
         if (legacy != null) {
            return legacy;
         } else {
            Boolean modern = invokeModernPermission(source, level);
            return modern != null ? modern : false;
         }
      }
   }

   private static Boolean invokeLegacyPermission(Object source, int level) {
      try {
         Method method = source.getClass().getMethod("hasPermission", int.class);
         return (Boolean)method.invoke(source, level);
      } catch (NoSuchMethodException var3) {
         return null;
      } catch (InvocationTargetException | ClassCastException | IllegalAccessException var4) {
         return false;
      }
   }

   private static Boolean invokeModernPermission(Object source, int level) {
      Object permissionSet = invokeNoArgs(source, "permissions");
      if (permissionSet == null) {
         return null;
      } else {
         try {
            ClassLoader loader = source.getClass().getClassLoader();
            Class<?> permissionsClass = Class.forName("net.minecraft.server.permissions.Permissions", true, loader);
            String fieldName;
            if (level >= 4) {
               fieldName = "COMMANDS_OWNER";
            } else if (level == 3) {
               fieldName = "COMMANDS_ADMIN";
            } else if (level == 2) {
               fieldName = "COMMANDS_GAMEMASTER";
            } else {
               fieldName = "COMMANDS_MODERATOR";
            }

            Field field = permissionsClass.getField(fieldName);
            Object permission = field.get(null);

            for (Method method : permissionSet.getClass().getMethods()) {
               if (method.getName().equals("hasPermission") && method.getParameterCount() == 1 && method.getParameterTypes()[0].isInstance(permission)) {
                  return (Boolean)method.invoke(permissionSet, permission);
               }
            }

            return null;
         } catch (NoSuchFieldException | ClassNotFoundException var12) {
            return null;
         } catch (InvocationTargetException | ClassCastException | IllegalAccessException var13) {
            return false;
         }
      }
   }

   private static Object invokeNoArgs(Object target, String methodName) {
      try {
         return target.getClass().getMethod(methodName).invoke(target);
      } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException var3) {
         return null;
      }
   }
}
