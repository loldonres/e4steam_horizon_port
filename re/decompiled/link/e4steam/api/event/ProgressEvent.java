package link.e4steam.api.event;

import link.e4steam.api.ApiValidation;

public final class ProgressEvent implements ApiEvent {
   public static final EventType<ProgressEvent> TYPE = new EventType<>("e4steam:progress", ProgressEvent.class);
   private final long occurredAtEpochMillis;
   private final String operationId;
   private final long completed;
   private final long total;
   private final String safeCode;

   public ProgressEvent(long occurredAtEpochMillis, String operationId, long completed, long total, String safeCode) {
      if (occurredAtEpochMillis >= 0L && completed >= 0L && total >= 0L && completed <= total) {
         this.occurredAtEpochMillis = occurredAtEpochMillis;
         this.operationId = ApiValidation.text(operationId, "operationId", 96);
         ApiValidation.rejectSensitiveName(this.operationId, "operationId");
         this.completed = completed;
         this.total = total;
         this.safeCode = ApiValidation.optionalText(safeCode, "safeCode", 96);
      } else {
         throw new IllegalArgumentException("invalid progress");
      }
   }

   @Override
   public long occurredAtEpochMillis() {
      return this.occurredAtEpochMillis;
   }

   public String operationId() {
      return this.operationId;
   }

   public long completed() {
      return this.completed;
   }

   public long total() {
      return this.total;
   }

   public String safeCode() {
      return this.safeCode;
   }
}
