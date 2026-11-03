package link.e4steam.api.scheduler;

import java.util.concurrent.CompletionStage;
import link.e4steam.api.ApiResult;
import link.e4steam.api.Registration;

public interface TaskHandle extends Registration {
   TaskState state();

   CompletionStage<ApiResult<TaskState>> completion();
}
