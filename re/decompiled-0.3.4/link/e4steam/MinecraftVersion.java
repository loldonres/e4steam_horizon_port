package link.e4steam;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.regex.Pattern;
import net.minecraft.SharedConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MinecraftVersion {
   private static final Logger LOGGER = LoggerFactory.getLogger("e4steam");
   private static final Pattern RELEASE_NAME = Pattern.compile("^(?:1\\.\\d+(?:\\.\\d+)?|\\d{2}\\.\\d+(?:\\.\\d+)?)$");

   private MinecraftVersion() {
   }

   public static String current() {
      for (Method method : SharedConstants.class.getDeclaredMethods()) {
         if (Modifier.isStatic(method.getModifiers())
            && method.getParameterCount() == 0
            && !method.getReturnType().isPrimitive()
            && method.getReturnType() != String.class) {
            try {
               method.setAccessible(true);
               Object candidate = method.invoke(null);
               String name = findReleaseName(candidate);
               if (name != null) {
                  return name;
               }
            } catch (RuntimeException | ReflectiveOperationException var6) {
            }
         }
      }

      LOGGER.warn("Could not determine the running Minecraft version");
      return "unknown";
   }

   private static String findReleaseName(Object version) {
      if (version == null) {
         return null;
      } else {
         for (Method method : version.getClass().getMethods()) {
            if (method.getParameterCount() == 0 && method.getReturnType() == String.class) {
               try {
                  if (method.invoke(version) instanceof String text && RELEASE_NAME.matcher(text).matches()) {
                     return text;
                  }
               } catch (RuntimeException | ReflectiveOperationException var7) {
               }
            }
         }

         return null;
      }
   }
}
