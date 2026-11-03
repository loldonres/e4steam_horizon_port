package link.e4steam.internal.api;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import link.e4steam.api.directory.PublicDirectoryService;

final class DevelopmentRegistryAttestation {
   static final String ENABLE_PROPERTY = "e4steam.publicDirectory.allowDevelopmentLoopbackAttestation";
   static final String KEY_ENVIRONMENT = "E4STEAM_REGISTRY_DEV_HMAC_KEY";
   static final String KEY_FILE_PROPERTY = "e4steam.publicDirectory.developmentKeyFile";
   private static final int MAX_RESPONSE_BYTES = 8192;
   private static final Pattern FIELD = Pattern.compile("\\\"([^\\\"]+)\\\"\\s*:");
   private static final Pattern RECEIPT = Pattern.compile("\\\"receipt\\\"\\s*:\\s*\\\"([A-Za-z0-9._:-]{12,256})\\\"");
   private static final Pattern OWNER = Pattern.compile("\\\"ownerRef\\\"\\s*:\\s*\\\"([A-Za-z0-9._:-]{12,96})\\\"");
   private static final Pattern EXPIRY = Pattern.compile("\\\"expiresAt\\\"\\s*:\\s*([0-9]{13})");
   private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5L)).followRedirects(Redirect.NEVER).build();

   private DevelopmentRegistryAttestation() {
   }

   static boolean enabledFor(PublicDirectoryService.AttestationChallenge challenge) {
      if (challenge != null && Boolean.getBoolean("e4steam.publicDirectory.allowDevelopmentLoopbackAttestation")) {
         URI origin = URI.create(challenge.origin().value());
         return "http".equalsIgnoreCase(origin.getScheme()) && loopback(origin.getHost()) && configuredKey();
      } else {
         return false;
      }
   }

   static CompletionStage<PublicDirectoryService.AttestationReceipt> attest(PublicDirectoryService.AttestationChallenge challenge, String ownerRef) {
      try {
         byte[] key = key();
         String authority = authority(challenge.action());
         String type = type(challenge.action());
         long now = System.currentTimeMillis();
         long expiry = Math.min(challenge.expiresAtEpochMillis(), now + 30000L);
         if (expiry <= now) {
            throw new IllegalArgumentException("expired challenge");
         } else {
            String encodedOwner = Base64.getUrlEncoder().withoutPadding().encodeToString(ownerRef.getBytes(StandardCharsets.UTF_8));
            String canonical = "e4steam-registry-dev-attestation-v1\n"
               + challenge.challengeId()
               + "\n"
               + challenge.nonce()
               + "\n"
               + challenge.origin().value()
               + "\n"
               + challenge.action().name()
               + "\n"
               + type
               + "\n"
               + authority
               + "\n"
               + encodedOwner
               + "\n"
               + expiry;
            String assertion = "dev1."
               + authority
               + "."
               + encodedOwner
               + "."
               + expiry
               + "."
               + Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(key, canonical));
            Arrays.fill(key, (byte)0);
            String body = "{\"challengeId\":" + json(challenge.challengeId()) + ",\"assertion\":" + json(assertion) + "}";
            URI endpoint = URI.create(challenge.origin().value() + "/v1/attestations");
            HttpRequest request = HttpRequest.newBuilder(endpoint)
               .timeout(Duration.ofSeconds(10L))
               .header("Accept", "application/json")
               .header("Content-Type", "application/json")
               .header("User-Agent", "e4steam-core/dev-attestation")
               .POST(BodyPublishers.ofString(body, StandardCharsets.UTF_8))
               .build();
            return HTTP.sendAsync(request, BodyHandlers.ofInputStream()).thenApply(DevelopmentRegistryAttestation::decode);
         }
      } catch (RuntimeException var15) {
         return CompletableFuture.failedFuture(var15);
      }
   }

   static String opaqueOwner(String namespace, String material) {
      if (namespace != null && namespace.matches("[a-z]{3,12}") && material != null && !material.isEmpty()) {
         try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
               .digest(("e4steam-directory-dev-owner-v1\n" + namespace + "\n" + material).getBytes(StandardCharsets.UTF_8));
            return "dev" + namespace + "_" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
         } catch (GeneralSecurityException var3) {
            throw new IllegalStateException("SHA-256 unavailable", var3);
         }
      } else {
         throw new IllegalArgumentException("invalid development owner material");
      }
   }

   private static PublicDirectoryService.AttestationReceipt decode(HttpResponse<InputStream> response) {
      String body;
      try (InputStream input = response.body()) {
         byte[] bytes = input.readNBytes(8193);
         if (bytes.length > 8192) {
            throw new IllegalStateException("oversized response");
         }

         body = new String(bytes, StandardCharsets.UTF_8);
      } catch (IOException var13) {
         throw new IllegalStateException("registry response unavailable", var13);
      }

      if (response.statusCode() == 401 || response.statusCode() == 403) {
         throw new SecurityException("development attestation rejected");
      } else if (response.statusCode() != 201) {
         throw new IllegalStateException("development registry unavailable");
      } else {
         String contentType = response.headers().firstValue("Content-Type").orElse("");
         if (!contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
            throw new IllegalStateException("invalid registry response type");
         } else {
            Set<String> fields = new HashSet<>();
            Matcher names = FIELD.matcher(body);

            while (names.find()) {
               fields.add(names.group(1));
            }

            if (!fields.equals(Set.of("receipt", "ownerRef", "expiresAt"))) {
               throw new IllegalStateException("invalid registry response fields");
            } else {
               String receipt = one(RECEIPT, body);
               String owner = one(OWNER, body);

               long expiry;
               try {
                  expiry = Long.parseLong(one(EXPIRY, body));
               } catch (NumberFormatException var11) {
                  throw new IllegalStateException("invalid registry response expiry", var11);
               }

               return new PublicDirectoryService.AttestationReceipt(owner, receipt, expiry);
            }
         }
      }
   }

   private static String one(Pattern pattern, String body) {
      Matcher matcher = pattern.matcher(body);
      if (!matcher.find()) {
         throw new IllegalStateException("invalid registry response");
      } else {
         String value = matcher.group(1);
         if (matcher.find()) {
            throw new IllegalStateException("duplicate registry response field");
         } else {
            return value;
         }
      }
   }

   private static byte[] key() {
      String raw = System.getenv("E4STEAM_REGISTRY_DEV_HMAC_KEY");
      if (raw == null || raw.isBlank()) {
         String configuredPath = System.getProperty("e4steam.publicDirectory.developmentKeyFile", "").trim();
         if (!configuredPath.isEmpty()) {
            try {
               Path path = Path.of(configuredPath).toAbsolutePath().normalize();
               if (!Files.isRegularFile(path) || Files.size(path) > 256L) {
                  throw new IllegalStateException("invalid development attestation key file");
               }

               raw = Files.readString(path, StandardCharsets.US_ASCII).trim();
            } catch (RuntimeException | IOException var4) {
               throw new IllegalStateException("development attestation key file unavailable", var4);
            }
         }
      }

      if (raw != null && !raw.isBlank() && raw.length() <= 128) {
         try {
            byte[] key = Base64.getUrlDecoder().decode(raw);
            if (key.length >= 32 && key.length <= 64) {
               return key;
            } else {
               throw new IllegalArgumentException("invalid key length");
            }
         } catch (IllegalArgumentException var3) {
            throw new IllegalStateException("invalid development attestation key", var3);
         }
      } else {
         throw new IllegalStateException("development attestation key unavailable");
      }
   }

   private static boolean configuredKey() {
      String environment = System.getenv("E4STEAM_REGISTRY_DEV_HMAC_KEY");
      if (environment != null && !environment.isBlank()) {
         return true;
      } else {
         String path = System.getProperty("e4steam.publicDirectory.developmentKeyFile", "");
         return path != null && !path.trim().isEmpty();
      }
   }

   private static byte[] hmac(byte[] key, String canonical) {
      try {
         Mac mac = Mac.getInstance("HmacSHA256");
         mac.init(new SecretKeySpec(key, "HmacSHA256"));
         return mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
      } catch (GeneralSecurityException var3) {
         throw new IllegalStateException("HmacSHA256 unavailable", var3);
      }
   }

   private static String authority(PublicDirectoryService.AttestationAction action) {
      return switch (action) {
         case PUBLISH_INTEGRATED -> "user";
         case PUBLISH_DEDICATED -> "server";
         default -> throw new IllegalArgumentException("unsupported attestation action");
      };
   }

   private static String type(PublicDirectoryService.AttestationAction action) {
      return switch (action) {
         case PUBLISH_INTEGRATED -> "INTEGRATED_WORLD";
         case PUBLISH_DEDICATED -> "DEDICATED_SERVER";
         default -> throw new IllegalArgumentException("unsupported attestation action");
      };
   }

   private static String json(String value) {
      StringBuilder result = new StringBuilder(value.length() + 2).append('"');

      for (int index = 0; index < value.length(); index++) {
         char character = value.charAt(index);
         if (character == '"' || character == '\\') {
            result.append('\\');
         }

         if (character < ' ') {
            throw new IllegalArgumentException("invalid JSON text");
         }

         result.append(character);
      }

      return result.append('"').toString();
   }

   private static boolean loopback(String host) {
      return host != null && (host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1") || host.equals("::1") || host.equals("0:0:0:0:0:0:0:1"));
   }
}
