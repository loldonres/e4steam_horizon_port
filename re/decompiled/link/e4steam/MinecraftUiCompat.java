package link.e4steam;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

public final class MinecraftUiCompat {
   private static final String[] MESSAGE_SCREEN_CLASS_NAMES = new String[]{
      "net.minecraft.client.gui.screens.GenericDirtMessageScreen", "net.minecraft.client.gui.screens.GenericMessageScreen", "net.minecraft.class_424"
   };
   private static final String[] DISCONNECT_METHOD_NAMES = new String[]{"disconnect", "clearLevel", "method_18099", "method_18096", "m_91387_", "m_91398_"};

   private MinecraftUiCompat() {
   }

   public static Button button(Component message, Consumer<Button> onPress, int x, int y, int width, int height) {
      Button fromBuilder = tryBuilder(message, onPress, x, y, width, height);
      if (fromBuilder != null) {
         return fromBuilder;
      } else {
         Button fromConstructor = tryConstructor(message, onPress, x, y, width, height);
         if (fromConstructor != null) {
            return fromConstructor;
         } else {
            throw new IllegalStateException("No compatible Minecraft Button factory was found");
         }
      }
   }

   public static void tooltip(Object widget, Component message) {
      if (widget != null && message != null) {
         for (Method setter : widget.getClass().getMethods()) {
            if (setter.getParameterCount() == 1 && !Modifier.isStatic(setter.getModifiers())) {
               Class<?> tooltipType = setter.getParameterTypes()[0];
               Object tooltip = createTooltip(tooltipType, message);
               if (tooltip != null) {
                  try {
                     setter.invoke(widget, tooltip);
                     return;
                  } catch (ReflectiveOperationException var9) {
                  }
               }
            }
         }
      }
   }

   public static Screen messageScreen(Component message, Screen fallback) {
      for (String className : MESSAGE_SCREEN_CLASS_NAMES) {
         try {
            Class<?> screenType = Class.forName(className, false, Screen.class.getClassLoader());
            if (Screen.class.isAssignableFrom(screenType)) {
               for (Constructor<?> constructor : screenType.getDeclaredConstructors()) {
                  Class<?>[] parameterTypes = constructor.getParameterTypes();
                  if (parameterTypes.length == 1 && parameterTypes[0].isAssignableFrom(message.getClass())) {
                     constructor.setAccessible(true);
                     return (Screen)constructor.newInstance(message);
                  }
               }
            }
         } catch (ClassNotFoundException var12) {
         } catch (RuntimeException | ReflectiveOperationException var13) {
            break;
         }
      }

      return fallback;
   }

   public static Screen currentScreen(Minecraft minecraft) {
      try {
         return minecraft.screen;
      } catch (NoSuchFieldError var4) {
         try {
            Method getter = minecraft.gui.getClass().getMethod("screen");
            return (Screen)getter.invoke(minecraft.gui);
         } catch (ReflectiveOperationException var3) {
            throw new IllegalStateException("No compatible current-screen accessor was found", var3);
         }
      }
   }

   public static void setScreen(Minecraft minecraft, Screen screen) {
      try {
         minecraft.setScreen(screen);
      } catch (NoSuchMethodError var5) {
         try {
            Method setter = minecraft.getClass().getMethod("setScreenAndShow", Screen.class);
            setter.invoke(minecraft, screen);
         } catch (ReflectiveOperationException var4) {
            throw new IllegalStateException("No compatible screen setter was found", var4);
         }
      }
   }

   public static void addChatMessage(Minecraft minecraft, Component message) {
      try {
         minecraft.gui.getChat().addMessage(message);
      } catch (NoSuchMethodError var7) {
         try {
            Object hud = minecraft.gui.getClass().getField("hud").get(minecraft.gui);
            Object chat = hud.getClass().getMethod("getChat").invoke(hud);
            Method addMessage = Arrays.stream(chat.getClass().getMethods())
               .filter(method -> method.getName().equals("addClientSystemMessage") || method.getName().equals("addMessage"))
               .filter(method -> method.getParameterCount() == 1)
               .filter(method -> method.getParameterTypes()[0].isAssignableFrom(message.getClass()))
               .findFirst()
               .orElseThrow(() -> new NoSuchMethodException("ChatComponent system-message method"));
            addMessage.invoke(chat, message);
         } catch (ReflectiveOperationException var6) {
            throw new IllegalStateException("No compatible chat message API was found", var6);
         }
      }
   }

