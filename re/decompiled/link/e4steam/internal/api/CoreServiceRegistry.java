package link.e4steam.internal.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import link.e4steam.api.ServiceKey;
import link.e4steam.api.ServiceRegistry;

final class CoreServiceRegistry implements ServiceRegistry {
   private final Map<ServiceKey<?>, Object> services = new LinkedHashMap<>();

   <T> CoreServiceRegistry add(ServiceKey<T> key, T service) {
      if (key != null && service != null && key.serviceType().isInstance(service)) {
         this.services.put(key, service);
         return this;
      } else {
         throw new IllegalArgumentException("Invalid API service registration");
      }
   }

   @Override
   public <T> Optional<T> find(ServiceKey<T> key) {
      Object service = this.services.get(key);
      return service == null ? Optional.empty() : Optional.of(key.serviceType().cast(service));
   }

   @Override
   public Set<String> visibleServiceIds() {
      LinkedHashSet<String> ids = new LinkedHashSet<>();

      for (ServiceKey<?> key : this.services.keySet()) {
         ids.add(key.id());
      }

      return Collections.unmodifiableSet(ids);
   }
}
