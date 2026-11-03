package link.e4steam.internal.addon;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.Map.Entry;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import link.e4steam.api.ApiError;
import link.e4steam.api.ApiErrorCode;
import link.e4steam.api.ApiVersion;
import link.e4steam.api.E4steamApi;
import link.e4steam.api.Retryability;
import link.e4steam.api.addon.AddonContext;
import link.e4steam.api.addon.AddonDependency;
import link.e4steam.api.addon.AddonDescriptor;
import link.e4steam.api.addon.AddonFailure;
import link.e4steam.api.addon.AddonHandle;
import link.e4steam.api.addon.AddonId;
import link.e4steam.api.addon.AddonService;
import link.e4steam.api.addon.AddonState;
import link.e4steam.api.capability.CapabilityId;

public final class AddonManager implements AddonService, AutoCloseable {
   private final Object lock = new Object();
   private final ApiVersion apiVersion;
   private final CapabilityGrantPolicy capabilityPolicy;
   private final AddonApiFactory apiFactory;
   private final ExecutorService lifecycleExecutor;
   private final long callbackTimeoutMillis;
   private final Consumer<AddonHandle> lifecycleListener;
   private final LinkedHashMap<AddonId, AddonManager.Slot> slots = new LinkedHashMap<>();
   private final List<AddonManager.Slot> activationOrder = new ArrayList<>();
   private final AtomicBoolean closed = new AtomicBoolean();
   private volatile boolean frozen;
   private boolean discovered;

   public AddonManager(
      ApiVersion apiVersion, CapabilityGrantPolicy capabilityPolicy, AddonApiFactory apiFactory, ExecutorService lifecycleExecutor, long callbackTimeoutMillis
   ) {
      this(apiVersion, capabilityPolicy, apiFactory, lifecycleExecutor, callbackTimeoutMillis, handle -> {});
   }

   public AddonManager(
      ApiVersion apiVersion,
      CapabilityGrantPolicy capabilityPolicy,
      AddonApiFactory apiFactory,
      ExecutorService lifecycleExecutor,
      long callbackTimeoutMillis,
      Consumer<AddonHandle> lifecycleListener
   ) {
      this.apiVersion = Objects.requireNonNull(apiVersion, "apiVersion");
      this.capabilityPolicy = Objects.requireNonNull(capabilityPolicy, "capabilityPolicy");
      this.apiFactory = Objects.requireNonNull(apiFactory, "apiFactory");
      this.lifecycleExecutor = Objects.requireNonNull(lifecycleExecutor, "lifecycleExecutor");
      this.lifecycleListener = Objects.requireNonNull(lifecycleListener, "lifecycleListener");
      if (callbackTimeoutMillis >= 1L && callbackTimeoutMillis <= 10000L) {
         this.callbackTimeoutMillis = callbackTimeoutMillis;
      } else {
         throw new IllegalArgumentException("Invalid lifecycle callback timeout");
      }
   }

   public void initialize(List<AddonCandidate> candidates) {
      if (candidates == null) {
         throw new NullPointerException("candidates");
      } else {
         synchronized (this.lock) {
            if (this.discovered) {
               throw new IllegalStateException("Addon discovery already completed");
            }

            if (this.closed.get()) {
               throw new IllegalStateException("Addon manager is closed");
            }

            this.discovered = true;
         }

         for (AddonCandidate candidate : candidates) {
            if (candidate == null) {
               throw new NullPointerException("candidate");
            }
         }

         List<AddonCandidate> sorted = new ArrayList<>(candidates);
         sorted.sort(Comparator.comparing(candidatex -> candidatex.descriptor().id()));
         Map<AddonId, List<AddonCandidate>> grouped = new LinkedHashMap<>();

         for (AddonCandidate candidatex : sorted) {
            grouped.computeIfAbsent(candidatex.descriptor().id(), ignored -> new ArrayList<>()).add(candidatex);
         }

         for (Entry<AddonId, List<AddonCandidate>> entry : grouped.entrySet()) {
            AddonCandidate first = entry.getValue().get(0);
            AddonManager.Slot slot = new AddonManager.Slot(first);
            this.slots.put(entry.getKey(), slot);
            if (entry.getValue().size() > 1) {
               slot.fail(error(ApiErrorCode.INVALID_ARGUMENT, "duplicate-id", "addon.discovery", "DuplicateAddonId"));
            } else if (!first.enabled()) {
               slot.state = AddonState.DISABLED;
            }
         }

         this.validateMetadataAndCapabilities();

         for (AddonManager.Slot slot : this.dependencyOrder()) {
            this.initializeOne(slot);
         }

         this.frozen = true;

         for (AddonManager.Slot slot : this.slots.values()) {
            this.notifyLifecycle(slot);
         }
      }
   }

