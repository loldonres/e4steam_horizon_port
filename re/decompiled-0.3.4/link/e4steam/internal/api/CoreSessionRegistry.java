package link.e4steam.internal.api;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import link.e4steam.api.ApiErrorCode;
import link.e4steam.api.ApiResult;
import link.e4steam.api.Registration;
import link.e4steam.api.event.SessionStateEvent;
import link.e4steam.api.identity.IdentityService;
import link.e4steam.api.session.SessionService;
import link.e4steam.internal.dedicated.DedicatedServerController;
import link.e4steam.steam.SteamClientApiBridge;

final class CoreSessionRegistry implements AutoCloseable {
   private final Object lock = new Object();
   private final CoreEventBus events;
   private final Set<CoreSessionRegistry.SessionResource> resources = new LinkedHashSet<>();
   private long observedGeneration;
   private CompletableFuture<ApiResult<SessionService.SessionSnapshot>> nextReady = new CompletableFuture<>();
   private SessionService.SessionSnapshot lastPublished;
   private boolean closed;

   CoreSessionRegistry(CoreEventBus events) {
      this.events = Objects.requireNonNull(events, "events");
   }

   ApiResult<SessionService.SessionSnapshot> snapshot() {
      SessionService.SessionSnapshot dedicated = this.dedicatedSnapshot();
      if (dedicated != null) {
         return ApiResult.success(dedicated);
      } else {
         SteamClientApiBridge.SessionView view = SteamClientApiBridge.sessionView();
         return view.active() ? ApiResult.success(toSnapshot(view)) : SafeApiErrors.failure(ApiErrorCode.UNAVAILABLE, "session.snapshot", "NoActiveContext");
      }
   }

   CompletionStage<ApiResult<SessionService.PeerPage>> peers(SessionService.SessionId id, String cursor, int limit) {
      if (id != null && limit >= 1 && limit <= 100) {
         DedicatedServerController dedicated = DedicatedServerController.current();
         SessionService.SessionId dedicatedId = dedicated == null ? null : dedicated.addonSessionId();
         if (dedicatedId != null && dedicatedId.equals(id)) {
            try {
               return completed(ApiResult.success(dedicatedPeerPage(dedicated, cursor, limit)));
            } catch (RuntimeException var13) {
               return completed(SafeApiErrors.failure(ApiErrorCode.INVALID_ARGUMENT, "session.peers", "Cursor"));
            }
         } else {
            SteamClientApiBridge.SessionView view = SteamClientApiBridge.sessionView();
            if (!matches(view, id)) {
               return completed(stale("session.peers"));
            } else {
               String after = cursor == null ? "" : cursor.trim();
               if (!after.isEmpty()) {
                  try {
                     new IdentityService.PeerId(after);
                  } catch (RuntimeException var14) {
                     return completed(SafeApiErrors.failure(ApiErrorCode.INVALID_ARGUMENT, "session.peers", "Cursor"));
                  }
               }

               ArrayList<SteamClientApiBridge.PeerIdentity> peers = new ArrayList<>(view.peers());
               peers.sort(Comparator.comparing(SteamClientApiBridge.PeerIdentity::opaquePeerId));
               ArrayList<SessionService.PeerSnapshot> page = new ArrayList<>();
               String next = "";

               for (SteamClientApiBridge.PeerIdentity peer : peers) {
                  if (after.isEmpty() || peer.opaquePeerId().compareTo(after) > 0) {
                     if (page.size() == limit) {
                        next = page.get(page.size() - 1).peerId().value();
                        break;
                     }

                     page.add(new SessionService.PeerSnapshot(new IdentityService.PeerId(peer.opaquePeerId()), true));
                  }
               }

               return completed(ApiResult.success(new SessionService.PeerPage(page, next)));
            }
         }
      } else {
         return completed(SafeApiErrors.failure(ApiErrorCode.INVALID_ARGUMENT, "session.peers", "Pagination"));
      }
   }

