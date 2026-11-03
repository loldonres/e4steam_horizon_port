package link.e4steam.api.session;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.regex.Pattern;
import link.e4steam.api.ApiResult;
import link.e4steam.api.ApiValidation;
import link.e4steam.api.Registration;
import link.e4steam.api.identity.IdentityService;

public interface SessionService {
   ApiResult<SessionService.SessionSnapshot> snapshot();

   CompletionStage<ApiResult<SessionService.PeerPage>> peers(SessionService.SessionId var1, String var2, int var3);

   CompletionStage<ApiResult<SessionService.SessionSnapshot>> disconnect(SessionService.SessionId var1, String var2);

   CompletionStage<ApiResult<SessionService.SessionSnapshot>> readiness();

   ApiResult<Registration> registerResource(SessionService.SessionId var1, Registration var2);

   public static final class PeerPage {
      private final List<SessionService.PeerSnapshot> peers;
      private final String nextCursor;

      public PeerPage(List<SessionService.PeerSnapshot> peers, String nextCursor) {
         this.peers = ApiValidation.immutableList(peers, 100, "peers");
         this.nextCursor = ApiValidation.optionalText(nextCursor, "nextCursor", 256);
      }

      public List<SessionService.PeerSnapshot> peers() {
         return this.peers;
      }

      public String nextCursor() {
         return this.nextCursor;
      }
   }

   public static final class PeerSnapshot {
      private final IdentityService.PeerId peerId;
      private final boolean authenticated;

      public PeerSnapshot(IdentityService.PeerId peerId, boolean authenticated) {
         this.peerId = Objects.requireNonNull(peerId, "peerId");
         this.authenticated = authenticated;
      }

      public IdentityService.PeerId peerId() {
         return this.peerId;
      }

      public boolean authenticated() {
         return this.authenticated;
      }
   }

   public static final class SessionId {
      private static final Pattern FORMAT = Pattern.compile("^[A-Za-z0-9_-]{8,96}$");
      private final String value;
      private final long generation;

      public SessionId(String value, long generation) {
         this.value = ApiValidation.identifier(value, "sessionId", FORMAT);
         if (generation <= 0L) {
            throw new IllegalArgumentException("generation must be positive");
         } else {
            this.generation = generation;
         }
      }

      public String value() {
         return this.value;
      }

      public long generation() {
         return this.generation;
      }

      @Override
      public boolean equals(Object other) {
         if (this == other) {
            return true;
         } else if (!(other instanceof SessionService.SessionId)) {
            return false;
         } else {
            SessionService.SessionId id = (SessionService.SessionId)other;
            return this.generation == id.generation && this.value.equals(id.value);
         }
      }

      @Override
      public int hashCode() {
         return 31 * this.value.hashCode() + (int)(this.generation ^ this.generation >>> 32);
      }

      @Override
      public String toString() {
         return "SessionId{generation=" + this.generation + '}';
      }
   }

   public static enum SessionRole {
      NONE,
      INTEGRATED_HOST,
      GUEST,
      DEDICATED_SERVER_CLIENT,
      DEDICATED_SERVER;
   }

   public static final class SessionSnapshot {
      private final SessionService.SessionId id;
      private final SessionService.SessionRole role;
      private final SessionService.SessionState state;
      private final int peers;
      private final int capacity;
      private final Set<String> features;

      public SessionSnapshot(
         SessionService.SessionId id, SessionService.SessionRole role, SessionService.SessionState state, int peers, int capacity, Set<String> features
      ) {
         this.id = Objects.requireNonNull(id, "id");
         this.role = Objects.requireNonNull(role, "role");
         this.state = Objects.requireNonNull(state, "state");
         if (peers >= 0 && capacity >= 0 && peers <= capacity && capacity <= 256) {
            this.peers = peers;
            this.capacity = capacity;
            LinkedHashSet<String> copy = new LinkedHashSet<>();
            if (features != null && features.size() <= 256) {
               for (String feature : features) {
                  copy.add(ApiValidation.text(feature, "feature", 96));
               }

               this.features = Collections.unmodifiableSet(copy);
            } else {
               throw new IllegalArgumentException("invalid feature set");
            }
         } else {
            throw new IllegalArgumentException("invalid peer capacity");
         }
      }

      public SessionService.SessionId id() {
         return this.id;
      }

      public SessionService.SessionRole role() {
         return this.role;
      }

      public SessionService.SessionState state() {
         return this.state;
      }

      public int peers() {
         return this.peers;
      }

      public int capacity() {
         return this.capacity;
      }

      public Set<String> features() {
         return this.features;
      }

      @Override
      public String toString() {
         return "SessionSnapshot{id=" + this.id + ", role=" + this.role + ", state=" + this.state + ", peers=" + this.peers + '/' + this.capacity + '}';
      }
   }

   public static enum SessionState {
      NONE,
      CREATING,
      ACTIVE,
      RECONNECTING,
      CLOSING,
      CLOSED,
      FAILED;
   }
}
