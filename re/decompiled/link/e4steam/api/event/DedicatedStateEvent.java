package link.e4steam.api.event;

import java.util.Objects;
import link.e4steam.api.dedicated.DedicatedServerService;

public final class DedicatedStateEvent implements ApiEvent {
   public static final EventType<DedicatedStateEvent> TYPE = new EventType<>("e4steam:dedicated_state", DedicatedStateEvent.class);
   private final long occurredAtEpochMillis;
   private final DedicatedServerService.DedicatedServerSnapshot snapshot;

   public DedicatedStateEvent(long occurredAtEpochMillis, DedicatedServerService.DedicatedServerSnapshot snapshot) {
      if (occurredAtEpochMillis < 0L) {
         throw new IllegalArgumentException("invalid time");
      } else {
         this.occurredAtEpochMillis = occurredAtEpochMillis;
         this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
      }
   }

   @Override
   public long occurredAtEpochMillis() {
      return this.occurredAtEpochMillis;
   }

   public DedicatedServerService.DedicatedServerSnapshot snapshot() {
      return this.snapshot;
   }
}