   private void validateMetadataAndCapabilities() {
      for (AddonManager.Slot slot : this.slots.values()) {
         if (!slot.terminal()) {
            slot.state = AddonState.VALIDATING;
            AddonDescriptor descriptor = slot.descriptor();
            if (!descriptor.apiRange().contains(this.apiVersion)) {
               slot.fail(error(ApiErrorCode.INCOMPATIBLE_VERSION, "api-range", "addon.validation", "ApiVersionMismatch"));
            } else {
               LinkedHashSet<CapabilityId> granted = new LinkedHashSet<>();
               Iterator target = descriptor.requestedCapabilities().iterator();

               while (true) {
                  if (target.hasNext()) {
                     CapabilityId requested = (CapabilityId)target.next();
                     boolean known = this.capabilityPolicy.knownCapabilities().contains(requested);
                     boolean allowed = known && this.capabilityPolicy.isAllowed(descriptor, requested);
                     if (allowed) {
                        granted.add(requested);
                        continue;
                     }

                     if (!descriptor.requiredCapabilities().contains(requested)) {
                        continue;
                     }

                     slot.fail(
                        error(
                           ApiErrorCode.CAPABILITY_DENIED,
                           known ? "required-capability-denied" : "required-capability-unknown",
                           "addon.capabilities",
                           known ? "PolicyDenied" : "UnknownCapability"
                        )
                     );
                  }

                  slot.granted = Collections.unmodifiableSet(granted);
                  break;
               }
            }
         }
      }

      for (AddonManager.Slot slotx : this.slots.values()) {
         if (!slotx.terminal()) {
            for (AddonDependency dependency : slotx.descriptor().dependencies()) {
               AddonManager.Slot target = this.slots.get(dependency.addonId());
               if (target == null) {
                  if (dependency.required()) {
                     slotx.fail(error(ApiErrorCode.UNAVAILABLE, "required-dependency-missing", "addon.dependencies", "MissingDependency"));
                  }
               } else if (!dependency.supportedVersions().contains(target.descriptor().version()) && dependency.required()) {
                  slotx.fail(error(ApiErrorCode.INCOMPATIBLE_VERSION, "dependency-version", "addon.dependencies", "DependencyVersionMismatch"));
               }
            }
         }
      }
   }