   CompletionStage<ApiResult<SessionService.SessionSnapshot>> disconnect(SessionService.SessionId id) {
      if (id == null) {
         return completed(SafeApiErrors.failure(ApiErrorCode.INVALID_ARGUMENT, "session.disconnect", "Validation"));
      } else {
         DedicatedServerController dedicated = DedicatedServerController.current();
         SessionService.SessionId dedicatedId = dedicated == null ? null : dedicated.addonSessionId();
         if (dedicatedId != null && dedicatedId.equals(id)) {
            return completed(SafeApiErrors.failure(ApiErrorCode.CAPABILITY_DENIED, "session.disconnect", "DedicatedAdminRequired"));
         } else {
            SteamClientApiBridge.SessionView view = SteamClientApiBridge.sessionView();
            if (!matches(view, id)) {
               return completed(stale("session.disconnect"));
            } else {
               SessionService.SessionSnapshot before = toSnapshot(view);
               if (!SteamClientApiBridge.disconnect(id.generation())) {
                  return completed(stale("session.disconnect"));
               } else {
                  this.closeGeneration(id.generation());
                  return completed(
                     ApiResult.success(
                        new SessionService.SessionSnapshot(
                           before.id(), before.role(), SessionService.SessionState.CLOSED, 0, before.capacity(), before.features()
                        )
                     )
                  );
               }
            }
         }
      }
   }

   CompletionStage<ApiResult<SessionService.SessionSnapshot>> readiness() {
      ApiResult<SessionService.SessionSnapshot> current = this.snapshot();
      if (current.isSuccess()) {
         return completed(current);
      } else {
         synchronized (this.lock) {
            return (CompletionStage<ApiResult<SessionService.SessionSnapshot>>)(this.closed
               ? completed(SafeApiErrors.failure(ApiErrorCode.UNAVAILABLE, "session.readiness", "Shutdown"))
               : this.nextReady.thenApply(result -> result));
         }
      }
   }

   ApiResult<Registration> register(SessionService.SessionId id, Registration resource) {
      if (id != null && resource != null) {
         SteamClientApiBridge.SessionView view = SteamClientApiBridge.sessionView();
         if (!matchesAny(view, id)) {
            return stale("session.resource");
         } else {
            CoreSessionRegistry.SessionResource owned = new CoreSessionRegistry.SessionResource(id.generation(), resource);
            synchronized (this.lock) {
               if (this.closed || this.resources.size() >= 1000) {
                  owned.close();
                  return SafeApiErrors.failure(
                     this.closed ? ApiErrorCode.UNAVAILABLE : ApiErrorCode.QUEUE_FULL, "session.resource", this.closed ? "Shutdown" : "ResourceLimit"
                  );
               }

               this.resources.add(owned);
            }

            if (!matchesAny(SteamClientApiBridge.sessionView(), id)) {
               owned.close();
               return stale("session.resource");
            } else {
               return ApiResult.success(owned);
            }
         }
      } else {
         return SafeApiErrors.failure(ApiErrorCode.INVALID_ARGUMENT, "session.resource", "Validation");
      }
   }

   IdentityService.LocalIdentity localIdentity() {
      SteamClientApiBridge.MinecraftIdentity identity = SteamClientApiBridge.localIdentity();
      return identity == null
         ? null
         : new IdentityService.LocalIdentity(new IdentityService.MinecraftIdentity(identity.minecraftUuid(), identity.minecraftName(), true));
   }

   IdentityService.RemoteIdentity remoteIdentity(IdentityService.PeerId peerId) {
      if (peerId == null) {
         return null;
      } else {
         DedicatedServerController dedicated = DedicatedServerController.current();
         DedicatedServerController.DedicatedPeerIdentity dedicatedIdentity = dedicated == null ? null : dedicated.addonPeerIdentity(peerId.value());
         if (dedicatedIdentity != null) {
            return new IdentityService.RemoteIdentity(
               new IdentityService.PeerIdentity(
                  peerId, new IdentityService.MinecraftIdentity(dedicatedIdentity.minecraftUuid(), dedicatedIdentity.minecraftName(), false)
               )
            );
         } else {
            SteamClientApiBridge.PeerIdentity peer = SteamClientApiBridge.resolvePeer(peerId.value());
            return peer == null
               ? null
               : new IdentityService.RemoteIdentity(
                  new IdentityService.PeerIdentity(peerId, new IdentityService.MinecraftIdentity(peer.minecraftUuid(), peer.minecraftName(), false))
               );
         }
      }
   }

