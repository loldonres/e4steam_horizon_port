package link.e4steam.internal.dedicated;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import link.e4steam.steam.SteamMinecraftIdentity;

public final class DedicatedAdmissionGate {
   private static final int MIN_NONCE_BYTES = 16;
   private static final int MAX_NONCE_BYTES = 64;
   private static final int MAX_TICKET_BYTES = 4096;
   private static final int MAX_REPLAY_ENTRIES = 2048;
   private static final long REPLAY_TTL_MILLIS = 60000L;
   private static final int MAX_ATTEMPTS_PER_WINDOW = 8;
   private static final long RATE_WINDOW_MILLIS = 60000L;
   private final DedicatedAdmissionGate.Authenticator authenticator;
   private final DedicatedAdmissionGate.CorePolicy policy;
   private final Map<String, Long> replay = new LinkedHashMap<String, Long>(64, 0.75F, true) {
      @Override
      protected boolean removeEldestEntry(Entry<String, Long> eldest) {
         return this.size() > 2048;
      }
   };
   private final ConcurrentHashMap<Long, DedicatedAdmissionGate.RateWindow> attempts = new ConcurrentHashMap<>();

   public DedicatedAdmissionGate(DedicatedAdmissionGate.Authenticator authenticator, DedicatedAdmissionGate.CorePolicy policy) {
      this.authenticator = Objects.requireNonNull(authenticator, "authenticator");
      this.policy = Objects.requireNonNull(policy, "policy");
   }

   public CompletionStage<DedicatedAdmissionGate.Result> evaluate(DedicatedAdmissionGate.Request request, long activeGeneration) {
      if (request == null) {
         return completed(DedicatedAdmissionGate.Result.deny("invalid-request"));
      } else if (!request.hasCredentials()) {
         request.destroy();
         return completed(DedicatedAdmissionGate.Result.deny("invalid-request"));
      } else if (request.generation != activeGeneration || activeGeneration <= 0L) {
         request.destroy();
         return completed(DedicatedAdmissionGate.Result.deny("stale-generation"));
      } else if (request.wireVersion != 4) {
         request.destroy();
         return completed(DedicatedAdmissionGate.Result.deny("incompatible-wire"));
      } else if (!this.allowAttempt(request.remoteSteamId, System.currentTimeMillis())) {
         request.destroy();
         return completed(DedicatedAdmissionGate.Result.deny("rate-limited"));
      } else if (!this.claimNonce(request.nonce, System.currentTimeMillis())) {
         request.destroy();
         return completed(DedicatedAdmissionGate.Result.deny("replayed-hello"));
      } else {
         Arrays.fill(request.nonce, (byte)0);
         byte[] ticket = request.takeTicket();

         CompletionStage<Boolean> validation;
         try {
            validation = this.authenticator.authenticate(request.remoteSteamId, ticket, activeGeneration);
         } catch (ThreadDeath | VirtualMachineError var7) {
            Arrays.fill(ticket, (byte)0);
            throw var7;
         } catch (Throwable var8) {
            Arrays.fill(ticket, (byte)0);
            return completed(DedicatedAdmissionGate.Result.deny("steam-auth-failed"));
         }

         Arrays.fill(ticket, (byte)0);
         if (validation == null) {
            return completed(DedicatedAdmissionGate.Result.deny("steam-auth-failed"));
         } else {
            CompletableFuture<DedicatedAdmissionGate.Result> result = new CompletableFuture<>();
            validation.whenComplete(
               (authenticated, failure) -> {
                  if (failure == null && Boolean.TRUE.equals(authenticated)) {
                     DedicatedAdmissionGate.Result mandatory = this.mandatoryAfterAuthentication(request.remoteSteamId);
                     if (!mandatory.allowed()) {
                        result.complete(mandatory);
                     } else {
                        CompletionStage<Boolean> addon;
                        try {
                           addon = this.policy.addonPolicy(request.remoteSteamId);
                        } catch (ThreadDeath | VirtualMachineError var10) {
                           throw var10;
                        } catch (Throwable var11) {
                           result.complete(DedicatedAdmissionGate.Result.deny("addon-policy-failed"));
                           return;
                        }

                        if (addon == null) {
                           result.complete(DedicatedAdmissionGate.Result.deny("addon-policy-failed"));
                        } else {
                           addon.whenComplete(
                              (allowed, addonFailure) -> {
                                 if (addonFailure == null && Boolean.TRUE.equals(allowed)) {
                                    result.complete(
                                       DedicatedAdmissionGate.Result.allow(
                                          request.remoteSteamId,
                                          activeGeneration,
                                          SteamMinecraftIdentity.uuid(request.remoteSteamId),
                                          SteamMinecraftIdentity.safeName(request.remoteSteamId)
                                       )
                                    );
                                 } else {
                                    result.complete(DedicatedAdmissionGate.Result.deny("addon-policy-denied"));
                                 }
                              }
                           );
                        }
                     }
                  } else {
                     result.complete(DedicatedAdmissionGate.Result.deny("steam-auth-failed"));
                  }
               }
            );
            return result.thenApply(value -> (DedicatedAdmissionGate.Result)value);
         }
      }
   }

   private DedicatedAdmissionGate.Result mandatoryAfterAuthentication(long steamId) {
      try {
         if (this.policy.banned(steamId)) {
            return DedicatedAdmissionGate.Result.deny("banned");
         } else {
            int capacity = this.policy.capacity();
            int players = this.policy.players();
            return capacity >= 1 && players >= 0 && players < capacity
               ? DedicatedAdmissionGate.Result.allowCore()
               : DedicatedAdmissionGate.Result.deny("capacity-reached");
         }
      } catch (ThreadDeath | VirtualMachineError var5) {
         throw var5;
      } catch (Throwable var6) {
         return DedicatedAdmissionGate.Result.deny("core-policy-failed");
      }
   }

