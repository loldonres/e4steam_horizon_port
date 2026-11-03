package link.e4steam.api.udp;

import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.regex.Pattern;
import link.e4steam.api.ApiResult;
import link.e4steam.api.ApiValidation;
import link.e4steam.api.Registration;
import link.e4steam.api.identity.IdentityService;
import link.e4steam.api.session.SessionService;

public interface UdpService {
   ApiResult<UdpService.EndpointHandle> register(UdpService.EndpointDescriptor var1, UdpService.DatagramHandler var2);

   public static final class Datagram {
      private final SessionService.SessionId sessionId;
      private final IdentityService.PeerId peerId;
      private final UdpService.EndpointId endpointId;
      private final byte[] payload;

      public Datagram(SessionService.SessionId sessionId, IdentityService.PeerId peerId, UdpService.EndpointId endpointId, byte[] payload) {
         this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
         this.peerId = Objects.requireNonNull(peerId, "peerId");
         this.endpointId = Objects.requireNonNull(endpointId, "endpointId");
         this.payload = ApiValidation.bytes(payload, 65507, "payload");
      }

      public SessionService.SessionId sessionId() {
         return this.sessionId;
      }

      public IdentityService.PeerId peerId() {
         return this.peerId;
      }

      public UdpService.EndpointId endpointId() {
         return this.endpointId;
      }

      public byte[] payload() {
         return (byte[])this.payload.clone();
      }

      @Override
      public String toString() {
         return "Datagram{endpoint=" + this.endpointId + ", bytes=" + this.payload.length + '}';
      }
   }

   public interface DatagramHandler {
      CompletionStage<ApiResult<Boolean>> onDatagram(UdpService.Datagram var1);
   }

   public static final class EndpointDescriptor {
      private final UdpService.EndpointId id;
      private final int maximumDatagramBytes;
      private final int datagramsPerSecond;
      private final String discriminator;

      public EndpointDescriptor(UdpService.EndpointId id, int maximumDatagramBytes, int datagramsPerSecond, String discriminator) {
         this.id = Objects.requireNonNull(id, "id");
         if (maximumDatagramBytes < 1 || maximumDatagramBytes > 65507) {
            throw new IllegalArgumentException("invalid maximumDatagramBytes");
         } else if (datagramsPerSecond >= 1 && datagramsPerSecond <= 1000) {
            this.maximumDatagramBytes = maximumDatagramBytes;
            this.datagramsPerSecond = datagramsPerSecond;
            this.discriminator = ApiValidation.optionalText(discriminator, "discriminator", 64);
            ApiValidation.rejectSensitiveName(this.discriminator, "discriminator");
         } else {
            throw new IllegalArgumentException("invalid datagramsPerSecond");
         }
      }

      public UdpService.EndpointId id() {
         return this.id;
      }

      public int maximumDatagramBytes() {
         return this.maximumDatagramBytes;
      }

      public int datagramsPerSecond() {
         return this.datagramsPerSecond;
      }

      public String discriminator() {
         return this.discriminator;
      }
   }

   public interface EndpointHandle extends Registration {
      UdpService.EndpointDescriptor descriptor();

      boolean ready();

      CompletionStage<ApiResult<Boolean>> send(UdpService.Datagram var1);
   }

   public static final class EndpointId {
      private static final Pattern FORMAT = Pattern.compile("^[a-z][a-z0-9_.-]{0,31}:[a-z][a-z0-9_./-]{0,63}$");
      private final String value;

      public EndpointId(String value) {
         this.value = ApiValidation.identifier(value, "endpointId", FORMAT);
      }

      public String value() {
         return this.value;
      }

      @Override
      public boolean equals(Object other) {
         return this == other || other instanceof UdpService.EndpointId && this.value.equals(((UdpService.EndpointId)other).value);
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
}
