package link.e4steam.api.scheduler;

import java.time.Duration;
import link.e4steam.api.ApiResult;

public interface SchedulerService {
   ApiResult<TaskHandle> execute(ExecutionContext var1, Runnable var2, Duration var3);

   ApiResult<TaskHandle> schedule(ExecutionContext var1, Runnable var2, Duration var3, Duration var4);

   boolean isCurrentContext(ExecutionContext var1);
}