   private boolean claimNonce(byte[] nonce, long now) {
      if (nonce != null && nonce.length >= 16 && nonce.length <= 64) {
         String key = digest(nonce);
         synchronized (this.replay) {
            Iterator<Entry<String, Long>> iterator = this.replay.entrySet().iterator();

            while (iterator.hasNext()) {
               if (iterator.next().getValue() <= now) {
                  iterator.remove();
               }
            }

            if (this.replay.containsKey(key)) {
               return false;
            } else {
               this.replay.put(key, now + 60000L);
               return true;
            }
         }
      } else {
         return false;
      }
   }

   private boolean allowAttempt(long steamId, long now) {
      if (steamId == 0L) {
         return false;
      } else {
         if (this.attempts.size() >= 1024 && !this.attempts.containsKey(steamId)) {
            for (Entry<Long, DedicatedAdmissionGate.RateWindow> entry : this.attempts.entrySet()) {
               if (now - entry.getValue().started >= 60000L) {
                  this.attempts.remove(entry.getKey(), entry.getValue());
               }
            }

            if (this.attempts.size() >= 1024) {
               return false;
            }
         }

         DedicatedAdmissionGate.RateWindow updated = this.attempts
            .compute(
               steamId,
               (id, current) -> current != null && now - current.started < 60000L
                  ? new DedicatedAdmissionGate.RateWindow(current.started, current.count + 1)
                  : new DedicatedAdmissionGate.RateWindow(now, 1)
            );
         return updated.count <= 8;
      }
   }

   private static String digest(byte[] value) {
      try {
         byte[] hash = MessageDigest.getInstance("SHA-256").digest(value);
         return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
      } catch (NoSuchAlgorithmException var2) {
         throw new IllegalStateException("SHA-256 unavailable", var2);
      }
   }

   private static CompletionStage<DedicatedAdmissionGate.Result> completed(DedicatedAdmissionGate.Result result) {
      return CompletableFuture.completedFuture(result);
   }

   public interface Authenticator {
      CompletionStage<Boolean> authenticate(long var1, byte[] var3, long var4);
   }

   public interface CorePolicy {
      boolean banned(long var1);

      boolean whitelistRequired();

      boolean whitelisted(long var1);

      int players();

      int capacity();

      CompletionStage<Boolean> addonPolicy(long var1);
   }

   private static final class RateWindow {
      private final long started;
      private final int count;

      private RateWindow(long started, int count) {
         this.started = started;
         this.count = count;
      }
   }

   public static final class Request implements AutoCloseable {
      private final long remoteSteamId;
      private final long generation;
      private final int wireVersion;
      private final byte[] nonce;
      private byte[] ticket;

      public Request(long remoteSteamId, long generation, int wireVersion, byte[] nonce, byte[] ticket) {
         if (remoteSteamId == 0L) {
            throw new IllegalArgumentException("steamId");
         } else if (nonce == null || nonce.length < 16 || nonce.length > 64) {
            throw new IllegalArgumentException("nonce");
         } else if (ticket != null && ticket.length != 0 && ticket.length <= 4096) {
            this.remoteSteamId = remoteSteamId;
            this.generation = generation;
            this.wireVersion = wireVersion;
            this.nonce = (byte[])nonce.clone();
            this.ticket = (byte[])ticket.clone();
         } else {
            throw new IllegalArgumentException("ticket");
         }
      }

      private synchronized byte[] takeTicket() {
         byte[] current = this.ticket;
         if (current == null) {
            return new byte[0];
         } else {
            this.ticket = null;
            return current;
         }
      }

      private synchronized boolean hasCredentials() {
         return this.ticket != null && this.ticket.length > 0;
      }

      @Override
      public synchronized void close() {
         if (this.ticket != null) {
            Arrays.fill(this.ticket, (byte)0);
            this.ticket = null;
         }

         Arrays.fill(this.nonce, (byte)0);
      }

      @Override
      public String toString() {
         return "DedicatedAdmissionRequest{generation=" + this.generation + ", wire=" + this.wireVersion + ", credentials=redacted}";
      }

      private void destroy() {
         this.close();
      }
   }

   public static final class Result {
      private final boolean allowed;
      private final String reason;
      private final long internalSteamId;
      private final long generation;
      private final UUID minecraftUuid;
      private final String minecraftName;

      private Result(boolean allowed, String reason, long internalSteamId, long generation, UUID minecraftUuid, String minecraftName) {
         this.allowed = allowed;
         this.reason = reason;
         this.internalSteamId = internalSteamId;
         this.generation = generation;
         this.minecraftUuid = minecraftUuid;
         this.minecraftName = minecraftName;
      }

      static DedicatedAdmissionGate.Result allowCore() {
         return new DedicatedAdmissionGate.Result(true, "", 0L, 0L, null, null);
      }

      static DedicatedAdmissionGate.Result allow(long id, long generation, UUID uuid, String name) {
         return new DedicatedAdmissionGate.Result(true, "", id, generation, uuid, name);
      }

      static DedicatedAdmissionGate.Result deny(String reason) {
         return new DedicatedAdmissionGate.Result(false, reason, 0L, 0L, null, null);
      }

      public boolean allowed() {
         return this.allowed;
      }

      public String reason() {
         return this.reason;
      }

      public long internalSteamId() {
         return this.internalSteamId;
      }

      public long generation() {
         return this.generation;
      }

      public UUID minecraftUuid() {
         return this.minecraftUuid;
      }

      public String minecraftName() {
         return this.minecraftName;
      }

      @Override
      public String toString() {
         return "DedicatedAdmissionResult{" + (this.allowed ? "allowed" : "denied:" + this.reason) + "}";
      }
   }
}