   public static void connect(Screen parent, Minecraft minecraft, ServerAddress address, String displayName, String endpoint) throws ReflectiveOperationException {
      Object serverData = createServerData(displayName, endpoint);
      Method[] methods = ConnectScreen.class.getDeclaredMethods();
      Arrays.sort(
         methods, Comparator.<Method>comparingInt(methodx -> Modifier.isPublic(methodx.getModifiers()) ? 0 : 1).thenComparingInt(Method::getParameterCount)
      );

      for (Method method : methods) {
         if (Modifier.isStatic(method.getModifiers()) && method.getReturnType() == void.class) {
            Class<?>[] types = method.getParameterTypes();
            if (containsAssignable(types, Screen.class)
               && containsAssignable(types, Minecraft.class)
               && containsAssignable(types, ServerAddress.class)
               && containsAssignable(types, ServerData.class)) {
               Object[] arguments = new Object[types.length];
               boolean valid = true;

               for (int i = 0; i < types.length; i++) {
                  Class<?> type = types[i];
                  if (type.isInstance(parent) || type == Screen.class) {
                     arguments[i] = parent;
                  } else if (type.isInstance(minecraft) || type == Minecraft.class) {
                     arguments[i] = minecraft;
                  } else if (type.isInstance(address) || type == ServerAddress.class) {
                     arguments[i] = address;
                  } else if (type.isInstance(serverData) || type == ServerData.class) {
                     arguments[i] = serverData;
                  } else if (type == boolean.class) {
                     arguments[i] = false;
                  } else if (type == int.class) {
                     arguments[i] = 0;
                  } else if (type == long.class) {
                     arguments[i] = 0L;
                  } else if (type == Optional.class) {
                     arguments[i] = Optional.empty();
                  } else {
                     if (type.isPrimitive()) {
                        valid = false;
                        break;
                     }

                     arguments[i] = null;
                  }
               }

               if (valid) {
                  method.setAccessible(true);
                  method.invoke(null, arguments);
                  return;
               }
            }
         }
      }

      throw new NoSuchMethodException("No compatible ConnectScreen entry point was found");
   }

   public static void disconnect(Minecraft minecraft, Screen nextScreen) throws ReflectiveOperationException {
      for (String name : DISCONNECT_METHOD_NAMES) {
         for (Method method : Minecraft.class.getMethods()) {
            if (method.getName().equals(name)
               && !Modifier.isStatic(method.getModifiers())
               && method.getReturnType() == void.class
               && method.getParameterCount() == 1
               && method.getParameterTypes()[0].isAssignableFrom(nextScreen.getClass())) {
               method.invoke(minecraft, nextScreen);
               return;
            }
         }
      }

      throw new NoSuchMethodException("No compatible Minecraft disconnect method was found");
   }

