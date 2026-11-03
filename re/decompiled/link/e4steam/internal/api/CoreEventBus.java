package link.e4steam.internal.api;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import link.e4steam.api.ApiErrorCode;
import link.e4steam.api.ApiResult;
import link.e4steam.api.ResourceScope;
import link.e4steam.api.Subscription;
import link.e4steam.api.event.ApiEvent;
import link.e4steam.api.event.EventListener;
import link.e4steam.api.event.EventService;
import link.e4steam.api.event.EventType;
import link.e4steam.api.scheduler.ExecutionContext;

final class CoreEventBus {
   private final Object lock = new Object();
   private final CoreSchedulerService scheduler;
   private final Map<EventType<?>, List<CoreEventBus.Listener<?>>> listeners = new HashMap<>();
   private final Map<EventType<?>, ApiEvent> snapshots = new HashMap<>();
   private int subscriptions;

   CoreEventBus(CoreSchedulerService scheduler) {
      this.scheduler = scheduler;
   }

   EventService scoped(ResourceScope resources) {
      return new EventService() {
         @Override
         public <T extends ApiEvent> ApiResult<Subscription> subscribe(EventType<T> type, EventListener<? super T> callback) {
            if (type != null && callback != null) {
               CoreEventBus.Listener<T> registration;
               synchronized (CoreEventBus.this.lock) {
                  if (CoreEventBus.this.subscriptions >= 1024) {
                     return SafeApiErrors.failure(ApiErrorCode.QUEUE_FULL, "event.subscribe", "BoundedSubscriptions");
                  }

                  registration = CoreEventBus.this.new Listener<>(type, callback);
                  CoreEventBus.this.listeners.computeIfAbsent(type, ignored -> new ArrayList<>()).add(registration);
                  CoreEventBus.this.subscriptions++;
               }

               resources.own(registration);
               return ApiResult.success(registration);
            } else {
               return SafeApiErrors.failure(ApiErrorCode.INVALID_ARGUMENT, "event.subscribe", "Validation");
            }
         }

         @Override
         public <T extends ApiEvent> Optional<T> lastSnapshot(EventType<T> type) {
            synchronized (CoreEventBus.this.lock) {
               ApiEvent value = CoreEventBus.this.snapshots.get(type);
               return value == null ? Optional.empty() : Optional.of(type.eventClass().cast(value));
            }
         }
      };
   }

   <T extends ApiEvent> void publish(EventType<T> type, T event, boolean replayable) {
      if (type != null && event != null && type.eventClass().isInstance(event)) {
         List<CoreEventBus.Listener<?>> copy;
         synchronized (this.lock) {
            if (replayable) {
               this.snapshots.put(type, event);
            }

            copy = new ArrayList<>(this.listeners.getOrDefault(type, Collections.emptyList()));
         }

         for (CoreEventBus.Listener<?> listener : copy) {
            this.scheduler.execute(ExecutionContext.ADDON_WORKER, () -> listener.dispatch(event), Duration.ofSeconds(2L));
         }
      } else {
         throw new IllegalArgumentException("Event type mismatch");
      }
   }

   private final class Listener<T extends ApiEvent> extends CoreRegistration implements Subscription {
      private final EventType<T> type;
      private final EventListener<? super T> callback;

      private Listener(EventType<T> type, EventListener<? super T> callback) {
         super(null);
         this.type = type;
         this.callback = callback;
      }

      private void dispatch(ApiEvent event) {
         if (!this.isClosed()) {
            try {
               this.callback.onEvent(this.type.eventClass().cast(event));
            } catch (Exception var3) {
            }
         }
      }

      @Override
      public void close() {
         if (!this.isClosed()) {
            super.close();
            synchronized (CoreEventBus.this.lock) {
               List<CoreEventBus.Listener<?>> current = CoreEventBus.this.listeners.get(this.type);
               if (current != null && current.remove(this)) {
                  CoreEventBus.this.subscriptions--;
               }
            }
         }
      }
   }
}
