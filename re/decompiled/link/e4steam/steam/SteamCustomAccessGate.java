package link.e4steam.steam;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class SteamCustomAccessGate {
   private static final SteamCustomAccessGate.Provider DENY_ALL = new SteamCustomAccessGate.Provider() {
      @Override
      public boolean isRegistered(String modeId) {
         return false;
      }

      @Override
      public CompletionStage<Boolean> evaluate(String modeId, long remoteSteamId) {
         return CompletableFuture.completedFuture(Boolean.FALSE);
      }
   };
   private static final Object LOCK = new Object();
   private static volatile SteamCustomAccessGate.Provider provider = DENY_ALL;

   private SteamCustomAccessGate() {
   }

   public static AutoCloseable install(SteamCustomAccessGate.Provider replacement) {
      if (replacement == null) {
         throw new NullPointerException("replacement");
      } else {
         synchronized (LOCK) {
            if (provider != DENY_ALL) {
               throw new IllegalStateException("Custom access provider is already installed");
            }

            provider = replacement;
         }

         return new AutoCloseable() {
            @Override
            public void close() {
               synchronized (SteamCustomAccessGate.LOCK) {
                  if (SteamCustomAccessGate.provider == replacement) {
                     SteamCustomAccessGate.provider = SteamCustomAccessGate.DENY_ALL;
                  }
               }
            }
         };
      }
   }

   public static boolean isRegistered(String modeId) {
      if (!validModeId(modeId)) {
         return false;
      } else {
         try {
            return provider.isRegistered(modeId);
         } catch (RuntimeException var2) {
            return false;
         }
      }
   }

   public static CompletionStage<Boolean> evaluate(String modeId, long remoteSteamId) {
      if (validModeId(modeId) && remoteSteamId != 0L) {
         try {
            CompletionStage<Boolean> result = provider.evaluate(modeId, remoteSteamId);
            return (CompletionStage<Boolean>)(result == null ? CompletableFuture.completedFuture(Boolean.FALSE) : result);
         } catch (RuntimeException var4) {
            return CompletableFuture.completedFuture(Boolean.FALSE);
         }
      } else {
         return CompletableFuture.completedFuture(Boolean.FALSE);
      }
   }

   private static boolean validModeId(String value) {
      return value != null && value.matches("[a-z][a-z0-9_.-]{0,31}:[a-z][a-z0-9_.-]{0,63}");
   }

   public interface Provider {
      boolean isRegistered(String var1);

      CompletionStage<Boolean> evaluate(String var1, long var2);
   }
}
