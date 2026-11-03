package link.e4steam.api.diagnostics;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Map.Entry;
import java.util.concurrent.CompletionStage;
import link.e4steam.api.ApiResult;
import link.e4steam.api.ApiValidation;
import link.e4steam.api.Registration;

public interface DiagnosticsService {
   ApiResult<DiagnosticsService.HealthSnapshot> health();

   ApiResult<Registration> registerContributor(DiagnosticsService.DiagnosticsContributor var1);

   CompletionStage<ApiResult<DiagnosticsService.DoctorPreview>> doctorPreview(DiagnosticsService.PrivacyOptions var1);

   public static final class ComponentHealth {
      private final String componentId;
      private final DiagnosticsService.Health health;
      private final String safeCode;

      public ComponentHealth(String componentId, DiagnosticsService.Health health, String safeCode) {
         this.componentId = ApiValidation.text(componentId, "componentId", 96);
         this.health = Objects.requireNonNull(health, "health");
         this.safeCode = ApiValidation.text(safeCode, "safeCode", 96);
      }

      public String componentId() {
         return this.componentId;
      }

      public DiagnosticsService.Health health() {
         return this.health;
      }

      public String safeCode() {
         return this.safeCode;
      }

      @Override
      public String toString() {
         return "ComponentHealth{id='" + this.componentId + "', health=" + this.health + ", code='" + this.safeCode + "'}";
      }
   }

   public interface DiagnosticsContributor {
      String id();

      CompletionStage<ApiResult<DiagnosticsService.DiagnosticsSection>> contribute();
   }

   public static final class DiagnosticsSection {
      private final String id;
      private final Map<String, String> fields;

      public DiagnosticsSection(String id, Map<String, String> fields) {
         this.id = ApiValidation.text(id, "section id", 96);
         if (fields != null && fields.size() <= 256) {
            LinkedHashMap<String, String> copy = new LinkedHashMap<>();

            for (Entry<String, String> entry : fields.entrySet()) {
               String key = ApiValidation.text(entry.getKey(), "field key", 64);
               ApiValidation.rejectSensitiveName(key, "field key");
               String value = ApiValidation.text(entry.getValue(), "field value", 4096);
               if (looksSensitive(value)) {
                  throw new IllegalArgumentException("diagnostic value appears sensitive");
               }

               copy.put(key, redactHome(value));
            }

            this.fields = Collections.unmodifiableMap(copy);
         } else {
            throw new IllegalArgumentException("invalid diagnostics fields");
         }
      }

      public String id() {
         return this.id;
      }

      public Map<String, String> fields() {
         return this.fields;
      }

      @Override
      public String toString() {
         return "DiagnosticsSection{id='" + this.id + "', fieldNames=" + this.fields.keySet() + '}';
      }

      private static boolean looksSensitive(String value) {
         String lower = value.toLowerCase(Locale.ROOT);
         return lower.contains("bearer ")
            || lower.contains("begin openssh private key")
            || lower.contains("steamdatagram cert")
            || lower.matches(".*(?:token|ticket|password|secret|cookie|authorization|gslt)=[^\\s]+.*");
      }

      private static String redactHome(String value) {
         String home = System.getProperty("user.home", "");
         return home.isEmpty() ? value : value.replace(home, "<user-home>");
      }
   }

   public static final class DoctorPreview {
      private final List<DiagnosticsService.DiagnosticsSection> sections;
      private final List<String> redactions;
      private final int encodedBytes;

      public DoctorPreview(List<DiagnosticsService.DiagnosticsSection> sections, List<String> redactions, int encodedBytes) {
         this.sections = ApiValidation.immutableList(sections, 128, "sections");
         this.redactions = ApiValidation.immutableList(redactions, 128, "redactions");
         if (encodedBytes >= 0 && encodedBytes <= 2097152) {
            this.encodedBytes = encodedBytes;
         } else {
            throw new IllegalArgumentException("invalid encodedBytes");
         }
      }

      public List<DiagnosticsService.DiagnosticsSection> sections() {
         return this.sections;
      }

      public List<String> redactions() {
         return this.redactions;
      }

      public int encodedBytes() {
         return this.encodedBytes;
      }

      @Override
      public String toString() {
         return "DoctorPreview{sections=" + this.sections.size() + ", redactions=" + this.redactions.size() + ", bytes=" + this.encodedBytes + '}';
      }
   }

   public static enum Health {
      HEALTHY,
      DEGRADED,
      UNHEALTHY,
      UNAVAILABLE;
   }

   public static final class HealthSnapshot {
      private final List<DiagnosticsService.ComponentHealth> components;

      public HealthSnapshot(List<DiagnosticsService.ComponentHealth> components) {
         this.components = ApiValidation.immutableList(components, 256, "components");
      }

      public List<DiagnosticsService.ComponentHealth> components() {
         return this.components;
      }

      @Override
      public String toString() {
         return "HealthSnapshot{components=" + this.components.size() + '}';
      }
   }

   public static final class PrivacyOptions {
      private final boolean includeSteamIds;
      private final boolean includeLobbyIds;

      public PrivacyOptions(boolean includeSteamIds, boolean includeLobbyIds) {
         this.includeSteamIds = includeSteamIds;
         this.includeLobbyIds = includeLobbyIds;
      }

      public boolean includeSteamIds() {
         return this.includeSteamIds;
      }

      public boolean includeLobbyIds() {
         return this.includeLobbyIds;
      }
   }
}
