package link.e4steam.internal.api;

import java.util.concurrent.atomic.AtomicBoolean;
import link.e4steam.api.Registration;

class CoreRegistration implements Registration {
   private final AtomicBoolean closed = new AtomicBoolean();
   private final Runnable closeAction;

   CoreRegistration(Runnable closeAction) {
      this.closeAction = closeAction;
   }

   @Override
   public void close() {
      if (this.closed.compareAndSet(false, true) && this.closeAction != null) {
         this.closeAction.run();
      }
   }

   @Override
   public boolean isClosed() {
      return this.closed.get();
   }
}