   boolean matches(SessionService.SessionId id, IdentityService.PeerId peerId) {
      if (id != null && peerId != null) {
         DedicatedServerController dedicated = DedicatedServerController.current();
         if (dedicated != null && dedicated.matchesAddonPeer(id, peerId)) {
            return true;
         } else {
            SteamClientApiBridge.SessionView view = SteamClientApiBridge.sessionView();
            return matches(view, id) && SteamClientApiBridge.resolvePeer(peerId.value()) != null;
         }
      } else {
         return false;
      }
   }

   boolean localIsHost(SessionService.SessionId id) {
      if (id == null) {
         return false;
      } else {
         DedicatedServerController dedicated = DedicatedServerController.current();
         SessionService.SessionId dedicatedId = dedicated == null ? null : dedicated.addonSessionId();
         if (dedicatedId != null && dedicatedId.equals(id)) {
            return true;
         } else {
            SteamClientApiBridge.SessionView view = SteamClientApiBridge.sessionView();
            return matches(view, id) && "INTEGRATED_HOST".equals(view.roleCode());
         }
      }
   }

   void refresh() {
      SteamClientApiBridge.SessionView view = SteamClientApiBridge.sessionView();
      SessionService.SessionSnapshot dedicated = this.dedicatedSnapshot();
      List<CoreSessionRegistry.SessionResource> stale = Collections.emptyList();
      CompletableFuture<ApiResult<SessionService.SessionSnapshot>> ready = null;
      SessionService.SessionSnapshot publish = null;
      synchronized (this.lock) {
         if (this.closed) {
            return;
         }

         long current = dedicated != null ? dedicated.id().generation() : (view.active() ? view.generation() : 0L);
         if (this.observedGeneration != 0L && this.observedGeneration != current) {
            stale = this.removeGeneration(this.observedGeneration);
            this.nextReady = new CompletableFuture<>();
         }

         this.observedGeneration = current;
         if (dedicated == null && !view.active()) {
            if (this.lastPublished != null && this.lastPublished.state() != SessionService.SessionState.CLOSED) {
               this.lastPublished = closedSnapshot(this.lastPublished);
               publish = this.lastPublished;
            }
         } else {
            SessionService.SessionSnapshot snapshot = dedicated != null ? dedicated : toSnapshot(view);
            if (!this.nextReady.isDone()) {
               ready = this.nextReady;
            }

            if (!sameState(this.lastPublished, snapshot)) {
               this.lastPublished = snapshot;
               publish = snapshot;
            }
         }
      }

      for (CoreSessionRegistry.SessionResource resource : stale) {
         resource.closeDelegate();
      }

      if (ready != null) {
         ready.complete(ApiResult.success(dedicated != null ? dedicated : toSnapshot(view)));
      }

      if (publish != null) {
         this.events.publish(SessionStateEvent.TYPE, new SessionStateEvent(System.currentTimeMillis(), publish), true);
      }
   }

   @Override
   public void close() {
      List<CoreSessionRegistry.SessionResource> closing;
      CompletableFuture<ApiResult<SessionService.SessionSnapshot>> readiness;
      synchronized (this.lock) {
         if (this.closed) {
            return;
         }

         this.closed = true;
         closing = new ArrayList<>(this.resources);
         this.resources.clear();
         readiness = this.nextReady;
      }

      for (CoreSessionRegistry.SessionResource resource : closing) {
         resource.closeDelegate();
      }

      readiness.complete(SafeApiErrors.failure(ApiErrorCode.UNAVAILABLE, "session.readiness", "Shutdown"));
   }

   private void closeGeneration(long generation) {
      List<CoreSessionRegistry.SessionResource> closing;
      synchronized (this.lock) {
         closing = this.removeGeneration(generation);
      }

      for (CoreSessionRegistry.SessionResource resource : closing) {
         resource.closeDelegate();
      }
   }

   private List<CoreSessionRegistry.SessionResource> removeGeneration(long generation) {
      ArrayList<CoreSessionRegistry.SessionResource> closing = new ArrayList<>();
      Iterator<CoreSessionRegistry.SessionResource> iterator = this.resources.iterator();

      while (iterator.hasNext()) {
         CoreSessionRegistry.SessionResource resource = iterator.next();
         if (resource.generation == generation) {
            iterator.remove();
            closing.add(resource);
         }
      }

      return closing;
   }

