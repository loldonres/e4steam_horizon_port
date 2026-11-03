package link.e4steam.internal.api;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor.AbortPolicy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import link.e4steam.api.ApiErrorCode;
import link.e4steam.api.ApiResult;
import link.e4steam.api.scheduler.ExecutionContext;
import link.e4steam.api.scheduler.SchedulerService;
import link.e4steam.api.scheduler.TaskHandle;
import link.e4steam.api.scheduler.TaskState;

final class CoreSchedulerService implements SchedulerService, AutoCloseable {
   private final ThreadLocal<ExecutionContext> current = new ThreadLocal<>();
   private final AtomicInteger outstanding = new AtomicInteger();
   private final Set<CoreSchedulerService.CoreTask> tasks = ConcurrentHashMap.newKeySet();
   private final ThreadPoolExecutor workers;
   private final ScheduledThreadPoolExecutor timer;
   private final AtomicBoolean closed = new AtomicBoolean();

   CoreSchedulerService() {
      this.workers = new ThreadPoolExecutor(
         2, 4, 30L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(256), daemonFactory("e4steam-addon-worker"), new AbortPolicy()
      );
      this.timer = new ScheduledThreadPoolExecutor(1, daemonFactory("e4steam-addon-timer"));
      this.timer.setRemoveOnCancelPolicy(true);
   }

   @Override
   public ApiResult<TaskHandle> execute(ExecutionContext context, Runnable callback, Duration timeout) {
      return this.schedule(context, callback, Duration.ZERO, timeout);
   }

   @Override
   public ApiResult<TaskHandle> schedule(ExecutionContext context, Runnable callback, Duration delay, Duration timeout) {
      if (context == null
         || callback == null
         || delay == null
         || timeout == null
         || delay.isNegative()
         || timeout.isZero()
         || timeout.isNegative()
         || timeout.toMillis() > 30000L) {
         return SafeApiErrors.failure(ApiErrorCode.INVALID_ARGUMENT, "scheduler.schedule", "Validation");
      } else if (this.closed.get()) {
         return SafeApiErrors.failure(ApiErrorCode.UNAVAILABLE, "scheduler.schedule", "Shutdown");
      } else {
         int count = this.outstanding.incrementAndGet();
         if (count > 256) {
            this.outstanding.decrementAndGet();
            return SafeApiErrors.failure(ApiErrorCode.QUEUE_FULL, "scheduler.schedule", "BoundedQueue");
         } else {
            CoreSchedulerService.CoreTask task = new CoreSchedulerService.CoreTask(context, callback, timeout.toMillis());
            this.tasks.add(task);

            try {
               task.delayFuture = this.timer.schedule(() -> {
                  if (!task.taskClosed.get() && !this.closed.get()) {
                     try {
                        task.workerFuture = this.workers.submit(task::run);
                     } catch (RejectedExecutionException var3) {
                        task.reject();
                     }
                  }
               }, delay.toMillis(), TimeUnit.MILLISECONDS);
               return ApiResult.success(task);
            } catch (RejectedExecutionException var8) {
               task.reject();
               return SafeApiErrors.failure(ApiErrorCode.QUEUE_FULL, "scheduler.schedule", "BoundedQueue");
            }
         }
      }
   }

   @Override
   public boolean isCurrentContext(ExecutionContext context) {
      return context != null && context == this.current.get();
   }

   @Override
   public void close() {
      if (this.closed.compareAndSet(false, true)) {
         for (CoreSchedulerService.CoreTask task : this.tasks.toArray(new CoreSchedulerService.CoreTask[0])) {
            task.close();
         }

         this.timer.shutdownNow();
         this.workers.shutdownNow();
      }
   }

   private static ThreadFactory daemonFactory(String prefix) {
      AtomicInteger sequence = new AtomicInteger();
      return runnable -> {
         Thread thread = new Thread(runnable, prefix + "-" + sequence.incrementAndGet());
         thread.setDaemon(true);
         return thread;
      };
   }

   private final class CoreTask implements TaskHandle {
      private final ExecutionContext context;
      private final Runnable callback;
      private final long timeoutMillis;
      private final AtomicBoolean taskClosed = new AtomicBoolean();
      private final CompletableFuture<ApiResult<TaskState>> completion = new CompletableFuture<>();
      private volatile TaskState state = TaskState.QUEUED;
      private volatile Future<?> delayFuture;
      private volatile Future<?> workerFuture;
      private volatile Future<?> timeoutFuture;

      private CoreTask(ExecutionContext context, Runnable callback, long timeoutMillis) {
         this.context = context;
         this.callback = callback;
         this.timeoutMillis = timeoutMillis;
      }

      private void run() {
         if (!this.taskClosed.get()) {
            this.state = TaskState.RUNNING;
            CoreSchedulerService.this.current.set(this.context);

            try {
               this.timeoutFuture = CoreSchedulerService.this.timer.schedule(this::timeout, this.timeoutMillis, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException var10) {
               this.reject();
               CoreSchedulerService.this.current.remove();
               return;
            }

            try {
               this.callback.run();
               this.terminal(TaskState.COMPLETED, ApiResult.success(TaskState.COMPLETED));
            } catch (ThreadDeath | VirtualMachineError var8) {
               this.terminal(TaskState.FAILED, SafeApiErrors.failure(ApiErrorCode.ADDON_FAILURE, "scheduler.callback", var8.getClass().getSimpleName()));
               throw var8;
            } catch (Throwable var9) {
               this.terminal(TaskState.FAILED, SafeApiErrors.failure(ApiErrorCode.ADDON_FAILURE, "scheduler.callback", var9.getClass().getSimpleName()));
            } finally {
               Future<?> watchdog = this.timeoutFuture;
               if (watchdog != null) {
                  watchdog.cancel(false);
               }

               CoreSchedulerService.this.current.remove();
            }
         }
      }

      private void timeout() {
         if (this.terminal(TaskState.TIMED_OUT, SafeApiErrors.failure(ApiErrorCode.TIMEOUT, "scheduler.callback", "TimeBudget"))) {
            Future<?> worker = this.workerFuture;
            if (worker != null) {
               worker.cancel(true);
            }
         }
      }

      private void reject() {
         this.terminal(TaskState.FAILED, SafeApiErrors.failure(ApiErrorCode.QUEUE_FULL, "scheduler.callback", "BoundedQueue"));
      }

      private boolean terminal(TaskState terminalState, ApiResult<TaskState> result) {
         if (!this.taskClosed.compareAndSet(false, true)) {
            return false;
         } else {
            this.state = terminalState;
            CoreSchedulerService.this.tasks.remove(this);
            CoreSchedulerService.this.outstanding.decrementAndGet();
            this.completion.complete(result);
            return true;
         }
      }

      @Override
      public TaskState state() {
         return this.state;
      }

      @Override
      public CompletionStage<ApiResult<TaskState>> completion() {
         return this.completion.thenApply(result -> result);
      }

      @Override
      public void close() {
         if (this.terminal(TaskState.CANCELLED, SafeApiErrors.failure(ApiErrorCode.CANCELLED, "scheduler.callback", "Cancelled"))) {
            Future<?> delayed = this.delayFuture;
            if (delayed != null) {
               delayed.cancel(false);
            }

            Future<?> worker = this.workerFuture;
            if (worker != null) {
               worker.cancel(true);
            }

            Future<?> watchdog = this.timeoutFuture;
            if (watchdog != null) {
               watchdog.cancel(false);
            }
         }
      }

      @Override
      public boolean isClosed() {
         return this.taskClosed.get();
      }
   }
}