   private static Button tryBuilder(Component message, Consumer<Button> onPress, int x, int y, int width, int height) {
      for (Method factory : Button.class.getDeclaredMethods()) {
         if (Modifier.isStatic(factory.getModifiers()) && factory.getParameterCount() == 2) {
            Class<?>[] parameters = factory.getParameterTypes();
            if ((parameters[0].isAssignableFrom(message.getClass()) || parameters[0].isAssignableFrom(Component.class)) && parameters[1].isInterface()) {
               Object callback = callback(parameters[1], onPress, message);

               try {
                  factory.setAccessible(true);
                  Object builder = factory.invoke(null, message, callback);
                  if (builder != null && !(builder instanceof Button)) {
                     Method bounds = Arrays.stream(builder.getClass().getMethods())
                        .filter(method -> !Modifier.isStatic(method.getModifiers()))
                        .filter(method -> method.getParameterCount() == 4)
                        .filter(method -> Arrays.stream(method.getParameterTypes()).allMatch(type -> type == int.class))
                        .filter(
                           method -> method.getReturnType().isAssignableFrom(builder.getClass()) || builder.getClass().isAssignableFrom(method.getReturnType())
                        )
                        .findFirst()
                        .orElse(null);
                     if (bounds != null) {
                        bounds.invoke(builder, x, y, width, height);
                        Method build = Arrays.stream(builder.getClass().getMethods())
                           .filter(method -> !Modifier.isStatic(method.getModifiers()))
                           .filter(method -> method.getParameterCount() == 0)
                           .filter(method -> Button.class.isAssignableFrom(method.getReturnType()))
                           .findFirst()
                           .orElse(null);
                        if (build != null) {
                           return (Button)build.invoke(builder);
                        }
                     }
                  }
               } catch (RuntimeException | ReflectiveOperationException var15) {
               }
            }
         }
      }

      return null;
   }

   private static Object createServerData(String displayName, String endpoint) throws ReflectiveOperationException {
      Constructor<?>[] constructors = ServerData.class.getDeclaredConstructors();
      Arrays.sort(constructors, Comparator.comparingInt(Constructor::getParameterCount));
      ReflectiveOperationException lastFailure = null;

      for (Constructor<?> constructor : constructors) {
         Class<?>[] types = constructor.getParameterTypes();
         if (types.length >= 2 && types[0] == String.class && types[1] == String.class) {
            Object[] arguments = new Object[types.length];
            arguments[0] = displayName;
            arguments[1] = endpoint;

            for (int i = 2; i < types.length; i++) {
               Class<?> type = types[i];
               if (type == boolean.class) {
                  arguments[i] = false;
               } else if (type == int.class) {
                  arguments[i] = 0;
               } else if (type == long.class) {
                  arguments[i] = 0L;
               } else if (type.isEnum()) {
                  Object[] constants = type.getEnumConstants();
                  Object selected = null;

                  for (Object constant : constants) {
                     if (((Enum)constant).name().equals("OTHER")) {
                        selected = constant;
                        break;
                     }
                  }

                  arguments[i] = selected != null ? selected : (constants.length == 0 ? null : constants[0]);
               } else if (type == Optional.class) {
                  arguments[i] = Optional.empty();
               } else {
                  arguments[i] = null;
               }
            }

            try {
               constructor.setAccessible(true);
               return constructor.newInstance(arguments);
            } catch (ReflectiveOperationException var18) {
               lastFailure = var18;
            }
         }
      }

      if (lastFailure != null) {
         throw lastFailure;
      } else {
         throw new NoSuchMethodException("No compatible ServerData constructor was found");
      }
   }

   private static boolean containsAssignable(Class<?>[] haystack, Class<?> needle) {
      for (Class<?> type : haystack) {
         if (type != Object.class && (type.isAssignableFrom(needle) || needle.isAssignableFrom(type))) {
            return true;
         }
      }

      return false;
   }

   private static Button tryConstructor(Component message, Consumer<Button> onPress, int x, int y, int width, int height) {
      Constructor<?>[] constructors = Button.class.getDeclaredConstructors();
      Arrays.sort(constructors, Comparator.comparingInt(Constructor::getParameterCount));

      for (Constructor<?> constructor : constructors) {
         Class<?>[] parameterTypes = constructor.getParameterTypes();
         if (parameterTypes.length >= 6
            && parameterTypes[0] == int.class
            && parameterTypes[1] == int.class
            && parameterTypes[2] == int.class
            && parameterTypes[3] == int.class
            && Component.class.isAssignableFrom(parameterTypes[4])
            && parameterTypes[5].isInterface()) {
            Object[] arguments = new Object[parameterTypes.length];
            arguments[0] = x;
            arguments[1] = y;
            arguments[2] = width;
            arguments[3] = height;
            arguments[4] = message;
            arguments[5] = callback(parameterTypes[5], onPress, message);

            for (int i = 6; i < parameterTypes.length; i++) {
               arguments[i] = defaultValue(parameterTypes[i], message);
            }

            try {
               constructor.setAccessible(true);
               return (Button)constructor.newInstance(arguments);
            } catch (RuntimeException | ReflectiveOperationException var14) {
            }
         }
      }

      return null;
   }

