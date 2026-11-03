package link.e4steam.api.event;

public final class RuntimeStoppingEvent implements ApiEvent {
   public static final EventType<RuntimeStoppingEvent> TYPE = new EventType<>("e4steam:runtime_stopping", RuntimeStoppingEvent.class);
   private final long occurredAtEpochMillis;

   public RuntimeStoppingEvent(long occurredAtEpochMillis) {
      if (occurredAtEpochMillis < 0L) {
         throw new IllegalArgumentException("occurredAtEpochMillis must be non-negative");
      } else {
         this.occurredAtEpochMillis = occurredAtEpochMillis;
      }
   }

   @Override
   public long occurredAtEpochMillis() {
      return this.occurredAtEpochMillis;
   }
}