   private List<AddonManager.Slot> dependencyOrder() {
      Map<AddonManager.Slot, Integer> indegree = new LinkedHashMap<>();
      Map<AddonManager.Slot, List<AddonManager.Slot>> outgoing = new HashMap<>();

      for (AddonManager.Slot slot : this.slots.values()) {
         if (!slot.terminal()) {
            indegree.put(slot, 0);
         }
      }

      for (AddonManager.Slot slotx : indegree.keySet()) {
         for (AddonDependency dependency : slotx.descriptor().dependencies()) {
            AddonManager.Slot target = this.slots.get(dependency.addonId());
            if (target != null && !target.terminal() && dependency.supportedVersions().contains(target.descriptor().version())) {
               outgoing.computeIfAbsent(target, ignored -> new ArrayList<>()).add(slotx);
               indegree.put(slotx, indegree.get(slotx) + 1);
            }
         }
      }

      ArrayDeque<AddonManager.Slot> ready = new ArrayDeque<>();
      indegree.entrySet()
         .stream()
         .filter(entry -> entry.getValue() == 0)
         .map(Entry::getKey)
         .sorted(Comparator.comparing(slotx -> slotx.descriptor().id()))
         .forEach(ready::addLast);
      List<AddonManager.Slot> order = new ArrayList<>();

      while (!ready.isEmpty()) {
         AddonManager.Slot current = ready.removeFirst();
         order.add(current);
         List<AddonManager.Slot> dependants = outgoing.getOrDefault(current, Collections.emptyList());
         dependants.sort(Comparator.comparing(slotx -> slotx.descriptor().id()));

         for (AddonManager.Slot dependant : dependants) {
            int remaining = indegree.get(dependant) - 1;
            indegree.put(dependant, remaining);
            if (remaining == 0) {
               ready.addLast(dependant);
            }
         }
      }

      if (order.size() != indegree.size()) {
         Set<AddonManager.Slot> ordered = new HashSet<>(order);

         for (AddonManager.Slot slotx : indegree.keySet()) {
            if (!ordered.contains(slotx)) {
               slotx.fail(error(ApiErrorCode.INVALID_ARGUMENT, "dependency-cycle", "addon.dependencies", "DependencyCycle"));
            }
         }
      }

      return order;
   }

   private void initializeOne(AddonManager.Slot slot) {
      if (!slot.terminal()) {
         for (AddonDependency dependency : slot.descriptor().dependencies()) {
            if (dependency.required()) {
               AddonManager.Slot target = this.slots.get(dependency.addonId());
               if (target == null || target.state != AddonState.ACTIVE) {
                  slot.fail(error(ApiErrorCode.UNAVAILABLE, "required-dependency-inactive", "addon.initialize", "DependencyInactive"));
                  return;
               }
            }
         }

         slot.state = AddonState.INITIALIZING;

         E4steamApi api;
         try {
            api = this.apiFactory.create(slot.descriptor(), slot.granted, slot.resources);
         } catch (RuntimeException var8) {
            slot.fail(error(ApiErrorCode.ADDON_FAILURE, "api-view-failed", "addon.initialize", safeCategory(var8)));
            return;
         }

         AddonContext context = new AddonManager.Context(slot.descriptor(), api, slot.resources);

         Future<?> callback;
         try {
            callback = this.lifecycleExecutor.submit(() -> {
               try {
                  slot.candidate.addon().initialize(context);
               } catch (Exception var3x) {
                  throw new AddonManager.AddonCallbackException(var3x);
               }
            });
         } catch (RejectedExecutionException var7) {
            slot.fail(error(ApiErrorCode.QUEUE_FULL, "initialize-rejected", "addon.initialize", "LifecycleQueue"));
            return;
         }

         try {
            callback.get(this.callbackTimeoutMillis, TimeUnit.MILLISECONDS);
            slot.state = AddonState.ACTIVE;
            this.activationOrder.add(slot);
         } catch (TimeoutException var9) {
            callback.cancel(true);
            slot.fail(error(ApiErrorCode.TIMEOUT, "initialize-timeout", "addon.initialize", "Timeout"));
         } catch (InterruptedException var10) {
            Thread.currentThread().interrupt();
            callback.cancel(true);
            slot.fail(error(ApiErrorCode.CANCELLED, "initialize-interrupted", "addon.initialize", "Interrupted"));
         } catch (ExecutionException var11) {
            Throwable cause = rootCause(var11);
            if (cause instanceof VirtualMachineError) {
               throw (VirtualMachineError)cause;
            }

            if (cause instanceof ThreadDeath) {
               throw (ThreadDeath)cause;
            }

            slot.fail(error(ApiErrorCode.ADDON_FAILURE, "initialize-failed", "addon.initialize", safeCategory(cause)));
         }
      }
   }

   @Override
   public List<AddonHandle> addons() {
      synchronized (this.lock) {
         return Collections.unmodifiableList(new ArrayList<>(this.slots.values()));
      }
   }

