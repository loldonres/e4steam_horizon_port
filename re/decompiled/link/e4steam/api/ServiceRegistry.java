package link.e4steam.api;

import java.util.Optional;
import java.util.Set;

public interface ServiceRegistry {
   <T> Optional<T> find(ServiceKey<T> var1);

   Set<String> visibleServiceIds();
}
