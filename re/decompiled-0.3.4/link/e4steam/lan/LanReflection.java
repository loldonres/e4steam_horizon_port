package link.e4steam.lan;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;

public final class LanReflection {
   private LanReflection() {
   }

   public static Method method(Object owner, int parameters, String... names) {
      for (String name : names) {
         for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
               if (method.getName().equals(name) && method.getParameterTypes().length == parameters) {
                  method.setAccessible(true);
                  return method;
               }
            }
         }
      }

      return null;
   }

   public static Field field(Object owner, String... names) {
      for (String name : names) {
         for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            try {
               Field field = type.getDeclaredField(name);
               field.setAccessible(true);
               return field;
            } catch (NoSuchFieldException var8) {
            }
         }
      }

      return null;
   }

   public static Object call(Object owner, Method method, Object... arguments) {
      if (method == null) {
         throw new IllegalStateException("Required LAN server hook is missing");
      } else {
         try {
            return method.invoke(owner, arguments);
         } catch (InvocationTargetException var4) {
            throw new IllegalStateException("LAN server hook failed: " + method.getName(), var4.getCause());
         } catch (ReflectiveOperationException var5) {
            throw new IllegalStateException("Cannot call LAN server hook: " + method.getName(), var5);
         }
      }
   }

   public static Object read(Object owner, String... names) {
      Method getter = method(owner, 0, names);
      if (getter != null) {
         return call(owner, getter);
      } else {
         Field field = field(owner, names);
         if (field == null) {
            throw new IllegalStateException("Missing LAN member: " + Arrays.toString((Object[])names));
         } else {
            try {
               return field.get(owner);
            } catch (IllegalAccessException var5) {
               throw new IllegalStateException("Cannot read LAN member", var5);
            }
         }
      }
   }

   public static void write(Object owner, Field field, Object value) {
      if (field == null) {
         throw new IllegalStateException("Required LAN server field is missing");
      } else {
         try {
            field.set(owner, value);
         } catch (IllegalAccessException var4) {
            throw new IllegalStateException("Cannot write LAN member", var4);
         }
      }
   }

   public static Object gameMode(Class<?> type, String name) {
      int id = Arrays.asList("survival", "creative", "adventure", "spectator").indexOf(name);

      for (Object constant : type.getEnumConstants()) {
         if (((Enum)constant).name().equalsIgnoreCase(name)) {
            return constant;
         }

         Method getter = method(constant, 0, "getId", "getID", "method_8379", "func_77148_a", "m_46392_");
         if (getter != null && Integer.valueOf(id).equals(call(constant, getter))) {
            return constant;
         }
      }

      throw new IllegalArgumentException("Game mode is unavailable: " + name);
   }
}
