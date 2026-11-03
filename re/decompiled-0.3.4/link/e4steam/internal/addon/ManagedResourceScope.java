package link.e4steam.internal.addon;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import link.e4steam.api.Registration;
import link.e4steam.api.ResourceScope;

public final class ManagedResourceScope implements ResourceScope {
   private final Object lock = new Object();
   private final List<Registration> children = new ArrayList<>();
   private final AtomicBoolean closed = new AtomicBoolean();

   @Override
   public <T extends Registration> T own(T child) {
      if (child == null) {
         throw new NullPointerException("child");
      } else {
         synchronized (this.lock) {
            if (!this.closed.get()) {
               this.children.add(child);
               return child;
            }
         }

         child.close();
         throw new IllegalStateException("Resource scope is closed");
      }
   }

   @Override
   public int openResourceCount() {
      synchronized (this.lock) {
         return this.children.size();
      }
   }

   @Override
   public boolean isClosed() {
      return this.closed.get();
   }

   @Override
   public void close() {
      if (this.closed.compareAndSet(false, true)) {
         List<Registration> closing;
         synchronized (this.lock) {
            closing = new ArrayList<>(this.children);
            this.children.clear();
         }

         RuntimeException first = null;

         for (int index = closing.size() - 1; index >= 0; index--) {
            try {
               closing.get(index).close();
            } catch (RuntimeException var6) {
               if (first == null) {
                  first = var6;
               } else {
                  first.addSuppressed(var6);
               }
            }
         }

         if (first != null) {
            throw first;
         }
      }
   }
}
