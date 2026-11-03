package link.e4steam.api.modpack;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.regex.Pattern;
import link.e4steam.api.ApiResult;
import link.e4steam.api.ApiValidation;
import link.e4steam.api.Registration;

public interface ModpackService {
   ApiResult<Registration> registerProvider(ModpackService.ModpackProvider var1);

   CompletionStage<ApiResult<ModpackService.CompatibilityReport>> inspect(ModpackService.ModpackManifest var1, ModpackService.Environment var2);

   CompletionStage<ApiResult<ModpackService.InstallPlan>> plan(ModpackService.ModpackManifest var1, ModpackService.CompatibilityReport var2);

   public static final class ArtifactSource {
      private final String uri;
      private final long expectedBytes;

      public ArtifactSource(String uri, long expectedBytes) {
         this.uri = validateUri(uri);
         if (expectedBytes >= 1L && expectedBytes <= 536870912L) {
            this.expectedBytes = expectedBytes;
         } else {
            throw new IllegalArgumentException("invalid expectedBytes");
         }
      }

      public String uri() {
         return this.uri;
      }

      public long expectedBytes() {
         return this.expectedBytes;
      }

      @Override
      public String toString() {
         return "ArtifactSource{hostOnly, bytes=" + this.expectedBytes + '}';
      }

      private static String validateUri(String value) {
         String checked = ApiValidation.text(value, "uri", 2048);

         try {
            URI parsed = new URI(checked);
            if ("https".equalsIgnoreCase(parsed.getScheme()) && parsed.getUserInfo() == null && parsed.getHost() != null && parsed.getRawQuery() == null) {
               return checked;
            } else {
               throw new IllegalArgumentException("artifact URI must be credential-free HTTPS without query");
            }
         } catch (URISyntaxException var3) {
            throw new IllegalArgumentException("invalid artifact URI");
         }
      }
   }

   public static final class CompatibilityReport {
      private final ModpackService.CompatibilityStatus status;
      private final List<String> safeCodes;

      public CompatibilityReport(ModpackService.CompatibilityStatus status, List<String> safeCodes) {
         this.status = Objects.requireNonNull(status, "status");
         this.safeCodes = ApiValidation.immutableList(safeCodes, 512, "safeCodes");
      }

      public ModpackService.CompatibilityStatus status() {
         return this.status;
      }

      public List<String> safeCodes() {
         return this.safeCodes;
      }
   }

   public static enum CompatibilityStatus {
      COMPATIBLE,
      CHANGES_REQUIRED,
      INCOMPATIBLE,
      INVALID;
   }

   public static final class Environment {
      private final String minecraftVersion;
      private final String loaderId;
      private final String loaderVersion;

      public Environment(String minecraftVersion, String loaderId, String loaderVersion) {
         this.minecraftVersion = ApiValidation.text(minecraftVersion, "minecraftVersion", 64);
         this.loaderId = ApiValidation.text(loaderId, "loaderId", 32);
         this.loaderVersion = ApiValidation.text(loaderVersion, "loaderVersion", 64);
      }

      public String minecraftVersion() {
         return this.minecraftVersion;
      }

      public String loaderId() {
         return this.loaderId;
      }

      public String loaderVersion() {
         return this.loaderVersion;
      }
   }

   public static enum InstallAction {
      KEEP,
      STAGE_ADD,
      STAGE_UPDATE,
      STAGE_REMOVE_WITH_BACKUP;
   }

   public static final class InstallPlan {
      private final String planId;
      private final List<ModpackService.PlannedEntry> entries;
      private final boolean restartRequired;
      private final boolean rollbackAvailable;

      public InstallPlan(String planId, List<ModpackService.PlannedEntry> entries, boolean restartRequired, boolean rollbackAvailable) {
         this.planId = ApiValidation.text(planId, "planId", 96);
         ApiValidation.rejectSensitiveName(this.planId, "planId");
         this.entries = ApiValidation.immutableList(entries, 2048, "entries");
         this.restartRequired = restartRequired;
         this.rollbackAvailable = rollbackAvailable;
      }

      public String planId() {
         return this.planId;
      }

      public List<ModpackService.PlannedEntry> entries() {
         return this.entries;
      }

      public boolean restartRequired() {
         return this.restartRequired;
      }

      public boolean rollbackAvailable() {
         return this.rollbackAvailable;
      }

      @Override
      public String toString() {
         return "InstallPlan{id='" + this.planId + "', entries=" + this.entries.size() + ", restart=" + this.restartRequired + '}';
      }
   }

