package link.e4steam.api.directory;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.regex.Pattern;
import link.e4steam.api.ApiResult;
import link.e4steam.api.ApiValidation;

public interface PublicDirectoryService {
   ApiResult<PublicDirectoryService.DirectoryAvailability> availability();

   CompletionStage<ApiResult<PublicDirectoryService.PublicationTarget>> publicationTarget(PublicDirectoryService.PublicationTargetRequest var1);

   CompletionStage<ApiResult<PublicDirectoryService.AttestationReceipt>> attest(PublicDirectoryService.AttestationChallenge var1);

   CompletionStage<ApiResult<PublicDirectoryService.JoinOperation>> join(PublicDirectoryService.JoinRequest var1);

   CompletionStage<ApiResult<PublicDirectoryService.JoinOperation>> cancel(PublicDirectoryService.JoinOperationId var1);

   ApiResult<PublicDirectoryService.JoinOperation> joinSnapshot(PublicDirectoryService.JoinOperationId var1);

   static String checked(String value, String field, Pattern pattern) {
      if (value == null) {
         throw new NullPointerException(field);
      } else {
         String checked = value.trim();
         if (checked.length() <= 512 && pattern.matcher(checked).matches()) {
            return checked;
         } else {
            throw new IllegalArgumentException(field + " has an invalid format");
         }
      }
   }

   public static enum AttestationAction {
      PUBLISH_INTEGRATED,
      PUBLISH_DEDICATED,
      UPDATE,
      DELETE;
   }

   public static final class AttestationChallenge {
      private static final Pattern OPAQUE = Pattern.compile("^[A-Za-z0-9._-]{16,256}$");
      private final PublicDirectoryService.DirectoryOrigin origin;
      private final String challengeId;
      private final String nonce;
      private final PublicDirectoryService.AttestationAction action;
      private final long expiresAtEpochMillis;

      public AttestationChallenge(
         PublicDirectoryService.DirectoryOrigin origin,
         String challengeId,
         String nonce,
         PublicDirectoryService.AttestationAction action,
         long expiresAtEpochMillis
      ) {
         this.origin = Objects.requireNonNull(origin, "origin");
         this.challengeId = PublicDirectoryService.checked(challengeId, "challengeId", OPAQUE);
         this.nonce = PublicDirectoryService.checked(nonce, "nonce", OPAQUE);
         this.action = Objects.requireNonNull(action, "action");
         if (expiresAtEpochMillis <= 0L) {
            throw new IllegalArgumentException("invalid expiry");
         } else {
            this.expiresAtEpochMillis = expiresAtEpochMillis;
         }
      }

      public PublicDirectoryService.DirectoryOrigin origin() {
         return this.origin;
      }

      public String challengeId() {
         return this.challengeId;
      }

      public String nonce() {
         return this.nonce;
      }

      public PublicDirectoryService.AttestationAction action() {
         return this.action;
      }

      public long expiresAtEpochMillis() {
         return this.expiresAtEpochMillis;
      }

      @Override
      public String toString() {
         return "AttestationChallenge{origin=" + this.origin + ", action=" + this.action + ", expires=" + this.expiresAtEpochMillis + '}';
      }
   }

   public static final class AttestationReceipt {
      private static final Pattern OPAQUE = Pattern.compile("^[A-Za-z0-9._:-]{12,512}$");
      private final String ownerRef;
      private final String receiptRef;
      private final long expiresAtEpochMillis;

      public AttestationReceipt(String ownerRef, String receiptRef, long expiresAtEpochMillis) {
         this.ownerRef = PublicDirectoryService.checked(ownerRef, "ownerRef", OPAQUE);
         this.receiptRef = PublicDirectoryService.checked(receiptRef, "receiptRef", OPAQUE);
         if (expiresAtEpochMillis <= 0L) {
            throw new IllegalArgumentException("invalid expiry");
         } else {
            this.expiresAtEpochMillis = expiresAtEpochMillis;
         }
      }

      public String ownerRef() {
         return this.ownerRef;
      }

      public String receiptRef() {
         return this.receiptRef;
      }

