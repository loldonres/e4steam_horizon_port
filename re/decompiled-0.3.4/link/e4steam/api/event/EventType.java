package link.e4steam.api.event;

import java.util.Objects;
import link.e4steam.api.ServiceKey;

public final class EventType<T extends ApiEvent> {
   private final ServiceKey<T> key;

   public EventType(String id, Class<T> eventClass) {
      this.key = new ServiceKey<>(id, eventClass);
   }

   public String id() {
      return this.key.id();
   }

   public Class<T> eventClass() {
      return this.key.serviceType();
   }

   @Override
   public boolean equals(Object other) {
      return this == other || other instanceof EventType && this.key.equals(((EventType)other).key);
   }

   @Override
   public int hashCode() {
      return Objects.hash(this.key);
   }

   @Override
   public String toString() {
      return this.key.toString();
   }
}
