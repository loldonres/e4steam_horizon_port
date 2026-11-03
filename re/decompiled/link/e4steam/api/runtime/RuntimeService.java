package link.e4steam.api.runtime;

import java.util.concurrent.CompletionStage;

public interface RuntimeService {
   RuntimeSnapshot snapshot();

   CompletionStage<RuntimeSnapshot> readiness();
}
