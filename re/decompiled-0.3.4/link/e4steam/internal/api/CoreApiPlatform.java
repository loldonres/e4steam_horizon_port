package link.e4steam.internal.api;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor.AbortPolicy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import link.e4steam.api.ApiConstants;
import link.e4steam.api.access.AccessService;
import link.e4steam.api.addon.AddonHandle;
import link.e4steam.api.addon.AddonService;
import link.e4steam.api.addon.AddonState;
import link.e4steam.api.event.LifecycleEvent;
import link.e4steam.api.event.RuntimeReadyEvent;
import link.e4steam.api.event.RuntimeStoppingEvent;
import link.e4steam.api.identity.IdentityService;
import link.e4steam.api.runtime.LifecyclePhase;
import link.e4steam.api.runtime.SteamRuntimeState;
import link.e4steam.api.session.SessionService;
import link.e4steam.internal.addon.AddonCandidate;
import link.e4steam.internal.addon.AddonManager;
import link.e4steam.internal.addon.BuiltinCapabilityPolicy;
import link.e4steam.steam.SteamAddonNetworkRuntime;
import link.e4steam.steam.SteamClientApiBridge;
import link.e4steam.steam.SteamCustomAccessGate;
import link.e4steam.steam.SteamPeerPrivacy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CoreApiPlatform implements AutoCloseable {
   private static final Logger LOGGER = LoggerFactory.getLogger("e4steam-addon-api");
   private static final Object INSTANCE_LOCK = new Object();
   private static volatile CoreApiPlatform instance;
   private final RuntimeEnvironment environment;
   private final CoreSchedulerService scheduler = new CoreSchedulerService();
   private final CoreEventBus events = new CoreEventBus(this.scheduler);
   private final CoreContributionRegistry contributions = new CoreContributionRegistry();
   private final CoreSessionRegistry sessions = new CoreSessionRegistry(this.events);
   private final AddonNetworkCoordinator network = new AddonNetworkCoordinator(this.scheduler, this.sessions);
   private final CoreRuntimeService runtime;
   private final ExecutorService lifecycleExecutor;
   private final AtomicBoolean closed = new AtomicBoolean();
   private final AutoCloseable addonNetworkHooks;
   private final AutoCloseable customAccessHooks;
   private volatile AddonManager addons;

   private CoreApiPlatform(RuntimeEnvironment environment) {
      this.environment = environment;
      this.runtime = new CoreRuntimeService(environment);
      this.addonNetworkHooks = SteamAddonNetworkRuntime.installHooks();
      this.customAccessHooks = SteamCustomAccessGate.install(new SteamCustomAccessGate.Provider() {
         @Override
         public boolean isRegistered(String modeId) {
            return CoreApiPlatform.this.hasCustomAccess(modeId);
         }

         @Override
         public CompletionStage<Boolean> evaluate(String modeId, long remoteSteamId) {
            return CoreApiPlatform.this.evaluateCustomAccess(modeId, remoteSteamId);
         }
      });
      this.lifecycleExecutor = new ThreadPoolExecutor(
         1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(4), daemonFactory("e4steam-addon-lifecycle"), new AbortPolicy()
      );
      Runtime.getRuntime().addShutdownHook(new Thread(this::close, "e4steam-addon-shutdown"));
   }

   public static CoreApiPlatform start(RuntimeEnvironment environment, List<AddonCandidate> candidates) {
      if (environment != null && candidates != null) {
         synchronized (INSTANCE_LOCK) {
            if (instance != null) {
               return instance;
            } else {
               CoreApiPlatform created = new CoreApiPlatform(environment);
               instance = created;
               created.initialize(candidates);
               return created;
            }
         }
      } else {
         throw new NullPointerException("addon platform");
      }
   }

   public static CoreApiPlatform current() {
      return instance;
   }

   public AddonNetworkCoordinator addonNetwork() {
      return this.network;
   }

   private void initialize(List<AddonCandidate> candidates) {
      this.runtime.phase(LifecyclePhase.ADDON_INITIALIZATION);
      AddonManager manager = new AddonManager(
         ApiConstants.API_VERSION,
         new BuiltinCapabilityPolicy(Collections.emptySet()),
         (descriptor, granted, resources) -> new ScopedE4steamApi(descriptor, granted, resources, this),
         this.lifecycleExecutor,
         10000L,
         this::publishAddonLifecycle
      );
      this.addons = manager;
      this.contributions.frozenSupplier(manager::registrationsFrozen);
      manager.initialize(candidates);
      this.runtime.phase(LifecyclePhase.IDLE);
      this.beginReadinessObservation();
   }

   private void beginReadinessObservation() {
      Thread observer = new Thread(() -> {
         boolean runtimeReadyPublished = false;

         while (!this.closed.get()) {
            this.runtime.refreshReadiness();
            this.sessions.refresh();
            if (!runtimeReadyPublished && this.runtime.snapshot().steamState() == SteamRuntimeState.READY) {
               this.events.publish(RuntimeReadyEvent.TYPE, new RuntimeReadyEvent(System.currentTimeMillis(), this.runtime.snapshot()), true);
               runtimeReadyPublished = true;
            }

            try {
               Thread.sleep(100L);
            } catch (InterruptedException var3) {
               Thread.currentThread().interrupt();
               return;
            }
         }
      }, "e4steam-api-readiness");
      observer.setDaemon(true);
      observer.start();
   }

   CoreRuntimeService runtime() {
      return this.runtime;
   }

   AddonService addons() {
      return this.addons;
   }

   CoreSchedulerService scheduler() {
      return this.scheduler;
   }

   CoreEventBus events() {
      return this.events;
   }

   CoreContributionRegistry contributions() {
      return this.contributions;
   }

   CoreSessionRegistry sessions() {
      return this.sessions;
   }

   AddonNetworkCoordinator network() {
      return this.network;
   }

   RuntimeEnvironment environment() {
      return this.environment;
   }

   public CompletionStage<Boolean> evaluateCustomAccess(String modeId, long remoteSteamId) {
      if (modeId != null && !modeId.isEmpty() && remoteSteamId != 0L && !this.closed.get()) {
         try {
            CoreContributionRegistry.OwnedContribution contribution = this.contributions.findOwned("access", modeId);
            SteamClientApiBridge.SessionView view = SteamClientApiBridge.sessionView();
            if (contribution != null && view.active() && view.generation() > 0L && "INTEGRATED_HOST".equals(view.roleCode())) {
               AccessService.AccessModeId accessModeId = new AccessService.AccessModeId(modeId);
               AccessService.AdmissionContext context = new AccessService.AdmissionContext(
                  new SessionService.SessionId(view.sessionId(), view.generation()),
                  new IdentityService.PeerId(SteamPeerPrivacy.opaquePeerId(view.generation(), remoteSteamId)),
                  contribution.owner(),
                  true
               );
               return CoreAccessService.evaluateRegistered(this.contributions, this.scheduler, accessModeId, context)
                  .handle(
                     (result, failurex) -> failurex == null
                        && result != null
                        && result.isSuccess()
                        && result.value().map(decision -> decision.kind() == AccessService.AdmissionDecision.Kind.ALLOW).orElse(false)
                  );
            } else {
               return CompletableFuture.completedFuture(Boolean.FALSE);
            }
         } catch (RuntimeException var8) {
            return CompletableFuture.completedFuture(Boolean.FALSE);
         }
      } else {
         return CompletableFuture.completedFuture(Boolean.FALSE);
      }
   }

   private boolean hasCustomAccess(String modeId) {
      CoreContributionRegistry.OwnedContribution contribution = this.contributions.findOwned("access", modeId);
      return contribution != null && contribution.value() instanceof AccessService.AccessModeProvider;
   }

   private void publishAddonLifecycle(AddonHandle addon) {
      LifecycleEvent.Kind kind;
      if (addon.state() == AddonState.ACTIVE) {
         kind = LifecycleEvent.Kind.ADDON_ACTIVATED;
      } else if (addon.state() == AddonState.DISABLED) {
         kind = LifecycleEvent.Kind.ADDON_DISABLED;
      } else {
         if (addon.state() != AddonState.FAILED) {
            return;
         }

         kind = LifecycleEvent.Kind.ADDON_FAILED;
      }

      if (addon.state() == AddonState.ACTIVE) {
         LOGGER.info(
            "Loaded e4steam addon {} {} (API range {}, core API {})",
            new Object[]{addon.descriptor().id().value(), addon.descriptor().version(), addon.descriptor().apiRange(), ApiConstants.API_VERSION}
         );
      } else if (addon.state() == AddonState.DISABLED) {
         LOGGER.info("Skipped disabled e4steam addon {} {}", addon.descriptor().id().value(), addon.descriptor().version());
      } else {
         addon.failure()
            .ifPresent(
               failure -> LOGGER.warn(
                  "Could not load e4steam addon {} {}: code={}, operation={}, category={}, addon API range={}, core API={}",
                  new Object[]{
                     addon.descriptor().id().value(),
                     addon.descriptor().version(),
                     failure.error().code(),
                     failure.error().operation(),
                     failure.error().causeCategory(),
                     addon.descriptor().apiRange(),
                     ApiConstants.API_VERSION
                  }
               )
            );
      }

      this.events
         .publish(
            LifecycleEvent.TYPE,
            new LifecycleEvent(System.currentTimeMillis(), kind, addon.descriptor().id().value(), addon.state().name().toLowerCase(Locale.ROOT)),
            false
         );
   }

   @Override
   public void close() {
      if (this.closed.compareAndSet(false, true)) {
         this.runtime.phase(LifecyclePhase.SHUTTING_DOWN);
         this.events.publish(RuntimeStoppingEvent.TYPE, new RuntimeStoppingEvent(System.currentTimeMillis()), false);
         AddonManager manager = this.addons;
         if (manager != null) {
            manager.close();
         }

         this.network.close();

         try {
            this.addonNetworkHooks.close();
         } catch (Exception var4) {
         }

         try {
            this.customAccessHooks.close();
         } catch (Exception var3) {
         }

         this.sessions.close();
         this.scheduler.close();
         this.lifecycleExecutor.shutdownNow();
         this.runtime.phase(LifecyclePhase.STOPPED);
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
}