      public long expiresAtEpochMillis() {
         return this.expiresAtEpochMillis;
      }

      @Override
      public String toString() {
         return "AttestationReceipt{redacted, expires=" + this.expiresAtEpochMillis + '}';
      }
   }

   public static enum DirectoryAvailability {
      AVAILABLE,
      NO_ACTIVE_SESSION,
      STEAM_UNAVAILABLE,
      UNSUPPORTED;
   }

   public static final class DirectoryOrigin {
      private final String value;

      public DirectoryOrigin(String value) {
         String checked = ApiValidation.text(value, "directoryOrigin", 256);

         try {
            URI uri = new URI(checked);
            boolean secure = "https".equalsIgnoreCase(uri.getScheme());
            boolean loopbackDevelopment = "http".equalsIgnoreCase(uri.getScheme()) && loopbackLiteral(uri.getHost());
            if ((secure || loopbackDevelopment)
               && uri.getHost() != null
               && !uri.getHost().isEmpty()
               && uri.getUserInfo() == null
               && uri.getQuery() == null
               && uri.getFragment() == null
               && (uri.getPath() == null || uri.getPath().isEmpty() || "/".equals(uri.getPath()))) {
               int port = uri.getPort();
               this.value = new URI(secure ? "https" : "http", null, uri.getHost().toLowerCase(Locale.ROOT), port, null, null, null).toASCIIString();
            } else {
               throw new IllegalArgumentException("directoryOrigin must be HTTPS or loopback HTTP");
            }
         } catch (URISyntaxException var7) {
            throw new IllegalArgumentException("directoryOrigin has an invalid format");
         }
      }

      public String value() {
         return this.value;
      }

      @Override
      public boolean equals(Object other) {
         return this == other
            || other instanceof PublicDirectoryService.DirectoryOrigin && this.value.equals(((PublicDirectoryService.DirectoryOrigin)other).value);
      }

      @Override
      public int hashCode() {
         return this.value.hashCode();
      }

      @Override
      public String toString() {
         return "DirectoryOrigin{" + this.value + '}';
      }

      private static boolean loopbackLiteral(String host) {
         return host != null && (host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1") || host.equals("::1") || host.equals("0:0:0:0:0:0:0:1"));
      }
   }

   public static final class JoinHandleRef {
      private static final Pattern FORMAT = Pattern.compile("^[A-Za-z0-9._-]{16,256}$");
      private final String value;

      public JoinHandleRef(String value) {
         this.value = PublicDirectoryService.checked(value, "joinHandle", FORMAT);
      }

      public String value() {
         return this.value;
      }

      @Override
      public boolean equals(Object other) {
         return this == other
            || other instanceof PublicDirectoryService.JoinHandleRef && this.value.equals(((PublicDirectoryService.JoinHandleRef)other).value);
      }

      @Override
      public int hashCode() {
         return this.value.hashCode();
      }

      @Override
      public String toString() {
         return "JoinHandleRef{redacted}";
      }
   }

   public static final class JoinOperation {
      private final PublicDirectoryService.JoinOperationId id;
      private final PublicDirectoryService.JoinState state;
      private final String detailCode;

      public JoinOperation(PublicDirectoryService.JoinOperationId id, PublicDirectoryService.JoinState state, String detailCode) {
         this.id = Objects.requireNonNull(id, "id");
         this.state = Objects.requireNonNull(state, "state");
         String detail = ApiValidation.optionalText(detailCode, "detailCode", 96);
         if (!detail.isEmpty() && !detail.matches("[a-z][a-z0-9_.-]{0,95}")) {
            throw new IllegalArgumentException("detailCode has an invalid format");
         } else {
            this.detailCode = detail;
         }
      }

      public PublicDirectoryService.JoinOperationId id() {
         return this.id;
      }

      public PublicDirectoryService.JoinState state() {
         return this.state;
      }

      public String detailCode() {
         return this.detailCode;
      }

      @Override
      public String toString() {
         return "JoinOperation{id=" + this.id + ", state=" + this.state + ", detail=" + this.detailCode + '}';
      }
   }

   public static final class JoinOperationId {
      private static final Pattern FORMAT = Pattern.compile("^[A-Za-z0-9_-]{12,96}$");
      private final String value;

