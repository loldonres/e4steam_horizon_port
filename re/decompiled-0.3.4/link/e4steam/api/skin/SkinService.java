package link.e4steam.api.skin;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import link.e4steam.api.ApiResult;
import link.e4steam.api.ApiValidation;
import link.e4steam.api.Registration;
import link.e4steam.api.identity.IdentityService;

public interface SkinService {
   ApiResult<Registration> registerProvider(SkinService.SkinProvider var1);

   CompletionStage<ApiResult<SkinService.SkinResult>> resolve(SkinService.SkinRequest var1);

   public static enum RejectionReason {
      NOT_FOUND,
      INVALID_FORMAT,
      INVALID_DIMENSIONS,
      TOO_LARGE,
      HASH_MISMATCH,
      TIMEOUT,
      PROVIDER_FAILED,
      CONSENT_REQUIRED;
   }

   public static final class SkinAsset {
      private final SkinService.SkinModel model;
      private final byte[] png;
      private final int width;
      private final int height;
      private final String sha256;
      private final String provenance;
      private final long expiresAtEpochMillis;

      public SkinAsset(SkinService.SkinModel model, byte[] png, String sha256, String provenance, long expiresAtEpochMillis) {
         this.model = Objects.requireNonNull(model, "model");
         this.png = ApiValidation.bytes(png, 2097152, "png");
         if (this.png.length >= 24
            && (this.png[0] & 255) == 137
            && this.png[1] == 80
            && this.png[2] == 78
            && this.png[3] == 71
            && this.png[4] == 13
            && this.png[5] == 10
            && this.png[6] == 26
            && this.png[7] == 10) {
            this.width = readInt(this.png, 16);
            this.height = readInt(this.png, 20);
            if (this.width == 64 && (this.height == 32 || this.height == 64) || this.width == 128 && this.height == 128) {
               if (sha256 != null && sha256.matches("^[a-f0-9]{64}$")) {
                  this.sha256 = sha256;
                  this.provenance = ApiValidation.text(provenance, "provenance", 256);
                  ApiValidation.rejectSensitiveName(this.provenance, "provenance");
                  if (expiresAtEpochMillis < 0L) {
                     throw new IllegalArgumentException("invalid expiry");
                  } else {
                     this.expiresAtEpochMillis = expiresAtEpochMillis;
                  }
               } else {
                  throw new IllegalArgumentException("invalid sha256");
               }
            } else {
               throw new IllegalArgumentException("unsupported skin dimensions");
            }
         } else {
            throw new IllegalArgumentException("invalid PNG magic");
         }
      }

      public SkinService.SkinModel model() {
         return this.model;
      }

      public byte[] png() {
         return (byte[])this.png.clone();
      }

      public int width() {
         return this.width;
      }

      public int height() {
         return this.height;
      }

      public String sha256() {
         return this.sha256;
      }

      public String provenance() {
         return this.provenance;
      }

      public long expiresAtEpochMillis() {
         return this.expiresAtEpochMillis;
      }

      @Override
      public String toString() {
         return "SkinAsset{model=" + this.model + ", dimensions=" + this.width + 'x' + this.height + ", sha256=present, bytes=" + this.png.length + '}';
      }

      private static int readInt(byte[] data, int offset) {
         return (data[offset] & 0xFF) << 24 | (data[offset + 1] & 0xFF) << 16 | (data[offset + 2] & 0xFF) << 8 | data[offset + 3] & 0xFF;
      }
   }

   public static enum SkinModel {
      CLASSIC,
      SLIM;
   }

   public interface SkinProvider {
      String id();

      CompletionStage<ApiResult<SkinService.SkinResult>> resolve(SkinService.SkinRequest var1);
   }

   public static final class SkinRequest {
      private final IdentityService.MinecraftIdentity identity;
      private final boolean capeRequested;

      public SkinRequest(IdentityService.MinecraftIdentity identity, boolean capeRequested) {
         this.identity = Objects.requireNonNull(identity, "identity");
         this.capeRequested = capeRequested;
      }

      public IdentityService.MinecraftIdentity identity() {
         return this.identity;
      }

      public boolean capeRequested() {
         return this.capeRequested;
      }

      @Override
      public String toString() {
         return "SkinRequest{uuid=" + this.identity.uuid() + ", cape=" + this.capeRequested + '}';
      }
   }

   public static final class SkinResult {
      private final SkinService.SkinAsset asset;
      private final SkinService.RejectionReason fallbackReason;

      private SkinResult(SkinService.SkinAsset asset, SkinService.RejectionReason fallbackReason) {
         this.asset = asset;
         this.fallbackReason = fallbackReason;
      }

      public static SkinService.SkinResult success(SkinService.SkinAsset asset) {
         return new SkinService.SkinResult(Objects.requireNonNull(asset, "asset"), null);
      }

      public static SkinService.SkinResult fallback(SkinService.RejectionReason reason) {
         return new SkinService.SkinResult(null, Objects.requireNonNull(reason, "reason"));
      }

      public Optional<SkinService.SkinAsset> asset() {
         return Optional.ofNullable(this.asset);
      }

      public Optional<SkinService.RejectionReason> fallbackReason() {
         return Optional.ofNullable(this.fallbackReason);
      }

      @Override
      public String toString() {
         return this.asset == null ? "SkinResult{fallback=" + this.fallbackReason + '}' : "SkinResult{asset=present}";
      }
   }
}
