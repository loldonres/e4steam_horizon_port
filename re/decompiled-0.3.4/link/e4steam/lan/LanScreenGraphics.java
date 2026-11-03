package link.e4steam.lan;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.gui.screens.Screen;

public final class LanScreenGraphics {
   private static final ConcurrentHashMap<String, Method> METHODS = new ConcurrentHashMap<>();
   private final Object context;

   public LanScreenGraphics(Object context) {
      this.context = context;
   }

   public void fill(int x1, int y1, int x2, int y2, int color) {
      if (!this.invokeIfPresent("fill,m_280509_,method_25294,m_93172_", new Object[]{x1, y1, x2, y2, color})) {
         throw new IllegalStateException("LAN fill hook missing");
      }
   }

   public void drawString(Object font, Object text, int x, int y, int color, boolean shadow) {
      color |= -16777216;
      String names = "drawString,m_280614_,method_51439,m_93243_,method_27535,method_25303";
      if (!this.invokeIfPresent(names, new Object[]{font, text, x, y, color, shadow})) {
         this.extractedText(text, x, y, color, false);
      }
   }

   public void drawCenteredString(Object font, Object text, int x, int y, int color) {
      color |= -16777216;
      String names = "drawCenteredString,m_280653_,method_27534,m_93215_";
      if (!this.invokeIfPresent(names, new Object[]{font, text, x, y, color})) {
         this.extractedText(text, x, y, color, true);
      }
   }

   private boolean invokeIfPresent(String names, Object[] args) {
      String key = this.context.getClass().getName() + names;
      Method method = METHODS.get(key);
      if (method == null) {
         method = this.find(this.context.getClass(), names, args, false);
         if (method == null) {
            method = this.find(Screen.class, names, args, true);
         }

         if (method == null) {
            return false;
         }

         method.setAccessible(true);
         METHODS.put(key, method);
      }

      try {
         boolean legacy = Modifier.isStatic(method.getModifiers());
         Object[] actual = this.arguments(args, legacy, method.getParameterCount());
         method.invoke(legacy ? null : this.context, actual);
         return true;
      } catch (ReflectiveOperationException var7) {
         throw new IllegalStateException("LAN rendering failed", var7);
      }
   }

   private void extractedText(Object text, int x, int y, int color, boolean centered) {
      try {
         ClassLoader loader = this.context.getClass().getClassLoader();
         Class<?> componentType = Class.forName("net.minecraft.network.chat.Component", true, loader);
         if (componentType.isInstance(text)) {
            String value = (String)componentType.getMethod("getString").invoke(text);
            Object styled = componentType.getMethod("literal", String.class).invoke(null, value);
            Class<?> styleType = Class.forName("net.minecraft.network.chat.Style", true, loader);
            Object style = styleType.getField("EMPTY").get(null);
            style = styleType.getMethod("withColor", int.class).invoke(style, color);
            styled.getClass().getMethod("setStyle", styleType).invoke(styled, style);
            Object collector = this.context.getClass().getMethod("textRenderer").invoke(this.context);
            Class<?> collectorType = Class.forName("net.minecraft.client.gui.ActiveTextCollector", true, loader);
            if (centered) {
               Class<?> alignmentType = Class.forName("net.minecraft.client.gui.TextAlignment", true, loader);
               Object center = alignmentType.getField("CENTER").get(null);
               Method accept = collectorType.getMethod("accept", alignmentType, int.class, int.class, componentType);
               accept.invoke(collector, center, x, y, styled);
            } else {
               Method accept = collectorType.getMethod("accept", int.class, int.class, componentType);
               accept.invoke(collector, x, y, styled);
            }
         }
      } catch (ReflectiveOperationException var17) {
         throw new IllegalStateException("LAN text extraction failed", var17);
      }
   }

   private Method find(Class<?> start, String names, Object[] args, boolean legacy) {
      for (String name : names.split(",")) {
         for (Class<?> type = start; type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
               if (method.getName().equals(name) && Modifier.isStatic(method.getModifiers()) == legacy) {
                  Object[] actual = this.arguments(args, legacy, method.getParameterCount());
                  if (actual.length == method.getParameterCount()) {
                     Class<?>[] types = method.getParameterTypes();
                     boolean match = true;

                     for (int i = 0; i < types.length; i++) {
                        if (!types[i].isInstance(actual[i])
                           && (types[i] != int.class || !(actual[i] instanceof Integer))
                           && (types[i] != boolean.class || !(actual[i] instanceof Boolean))) {
                           match = false;
                           break;
                        }
                     }

                     if (match) {
                        return method;
                     }
                  }
               }
            }
         }
      }

      return null;
   }

   private Object[] arguments(Object[] args, boolean legacy, int count) {
      int length = args.length;
      if (legacy && length > 0 && args[length - 1] instanceof Boolean) {
         length--;
      }

      Object[] actual = new Object[length + (legacy ? 1 : 0)];
      if (legacy) {
         actual[0] = this.context;
      }

      System.arraycopy(args, 0, actual, legacy ? 1 : 0, length);
      return actual;
   }
}