   public static final class ModEntry {
      private static final Pattern ID = Pattern.compile("^[a-z0-9][a-z0-9_.-]{0,95}$");
      private static final Pattern SHA256 = Pattern.compile("^[a-f0-9]{64}$");
      private final String modId;
      private final String version;
      private final ModpackService.Side side;
      private final String sha256;
      private final ModpackService.ArtifactSource source;

      public ModEntry(String modId, String version, ModpackService.Side side, String sha256, ModpackService.ArtifactSource source) {
         this.modId = ApiValidation.identifier(modId, "modId", ID);
         this.version = ApiValidation.text(version, "version", 64);
         this.side = Objects.requireNonNull(side, "side");
         this.sha256 = ApiValidation.identifier(sha256, "sha256", SHA256);
         this.source = Objects.requireNonNull(source, "source");
      }

      public String modId() {
         return this.modId;
      }

      public String version() {
         return this.version;
      }

      public ModpackService.Side side() {
         return this.side;
      }

      public String sha256() {
         return this.sha256;
      }

      public ModpackService.ArtifactSource source() {
         return this.source;
      }

      @Override
      public String toString() {
         return "ModEntry{id='" + this.modId + "', version='" + this.version + "', sha256=present}";
      }
   }

   public static final class ModpackId {
      private static final Pattern FORMAT = Pattern.compile("^[a-z][a-z0-9_.-]{0,63}$");
      private final String id;
      private final String version;

      public ModpackId(String id, String version) {
         this.id = ApiValidation.identifier(id, "modpackId", FORMAT);
         this.version = ApiValidation.text(version, "version", 64);
      }

      public String id() {
         return this.id;
      }

      public String version() {
         return this.version;
      }

      @Override
      public String toString() {
         return this.id + '@' + this.version;
      }
   }

   public static final class ModpackManifest {
      private final int schemaVersion;
      private final ModpackService.ModpackId id;
      private final ModpackService.Environment requiredEnvironment;
      private final List<ModpackService.ModEntry> entries;
      private final String signatureMetadata;

      public ModpackManifest(
         int schemaVersion,
         ModpackService.ModpackId id,
         ModpackService.Environment requiredEnvironment,
         List<ModpackService.ModEntry> entries,
         String signatureMetadata
      ) {
         if (schemaVersion >= 1 && schemaVersion <= 64) {
            this.schemaVersion = schemaVersion;
            this.id = Objects.requireNonNull(id, "id");
            this.requiredEnvironment = Objects.requireNonNull(requiredEnvironment, "requiredEnvironment");
            this.entries = ApiValidation.immutableList(entries, 2048, "entries");
            HashSet<String> ids = new HashSet<>();

            for (ModpackService.ModEntry entry : this.entries) {
               if (!ids.add(entry.modId())) {
                  throw new IllegalArgumentException("duplicate manifest entry");
               }
            }

            this.signatureMetadata = ApiValidation.optionalText(signatureMetadata, "signatureMetadata", 512);
            ApiValidation.rejectSensitiveName(this.signatureMetadata, "signatureMetadata");
         } else {
            throw new IllegalArgumentException("invalid schemaVersion");
         }
      }

      public int schemaVersion() {
         return this.schemaVersion;
      }

      public ModpackService.ModpackId id() {
         return this.id;
      }

      public ModpackService.Environment requiredEnvironment() {
         return this.requiredEnvironment;
      }

      public List<ModpackService.ModEntry> entries() {
         return this.entries;
      }

      public String signatureMetadata() {
         return this.signatureMetadata;
      }

      @Override
      public String toString() {
         return "ModpackManifest{id=" + this.id + ", entries=" + this.entries.size() + '}';
      }
   }

   public interface ModpackProvider {
      String id();

      CompletionStage<ApiResult<ModpackService.CompatibilityReport>> inspect(ModpackService.ModpackManifest var1, ModpackService.Environment var2);

      CompletionStage<ApiResult<ModpackService.InstallPlan>> plan(ModpackService.ModpackManifest var1, ModpackService.CompatibilityReport var2);
   }

   public static final class PlannedEntry {
      private final String modId;
      private final ModpackService.InstallAction action;
      private final long bytes;

      public PlannedEntry(String modId, ModpackService.InstallAction action, long bytes) {
         this.modId = ApiValidation.text(modId, "modId", 96);
         this.action = Objects.requireNonNull(action, "action");
         if (bytes < 0L) {
            throw new IllegalArgumentException("invalid bytes");
         } else {
            this.bytes = bytes;
         }
      }

      public String modId() {
         return this.modId;
      }

      public ModpackService.InstallAction action() {
         return this.action;
      }

      public long bytes() {
         return this.bytes;
      }
   }

   public static enum Side {
      REQUIRED,
      OPTIONAL,
      CLIENT_ONLY,
      SERVER_ONLY;
   }
}