   @Override
   public Optional<AddonHandle> find(AddonId addonId) {
      if (addonId == null) {
         throw new NullPointerException("addonId");
      } else {
         synchronized (this.lock) {
            return Optional.ofNullable(this.slots.get(addonId));
         }
      }
   }

   @Override
   public boolean registrationsFrozen() {
      return this.frozen;
   }

   @Override
   public void close() {
      if (this.closed.compareAndSet(false, true)) {
         this.frozen = true;
         RuntimeException first = null;

         for (int index = this.activationOrder.size() - 1; index >= 0; index--) {
            AddonManager.Slot slot = this.activationOrder.get(index);

            try {
               slot.resources.close();
            } catch (RuntimeException var6) {
               if (first == null) {
                  first = var6;
               } else {
                  first.addSuppressed(var6);
               }
            }

            slot.state = AddonState.STOPPED;
         }

         for (AddonManager.Slot slot : this.slots.values()) {
            if (slot.state != AddonState.STOPPED && !slot.resources.isClosed()) {
               try {
                  slot.resources.close();
               } catch (RuntimeException var5) {
                  if (first == null) {
                     first = var5;
                  } else {
                     first.addSuppressed(var5);
                  }
               }

               if (slot.state == AddonState.DISCOVERED || slot.state == AddonState.VALIDATING || slot.state == AddonState.INITIALIZING) {
                  slot.state = AddonState.STOPPED;
               }
            }
         }

         if (first != null) {
            throw first;
         }
      }
   }

   private static ApiError error(ApiErrorCode code, String message, String operation, String category) {
      return new ApiError(code, "e4steam.api." + message, Retryability.PERMANENT, operation, "", category);
   }

   private static Throwable rootCause(Throwable failure) {
      Throwable current = failure;

      while (current.getCause() != null && current.getCause() != current) {
         current = current.getCause();
      }

      return current;
   }

   private static String safeCategory(Throwable failure) {
      String simple = failure == null ? "AddonFailure" : failure.getClass().getSimpleName();
      return simple.matches("[A-Za-z0-9_$.-]{1,64}") ? simple : "AddonFailure";
   }

   private void notifyLifecycle(AddonManager.Slot slot) {
      try {
         this.lifecycleListener.accept(slot);
      } catch (RuntimeException var3) {
      }
   }

   private static final class AddonCallbackException extends RuntimeException {
      private AddonCallbackException(Throwable cause) {
         super(cause);
      }
   }

   private static final class Context implements AddonContext {
      private final AddonDescriptor descriptor;
      private final E4steamApi api;
      private final ManagedResourceScope resources;

      private Context(AddonDescriptor descriptor, E4steamApi api, ManagedResourceScope resources) {
         this.descriptor = descriptor;
         this.api = api;
         this.resources = resources;
      }

      @Override
      public AddonDescriptor descriptor() {
         return this.descriptor;
      }

      @Override
      public E4steamApi api() {
         return this.api;
      }

      public ManagedResourceScope resources() {
         return this.resources;
      }
   }

   private static final class Slot implements AddonHandle {
      private final AddonCandidate candidate;
      private final ManagedResourceScope resources = new ManagedResourceScope();
      private volatile AddonState state = AddonState.DISCOVERED;
      private volatile AddonFailure failure;
      private volatile Set<CapabilityId> granted = Collections.emptySet();

      private Slot(AddonCandidate candidate) {
         this.candidate = candidate;
      }

      @Override
      public AddonDescriptor descriptor() {
         return this.candidate.descriptor();
      }

      @Override
      public AddonState state() {
         return this.state;
      }

      @Override
      public Optional<AddonFailure> failure() {
         return Optional.ofNullable(this.failure);
      }

      private boolean terminal() {
         return this.state == AddonState.DISABLED || this.state == AddonState.FAILED || this.state == AddonState.STOPPED;
      }

      private void fail(ApiError error) {
         this.state = AddonState.FAILED;
         this.failure = new AddonFailure(this.descriptor().id(), error);

         try {
            this.resources.close();
         } catch (RuntimeException var3) {
         }
      }
   }
}
