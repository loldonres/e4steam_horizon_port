package link.e4steam.api.scheduler;

public enum TaskState {
   QUEUED,
   RUNNING,
   COMPLETED,
   FAILED,
   CANCELLED,
   TIMED_OUT,
   REJECTED;
}
