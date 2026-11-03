package link.e4steam.skin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor.AbortPolicy;

public final class SkinRestoration {
   private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(0, 2, 30L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64), runnable -> {
      Thread thread = new Thread(runnable, "e4steam-skin-fetch");
      thread.setDaemon(true);
      return thread;
   }, new AbortPolicy());
   private static final Map<String, SkinRestoration.Cached> CACHE = new LinkedHashMap<String, SkinRestoration.Cached>(64, 0.75F, true) {
      @Override
      protected boolean removeEldestEntry(Entry<String, SkinRestoration.Cached> entry) {
         return this.size() > 512;
      }
   };

   private SkinRestoration() {
   }

   public static boolean externalProviderPresent() {
      for (ClassLoader loader : new ClassLoader[]{Thread.currentThread().getContextClassLoader(), SkinRestoration.class.getClassLoader()}) {
         try {
            Class.forName("net.lionarius.skinrestorer.SkinRestorer", false, loader);
            return true;
         } catch (LinkageError | ClassNotFoundException var5) {
         }
      }

      return false;
   }

   public static boolean hasTextures(Object profile) {
      try {
         Object map = properties(profile);
         return Boolean.TRUE.equals(map.getClass().getMethod("containsKey", Object.class).invoke(map, "textures"));
      } catch (RuntimeException | ReflectiveOperationException var2) {
         return true;
      }
   }

   public static CompletableFuture<SkinRestoration.Texture> fetch(String name) {
      if (name != null && name.matches("[A-Za-z0-9_]{1,16}")) {
         String key = name.toLowerCase(Locale.ROOT);
         synchronized (CACHE) {
            SkinRestoration.Cached cached = CACHE.get(key);
            if (cached != null && System.currentTimeMillis() < cached.expires) {
               return CompletableFuture.completedFuture(cached.texture);
            }
         }

         try {
            return CompletableFuture.supplyAsync(() -> {
               SkinRestoration.Texture texture = null;

               try {
                  JsonObject account = read("https://api.mojang.com/users/profiles/minecraft/" + name);
                  if (account != null && account.has("id")) {
                     String id = account.get("id").getAsString();
                     if (id.matches("[a-fA-F0-9]{32}")) {
                        texture = parseTexture(read("https://sessionserver.mojang.com/session/minecraft/profile/" + id + "?unsigned=false"));
                     }
                  }
               } catch (RuntimeException | IOException var7) {
               }

               synchronized (CACHE) {
                  CACHE.put(key, new SkinRestoration.Cached(texture, System.currentTimeMillis() + (texture == null ? '\uea60' : 3600000)));
                  return texture;
               }
            }, WORKER);
         } catch (RejectedExecutionException var5) {
            return CompletableFuture.completedFuture(null);
         }
      } else {
         return CompletableFuture.completedFuture(null);
      }
   }

   public static SkinRestoration.Texture parseTexture(JsonObject response) {
      if (response != null && response.has("properties")) {
         for (JsonElement element : response.getAsJsonArray("properties")) {
            JsonObject property = element.getAsJsonObject();
            if (property.has("name") && "textures".equals(property.get("name").getAsString()) && property.has("value") && property.has("signature")) {
               String value = property.get("value").getAsString();
               String signature = property.get("signature").getAsString();
               if (!value.isEmpty() && value.length() <= 32768 && !signature.isEmpty() && signature.length() <= 8192) {
                  return new SkinRestoration.Texture(value, signature);
               }

               return null;
            }
         }

         return null;
      } else {
         return null;
      }
   }

   public static void applyIfMissing(Object profile, SkinRestoration.Texture texture) {
      restoreIfMissing(profile, texture);
   }

   public static Object restoreIfMissing(Object profile, SkinRestoration.Texture texture) {
      if (texture != null && !externalProviderPresent() && !hasTextures(profile)) {
         try {
            Object map = properties(profile);
            ClassLoader loader = profile.getClass().getClassLoader();
            Class<?> propertyType = Class.forName("com.mojang.authlib.properties.Property", false, loader);
            Object property = propertyType.getConstructor(String.class, String.class, String.class).newInstance("textures", texture.value, texture.signature);

            for (Constructor<?> constructor : profile.getClass().getConstructors()) {
               Class<?>[] args = constructor.getParameterTypes();
               if (args.length == 3 && args[0] == UUID.class && args[1] == String.class && args[2].isInstance(map)) {
                  Class<?> multimap = Class.forName("com.google.common.collect.Multimap", false, loader);
                  Object copy = Class.forName("com.google.common.collect.HashMultimap", false, loader).getMethod("create").invoke(null);
                  multimap.getMethod("putAll", multimap).invoke(copy, map);
                  multimap.getMethod("put", Object.class, Object.class).invoke(copy, "textures", property);
                  Object restoredMap = args[2].getConstructor(multimap).newInstance(copy);
                  return constructor.newInstance(access(profile, "getId", "id"), access(profile, "getName", "name"), restoredMap);
               }
            }

            map.getClass().getMethod("put", Object.class, Object.class).invoke(map, "textures", property);
         } catch (RuntimeException | ReflectiveOperationException var14) {
         }

         return profile;
      } else {
         return profile;
      }
   }

   private static Object access(Object target, String... names) throws ReflectiveOperationException {
      for (String name : names) {
         try {
            return target.getClass().getMethod(name).invoke(target);
         } catch (NoSuchMethodException var7) {
         }
      }

      throw new NoSuchMethodException();
   }

   private static Object properties(Object profile) throws ReflectiveOperationException {
      for (String name : new String[]{"getProperties", "properties"}) {
         try {
            return profile.getClass().getMethod(name).invoke(profile);
         } catch (NoSuchMethodException var6) {
         }
      }

      throw new NoSuchMethodException("Profile properties");
   }

   private static JsonObject read(String address) throws IOException {
      HttpURLConnection connection = (HttpURLConnection)new URL(address).openConnection();
      connection.setConnectTimeout(2500);
      connection.setReadTimeout(2500);
      connection.setInstanceFollowRedirects(false);
      connection.setRequestProperty("User-Agent", "e4steam-skin-restoration/1");

      Object input;
      try {
         if (connection.getResponseCode() == 200) {
            try (
               InputStream inputx = connection.getInputStream();
               ByteArrayOutputStream output = new ByteArrayOutputStream();
            ) {
               byte[] buffer = new byte[4096];

               int count;
               while ((count = inputx.read(buffer)) != -1) {
                  if (output.size() + count > 65536) {
                     throw new IOException("Oversized skin response");
                  }

                  output.write(buffer, 0, count);
               }

               return new JsonParser().parse(new String(output.toByteArray(), StandardCharsets.UTF_8)).getAsJsonObject();
            }
         }

         input = null;
      } finally {
         connection.disconnect();
      }

      return (JsonObject)input;
   }

   private static final class Cached {
      final SkinRestoration.Texture texture;
      final long expires;

      Cached(SkinRestoration.Texture texture, long expires) {
         this.texture = texture;
         this.expires = expires;
      }
   }

   public static final class LoginState {
      private CompletableFuture<SkinRestoration.Texture> pending;
      private long deadline;
      private Object profile;

      public Object profile() {
         return this.profile;
      }

      public boolean prepare(Object original) {
         this.profile = original;
         if (original != null && !SkinRestoration.externalProviderPresent() && !SkinRestoration.hasTextures(original)) {
            try {
               String name = (String)SkinRestoration.access(original, "getName", "name");
               Object id = SkinRestoration.access(original, "getId", "id");
               if (id == null) {
                  this.profile = original.getClass()
                     .getConstructor(UUID.class, String.class)
                     .newInstance(UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)), name);
               }

               if (this.pending == null) {
                  this.deadline = System.nanoTime() + 12000000000L;
                  this.pending = SkinRestoration.fetch(name);
               }

               if (!this.pending.isDone() && System.nanoTime() < this.deadline) {
                  return false;
               }

               if (this.pending.isDone() && !this.pending.isCompletedExceptionally()) {
                  this.profile = SkinRestoration.restoreIfMissing(this.profile, this.pending.getNow(null));
               }
            } catch (RuntimeException | ReflectiveOperationException var4) {
            }

            return true;
         } else {
            return true;
         }
      }
   }

   public static final class Texture {
      public final String value;
      public final String signature;

      public Texture(String value, String signature) {
         this.value = value;
         this.signature = signature;
      }
   }
}
