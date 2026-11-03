package link.e4steam.api.event;

import java.util.Optional;
import link.e4steam.api.ApiResult;
import link.e4steam.api.Subscription;

public interface EventService {
   <T extends ApiEvent> ApiResult<Subscription> subscribe(EventType<T> var1, EventListener<? super T> var2);

   <T extends ApiEvent> Optional<T> lastSnapshot(EventType<T> var1);
}
