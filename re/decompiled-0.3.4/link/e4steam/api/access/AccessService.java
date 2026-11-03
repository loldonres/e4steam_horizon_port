package link.e4steam.api.access;

import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.regex.Pattern;
import link.e4steam.api.ApiResult;
import link.e4steam.api.ApiValidation;
import link.e4steam.api.Registration;
import link.e4steam.api.addon.AddonId;
import link.e4steam.api.identity.IdentityService;
import link.e4steam.api.session.SessionService;

public interface AccessService {
   ApiResult<Registration> register(AccessService.AccessModeProvider var1);

   CompletionStage<ApiResult<AccessService.AdmissionDecision>> evaluate(AccessService.AccessModeId var1, AccessService.AdmissionContext var2);

   boolean registrationsFrozen();

   public static final class AccessModeId {
      private static final Pattern FORMAT = Pattern.compile("^[a-z][a-z0-9_.-]{0,31}:[a-z][a-z0-9_.-]{0,63}$");
      private final String value;

      public AccessModeId(String value) {
         this.value = ApiValidation.identifier(value, "accessModeId", FORMAT);
      }

      public String value() {
         return this.value;
      }

      @Override
      public boolean equals(Object other) {
         return this == other || other instanceof AccessService.AccessModeId && this.value.equals(((AccessService.AccessModeId)other).value);
      }

      @Override
      public int hashCode() {
         return this.value.hashCode();
      }

      @Override
      public String toString() {
         return this.value;
      }
   }

   public interface AccessModeProvider {
      AccessService.AccessModeId id();

      String displayNameKey();

      AccessService.AdmissionPolicy policy();
   }

   public static final class AdmissionContext {
      private final SessionService.SessionId sessionId;
      private final IdentityService.PeerId peerId;
      private final AddonId modeOwner;
      private final boolean coreAuthenticated;

      public AdmissionContext(SessionService.SessionId sessionId, IdentityService.PeerId peerId, AddonId modeOwner, boolean coreAuthenticated) {
         this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
         this.peerId = Objects.requireNonNull(peerId, "peerId");
         this.modeOwner = Objects.requireNonNull(modeOwner, "modeOwner");
         if (!coreAuthenticated) {
            throw new IllegalArgumentException("custom policy cannot receive an unauthenticated peer");
         } else {
            this.coreAuthenticated = true;
         }
      }

      public SessionService.SessionId sessionId() {
         return this.sessionId;
      }

      public IdentityService.PeerId peerId() {
         return this.peerId;
      }

      public AddonId modeOwner() {
         return this.modeOwner;
      }

      public boolean coreAuthenticated() {
         return this.coreAuthenticated;
      }

      @Override
      public String toString() {
         return "AdmissionContext{session=" + this.sessionId + ", peer=" + this.peerId + '}';
      }
   }

   public static final class AdmissionDecision {
      private final AccessService.AdmissionDecision.Kind kind;
      private final String reasonCode;
      private final String challengeId;
      private final long expiresInMillis;

      private AdmissionDecision(AccessService.AdmissionDecision.Kind kind, String reasonCode, String challengeId, long expiresInMillis) {
         this.kind = kind;
         this.reasonCode = reasonCode;
         this.challengeId = challengeId;
         this.expiresInMillis = expiresInMillis;
      }

      public static AccessService.AdmissionDecision allow() {
         return new AccessService.AdmissionDecision(AccessService.AdmissionDecision.Kind.ALLOW, "", "", 0L);
      }

      public static AccessService.AdmissionDecision deny(String reasonCode) {
         return new AccessService.AdmissionDecision(AccessService.AdmissionDecision.Kind.DENY, ApiValidation.text(reasonCode, "reasonCode", 96), "", 0L);
      }

      public static AccessService.AdmissionDecision challenge(String challengeId, long expiresInMillis) {
         String id = ApiValidation.text(challengeId, "challengeId", 96);
         ApiValidation.rejectSensitiveName(id, "challengeId");
         if (expiresInMillis > 0L && expiresInMillis <= 30000L) {
            return new AccessService.AdmissionDecision(AccessService.AdmissionDecision.Kind.CHALLENGE, "", id, expiresInMillis);
         } else {
            throw new IllegalArgumentException("invalid challenge expiry");
         }
      }

      public AccessService.AdmissionDecision.Kind kind() {
         return this.kind;
      }

      public String reasonCode() {
         return this.reasonCode;
      }

      public String challengeId() {
         return this.challengeId;
      }

      public long expiresInMillis() {
         return this.expiresInMillis;
      }

      @Override
      public String toString() {
         return "AdmissionDecision{" + this.kind + (this.reasonCode.isEmpty() ? "" : ", reason=" + this.reasonCode) + '}';
      }

      public static enum Kind {
         ALLOW,
         DENY,
         CHALLENGE;
      }
   }

   public interface AdmissionPolicy {
      CompletionStage<AccessService.AdmissionDecision> evaluate(AccessService.AdmissionContext var1);
   }

   public interface ConfirmableAccessModeProvider extends AccessService.AccessModeProvider {
      String confirmationTitleKey();

      String confirmationMessageKey();
   }
}
