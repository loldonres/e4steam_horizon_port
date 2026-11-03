package link.e4steam;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.function.Supplier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

final class DedicatedCommandCompat {
   private DedicatedCommandCompat() {
   }

   static void success(CommandSourceStack source, String text) {
      send(source, literal(text), true);
   }

   static void failure(CommandSourceStack source, String text) {
      Component message = literal(text);

      for (String name : new String[]{"sendFailure", "sendError", "method_9213", "m_81352_"}) {
         try {
            Method method = source.getClass().getMethod(name, Component.class);
            method.invoke(source, message);
            return;
         } catch (ReflectiveOperationException var8) {
         }
      }
   }

   private static void send(CommandSourceStack source, Component message, boolean broadcast) {
      for (String name : new String[]{"sendSuccess", "sendFeedback", "method_9226", "m_288197_"}) {
         try {
            Method method = source.getClass().getMethod(name, Component.class, boolean.class);
            method.invoke(source, message, broadcast);
            return;
         } catch (ReflectiveOperationException var9) {
            try {
               Method methodx = source.getClass().getMethod(name, Supplier.class, boolean.class);
               methodx.invoke(source, (Supplier<Component>)() -> message, broadcast);
               return;
            } catch (ReflectiveOperationException var8) {
            }
         }
      }
   }

   private static Component literal(String text) {
      for (String className : new String[]{"net.minecraft.network.chat.TextComponent", "net.minecraft.text.LiteralText", "net.minecraft.class_2585"}) {
         try {
            Class<?> type = Class.forName(className);
            Constructor<?> constructor = type.getConstructor(String.class);
            return (Component)constructor.newInstance(text);
         } catch (ClassCastException | ReflectiveOperationException var8) {
         }
      }

      for (String name : new String[]{"literal", "method_43470", "m_237113_"}) {
         try {
            Method method = Component.class.getMethod(name, String.class);
            return (Component)method.invoke(null, text);
         } catch (ClassCastException | ReflectiveOperationException var7) {
         }
      }

      throw new IllegalStateException("Could not create a server command message");
   }
}
