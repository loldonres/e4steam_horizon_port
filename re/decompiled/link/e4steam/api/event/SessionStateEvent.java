package link.e4steam.api.event;

import java.util.Objects;
import link.e4steam.api.session.SessionService;

public final class SessionStateEvent implements ApiEvent {
   public static final EventType<SessionStateEvent> TYPE = new EventType<>("e4steam:session_state", SessionStateEvent.class);
   private final long occurredAtEpochMillis;
   private final SessionService.SessionSnapshot snapshot;

   public SessionStateEvent(long occurredAtEpochMillis, SessionService.SessionSnapshot snapshot) {
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

   public SessionService.SessionSnapshot snapshot() {
      return this.snapshot;
   }
}
