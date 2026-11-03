package link.e4steam.api.event;

import link.e4steam.api.ApiValidation;

public final class LifecycleEvent implements ApiEvent {
   public static final EventType<LifecycleEvent> TYPE = new EventType<>("e4steam:lifecycle", LifecycleEvent.class);
   private final long occurredAtEpochMillis;
   private final LifecycleEvent.Kind kind;
   private final String subjectId;
   private final String safeCode;

   public LifecycleEvent(long occurredAtEpochMillis, LifecycleEvent.Kind kind, String subjectId, String safeCode) {
      if (occurredAtEpochMillis < 0L) {
         throw new IllegalArgumentException("invalid time");
      } else if (kind == null) {
         throw new NullPointerException("kind");
      } else {
         this.occurredAtEpochMillis = occurredAtEpochMillis;
         this.kind = kind;
         this.subjectId = ApiValidation.optionalText(subjectId, "subjectId", 96);
         this.safeCode = ApiValidation.optionalText(safeCode, "safeCode", 96);
         ApiValidation.rejectSensitiveName(this.subjectId, "subjectId");
      }
   }

   @Override
   public long occurredAtEpochMillis() {
      return this.occurredAtEpochMillis;
   }

   public LifecycleEvent.Kind kind() {
      return this.kind;
   }

   public String subjectId() {
      return this.subjectId;
   }

   public String safeCode() {
      return this.safeCode;
   }

   @Override
   public String toString() {
      return "LifecycleEvent{kind=" + this.kind + ", subject='" + this.subjectId + "', code='" + this.safeCode + "'}";
   }

   public static enum Kind {
      RUNTIME_FAILURE,
      ADDON_ACTIVATED,
      ADDON_DISABLED,
      ADDON_FAILED,
      WORLD_OPENING,
      WORLD_OPENED,
      WORLD_CLOSING,
      WORLD_CLOSED,
      LOBBY_CREATED,
      LOBBY_UPDATED,
      LOBBY_JOINED,
      LOBBY_LEFT,
      PEER_DISCOVERED,
      PEER_AUTHENTICATING,
      PEER_ACCEPTED,
      PEER_REJECTED,
      PEER_DISCONNECTED,
      ACCESS_EVALUATION_STARTED,
      ACCESS_EVALUATION_COMPLETED,
      CHANNEL_NEGOTIATED,
      CHANNEL_UNAVAILABLE,
      CHANNEL_CLOSED,
      CONFIG_CHANGED,
      WORLD_SETTINGS_PLAN_CREATED,
      WORLD_SETTINGS_APPLIED,
      WORLD_SETTINGS_REJECTED,
      MODPACK_MANIFEST_AVAILABLE,
      MODPACK_COMPATIBILITY_RESULT,
      SKIN_REQUESTED,
      SKIN_RESOLVED,
      SKIN_REJECTED,
      SKIN_CACHE_UPDATED,
      DIAGNOSTICS_WARNING,
      DIAGNOSTICS_HEALTH_CHANGED;
   }
}
