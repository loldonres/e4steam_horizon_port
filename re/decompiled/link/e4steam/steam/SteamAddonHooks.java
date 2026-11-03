package link.e4steam.steam;

import java.util.concurrent.atomic.AtomicReference;

public final class SteamAddonHooks {
   private static final SteamAddonHooks.Delegate NOOP = new SteamAddonHooks.Delegate() {
      @Override
      public void bridgeReady(SteamConnectionBridge bridge) {
      }

      @Override
      public void bridgeClosed(SteamConnectionBridge bridge) {
      }

      @Override
      public void tick() {
      }

      @Override
      public void accept(SteamConnectionBridge bridge, byte frameType, byte[] payload) {
      }
   };
   private static final AtomicReference<SteamAddonHooks.Delegate> CURRENT = new AtomicReference<>(NOOP);

   private SteamAddonHooks() {
   }

   public static AutoCloseable install(SteamAddonHooks.Delegate delegate) {
      if (delegate == null) {
         throw new NullPointerException("delegate");
      } else if (!CURRENT.compareAndSet(NOOP, delegate)) {
         throw new IllegalStateException("Addon network hooks are already installed");
      } else {
         return new AutoCloseable() {
            @Override
            public void close() {
               SteamAddonHooks.CURRENT.compareAndSet(delegate, SteamAddonHooks.NOOP);
            }
         };
      }
   }

   static void bridgeReady(SteamConnectionBridge bridge) {
      CURRENT.get().bridgeReady(bridge);
   }

   static void bridgeClosed(SteamConnectionBridge bridge) {
      CURRENT.get().bridgeClosed(bridge);
   }

   static void tick() {
      CURRENT.get().tick();
   }

   static void accept(SteamConnectionBridge bridge, byte frameType, byte[] payload) {
      CURRENT.get().accept(bridge, frameType, payload);
   }

   public interface Delegate {
      void bridgeReady(SteamConnectionBridge var1);

      void bridgeClosed(SteamConnectionBridge var1);

      void tick();

      void accept(SteamConnectionBridge var1, byte var2, byte[] var3);
   }
}