      public JoinOperationId(String value) {
         this.value = PublicDirectoryService.checked(value, "operationId", FORMAT);
      }

      public String value() {
         return this.value;
      }

      @Override
      public boolean equals(Object other) {
         return this == other
            || other instanceof PublicDirectoryService.JoinOperationId && this.value.equals(((PublicDirectoryService.JoinOperationId)other).value);
      }

      @Override
      public int hashCode() {
         return this.value.hashCode();
      }

      @Override
      public String toString() {
         return "JoinOperationId{" + this.value + '}';
      }
   }

   public static final class JoinRequest {
      private final PublicDirectoryService.OpaqueTargetRef target;
      private final PublicDirectoryService.JoinHandleRef handle;

      public JoinRequest(PublicDirectoryService.OpaqueTargetRef target, PublicDirectoryService.JoinHandleRef handle) {
         this.target = Objects.requireNonNull(target, "target");
         this.handle = Objects.requireNonNull(handle, "handle");
      }

      public PublicDirectoryService.OpaqueTargetRef target() {
         return this.target;
      }

      public PublicDirectoryService.JoinHandleRef handle() {
         return this.handle;
      }

      @Override
      public String toString() {
         return "JoinRequest{redacted}";
      }
   }

   public static enum JoinState {
      IDLE,
      RESOLVING,
      JOINING_STEAM_TARGET,
      AUTHENTICATING,
      CONNECTING_MINECRAFT,
      ACTIVE,
      CANCELLED,
      STALE,
      FULL,
      INCOMPATIBLE,
      FAILED;
   }

   public static final class OpaqueTargetRef {
      private static final Pattern FORMAT = Pattern.compile("^[A-Za-z0-9_-]{12,512}$");
      private final String value;

      public OpaqueTargetRef(String value) {
         this.value = PublicDirectoryService.checked(value, "targetRef", FORMAT);
      }

      public String value() {
         return this.value;
      }

      @Override
      public boolean equals(Object other) {
         return this == other
            || other instanceof PublicDirectoryService.OpaqueTargetRef && this.value.equals(((PublicDirectoryService.OpaqueTargetRef)other).value);
      }

      @Override
      public int hashCode() {
         return this.value.hashCode();
      }

      @Override
      public String toString() {
         return "OpaqueTargetRef{redacted}";
      }
   }

   public static final class PublicationTarget {
      private final PublicDirectoryService.TargetKind kind;
      private final PublicDirectoryService.OpaqueTargetRef targetRef;
      private final long generation;

      public PublicationTarget(PublicDirectoryService.TargetKind kind, PublicDirectoryService.OpaqueTargetRef targetRef, long generation) {
         this.kind = Objects.requireNonNull(kind, "kind");
         this.targetRef = Objects.requireNonNull(targetRef, "targetRef");
         if (generation <= 0L) {
            throw new IllegalArgumentException("generation must be positive");
         } else {
            this.generation = generation;
         }
      }

      public PublicDirectoryService.TargetKind kind() {
         return this.kind;
      }

      public PublicDirectoryService.OpaqueTargetRef targetRef() {
         return this.targetRef;
      }

      public long generation() {
         return this.generation;
      }

      @Override
      public String toString() {
         return "PublicationTarget{kind=" + this.kind + ", generation=" + this.generation + '}';
      }
   }

   public static final class PublicationTargetRequest {
      private final PublicDirectoryService.TargetKind kind;
      private final PublicDirectoryService.DirectoryOrigin origin;

      public PublicationTargetRequest(PublicDirectoryService.TargetKind kind, PublicDirectoryService.DirectoryOrigin origin) {
         this.kind = Objects.requireNonNull(kind, "kind");
         this.origin = Objects.requireNonNull(origin, "origin");
      }

      public PublicDirectoryService.TargetKind kind() {
         return this.kind;
      }

      public PublicDirectoryService.DirectoryOrigin origin() {
         return this.origin;
      }
   }

   public static enum TargetKind {
      INTEGRATED_WORLD,
      DEDICATED_SERVER;
   }
}
