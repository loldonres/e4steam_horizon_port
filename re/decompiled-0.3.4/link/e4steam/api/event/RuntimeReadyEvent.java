package link.e4steam.api.event;

import java.util.Objects;
import link.e4steam.api.runtime.RuntimeSnapshot;

public final class RuntimeReadyEvent implements ApiEvent {
   public static final EventType<RuntimeReadyEvent> TYPE = new EventType<>("e4steam:runtime_ready", RuntimeReadyEvent.class);
   private final long occurredAtEpochMillis;
   private final RuntimeSnapshot snapshot;

   public RuntimeReadyEvent(long occurredAtEpochMillis, RuntimeSnapshot snapshot) {
      if (occurredAtEpochMillis < 0L) {
         throw new IllegalArgumentException("occurredAtEpochMillis must be non-negative");
      } else {
         this.occurredAtEpochMillis = occurredAtEpochMillis;
         this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
      }
   }

   @Override
   public long occurredAtEpochMillis() {
      return this.occurredAtEpochMillis;
   }

   public RuntimeSnapshot snapshot() {
      return this.snapshot;
   }
}