   private static Object createTooltip(Class<?> tooltipType, Component message) {
      for (Method method : tooltipType.getDeclaredMethods()) {
         if (Modifier.isStatic(method.getModifiers())
            && method.getParameterCount() == 1
            && tooltipType.isAssignableFrom(method.getReturnType())
            && method.getParameterTypes()[0].isAssignableFrom(message.getClass())) {
            try {
               method.setAccessible(true);
               return method.invoke(null, message);
            } catch (RuntimeException | ReflectiveOperationException var9) {
            }
         }
      }

      for (Constructor<?> constructor : tooltipType.getDeclaredConstructors()) {
         Class<?>[] parameterTypes = constructor.getParameterTypes();
         if (parameterTypes.length == 1 && parameterTypes[0].isAssignableFrom(message.getClass())) {
            try {
               constructor.setAccessible(true);
               return constructor.newInstance(message);
            } catch (RuntimeException | ReflectiveOperationException var8) {
               return null;
            }
         }
      }

      return null;
   }

   private static Object callback(Class<?> callbackType, Consumer<Button> onPress, Component message) {
      return Proxy.newProxyInstance(
         callbackType.getClassLoader(),
         new Class[]{callbackType},
         (proxy, method, args) -> {
            // $VF: Couldn't be decompiled
            // Please report this to the Vineflower issue tracker, at https://github.com/Vineflower/vineflower/issues with a copy of the class file (if you have the rights to distribute it!)
            // java.lang.RuntimeException: invalid constant type: Ljava/lang/Object; with value e4steam Steam button callback
            //   at org.jetbrains.java.decompiler.modules.decompiler.exps.ConstExprent.toJava(ConstExprent.java:356)
            //   at org.jetbrains.java.decompiler.modules.decompiler.exps.SwitchExprent.toJava(SwitchExprent.java:152)
            //   at org.jetbrains.java.decompiler.modules.decompiler.ExprProcessor.getCastedExprent(ExprProcessor.java:1014)
            //   at org.jetbrains.java.decompiler.modules.decompiler.exps.ExitExprent.toJava(ExitExprent.java:86)
            //   at org.jetbrains.java.decompiler.modules.decompiler.ExprProcessor.listToJava(ExprProcessor.java:891)
            //   at org.jetbrains.java.decompiler.modules.decompiler.stats.BasicBlockStatement.toJava(BasicBlockStatement.java:91)
            //   at org.jetbrains.java.decompiler.modules.decompiler.ExprProcessor.jmpWrapper(ExprProcessor.java:829)
            //   at org.jetbrains.java.decompiler.modules.decompiler.stats.SequenceStatement.toJava(SequenceStatement.java:107)
            //   at org.jetbrains.java.decompiler.modules.decompiler.ExprProcessor.jmpWrapper(ExprProcessor.java:829)
            //   at org.jetbrains.java.decompiler.modules.decompiler.stats.IfStatement.toJava(IfStatement.java:238)
            //   at org.jetbrains.java.decompiler.modules.decompiler.stats.RootStatement.toJava(RootStatement.java:36)
            //   at org.jetbrains.java.decompiler.main.ClassWriter.methodLambdaToJava(ClassWriter.java:971)
            //
            // Bytecode:
            // 00: aload 3
            // 01: invokevirtual java/lang/reflect/Method.getDeclaringClass ()Ljava/lang/Class;
            // 04: ldc java/lang/Object
            // 06: if_acmpne b2
            // 09: aload 3
            // 0a: invokevirtual java/lang/reflect/Method.getName ()Ljava/lang/String;
            // 0d: astore 5
            // 0f: bipush -1
            // 10: istore 6
            // 12: aload 5
            // 14: invokevirtual java/lang/String.hashCode ()I
            // 17: lookupswitch 81 3 -1776922004 33 -1295482945 67 147696667 50
            // 38: aload 5
            // 3a: ldc_w "toString"
            // 3d: invokevirtual java/lang/String.equals (Ljava/lang/Object;)Z
            // 40: ifeq 68
            // 43: bipush 0
            // 44: istore 6
            // 46: goto 68
            // 49: aload 5
            // 4b: ldc_w "hashCode"
            // 4e: invokevirtual java/lang/String.equals (Ljava/lang/Object;)Z
            // 51: ifeq 68
            // 54: bipush 1
            // 55: istore 6
            // 57: goto 68
            // 5a: aload 5
            // 5c: ldc_w "equals"
            // 5f: invokevirtual java/lang/String.equals (Ljava/lang/Object;)Z
            // 62: ifeq 68
            // 65: bipush 2
            // 66: istore 6
            // 68: iload 6
            // 6a: tableswitch 70 0 2 26 32 42
            // 84: ldc_w "e4steam Steam button callback"
            // 87: goto b1
            // 8a: aload 2
            // 8b: invokestatic java/lang/System.identityHashCode (Ljava/lang/Object;)I
            // 8e: invokestatic java/lang/Integer.valueOf (I)Ljava/lang/Integer;
            // 91: goto b1
            // 94: aload 2
            // 95: aload 4
            // 97: ifnonnull 9e
            // 9a: aconst_null
            // 9b: goto a2
            // 9e: aload 4
            // a0: bipush 0
            // a1: aaload
            // a2: if_acmpne a9
            // a5: bipush 1
            // a6: goto aa
            // a9: bipush 0
            // aa: invokestatic java/lang/Boolean.valueOf (Z)Ljava/lang/Boolean;
            // ad: goto b1
            // b0: aconst_null
            // b1: areturn
            // b2: aload 4
            // b4: ifnull f1
            // b7: aload 4
            // b9: astore 5
            // bb: aload 5
            // bd: arraylength
            // be: istore 6
            // c0: bipush 0
            // c1: istore 7
            // c3: iload 7
            // c5: iload 6
            // c7: if_icmpge f1
            // ca: aload 5
            // cc: iload 7
            // ce: aaload
            // cf: astore 8
            // d1: aload 8
            // d3: instanceof net/minecraft/client/gui/components/Button
            // d6: ifeq eb
            // d9: aload 8
            // db: checkcast net/minecraft/client/gui/components/Button
            // de: astore 9
            // e0: aload 0
            // e1: aload 9
            // e3: invokeinterface java/util/function/Consumer.accept (Ljava/lang/Object;)V 2
            // e8: goto f1
            // eb: iinc 7 1
            // ee: goto c3
            // f1: aload 3
            // f2: invokevirtual java/lang/reflect/Method.getReturnType ()Ljava/lang/Class;
            // f5: aload 1
            // f6: invokestatic link/e4steam/MinecraftUiCompat.defaultValue (Ljava/lang/Class;Lnet/minecraft/network/chat/Component;)Ljava/lang/Object;
            // f9: areturn
         }
      );
   }

   private static Object defaultValue(Class<?> type, Component message) {
      if (!type.isPrimitive()) {
         if (type.isAssignableFrom(message.getClass()) || type.isAssignableFrom(Component.class)) {
            return message;
         } else {
            return type.isInterface()
               ? Proxy.newProxyInstance(type.getClassLoader(), new Class[]{type}, (proxy, method, args) -> defaultValue(method.getReturnType(), message))
               : null;
         }
      } else if (type == boolean.class) {
         return false;
      } else if (type == char.class) {
         return '\u0000';
      } else if (type == byte.class) {
         return (byte)0;
      } else if (type == short.class) {
         return (short)0;
      } else if (type == int.class) {
         return 0;
      } else if (type == long.class) {
         return 0L;
      } else if (type == float.class) {
         return 0.0F;
      } else {
         return type == double.class ? 0.0 : null;
      }
   }
}