   private static SessionService.SessionSnapshot toSnapshot(SteamClientApiBridge.SessionView view) {
      return new SessionService.SessionSnapshot(
         new SessionService.SessionId(view.sessionId(), view.generation()),
         SessionService.SessionRole.valueOf(view.roleCode()),
         SessionService.SessionState.valueOf(view.stateCode()),
         view.peers().size(),
         view.capacity(),
         new LinkedHashSet<>(Arrays.asList("e4steam:tcp", "e4steam:udp"))
      );
   }

   private SessionService.SessionSnapshot dedicatedSnapshot() {
      DedicatedServerController dedicated = DedicatedServerController.current();
      if (dedicated != null && dedicated.accepting()) {
         SessionService.SessionId id = dedicated.addonSessionId();
         return id == null
            ? null
            : new SessionService.SessionSnapshot(
               id,
               SessionService.SessionRole.DEDICATED_SERVER,
               SessionService.SessionState.ACTIVE,
               dedicated.addonPeerIds().size(),
               dedicated.maxPeers(),
               new LinkedHashSet<>(Arrays.asList("e4steam:tcp", "e4steam:addon-channels"))
            );
      } else {
         return null;
      }
   }

   private static SessionService.PeerPage dedicatedPeerPage(DedicatedServerController dedicated, String cursor, int limit) {
      String after = cursor == null ? "" : cursor.trim();
      if (!after.isEmpty()) {
         new IdentityService.PeerId(after);
      }

      ArrayList<SessionService.PeerSnapshot> page = new ArrayList<>();
      String next = "";

      for (String value : dedicated.addonPeerIds()) {
         if (after.isEmpty() || value.compareTo(after) > 0) {
            if (page.size() == limit) {
               next = page.get(page.size() - 1).peerId().value();
               break;
            }

            page.add(new SessionService.PeerSnapshot(new IdentityService.PeerId(value), true));
         }
      }

      return new SessionService.PeerPage(page, next);
   }

   private static SessionService.SessionSnapshot closedSnapshot(SessionService.SessionSnapshot previous) {
      return new SessionService.SessionSnapshot(previous.id(), previous.role(), SessionService.SessionState.CLOSED, 0, previous.capacity(), previous.features());
   }

   private static boolean sameState(SessionService.SessionSnapshot first, SessionService.SessionSnapshot second) {
      return first != null
         && first.id().equals(second.id())
         && first.role() == second.role()
         && first.state() == second.state()
         && first.peers() == second.peers()
         && first.capacity() == second.capacity()
         && first.features().equals(second.features());
   }

   private static boolean matches(SteamClientApiBridge.SessionView view, SessionService.SessionId id) {
      return view.active() && view.generation() == id.generation() && view.sessionId().equals(id.value());
   }

   private static boolean matchesAny(SteamClientApiBridge.SessionView view, SessionService.SessionId id) {
      DedicatedServerController dedicated = DedicatedServerController.current();
      SessionService.SessionId dedicatedId = dedicated == null ? null : dedicated.addonSessionId();
      return dedicatedId != null && dedicatedId.equals(id) || matches(view, id);
   }

   private static <T> ApiResult<T> stale(String operation) {
      return SafeApiErrors.failure(ApiErrorCode.STALE_HANDLE, operation, "SessionGeneration");
   }

   private static <T> CompletionStage<T> completed(T value) {
      return CompletableFuture.completedFuture(value);
   }

   private final class SessionResource extends CoreRegistration {
      private final long generation;
      private final Registration delegate;

      private SessionResource(long generation, Registration delegate) {
         super(null);
         this.generation = generation;
         this.delegate = delegate;
      }

      @Override
      public void close() {
         if (!this.isClosed()) {
            super.close();
            synchronized (CoreSessionRegistry.this.lock) {
               CoreSessionRegistry.this.resources.remove(this);
            }

            this.closeDelegate();
         }
      }

      private void closeDelegate() {
         super.close();

         try {
            this.delegate.close();
         } catch (RuntimeException var2) {
         }
      }
   }
}
