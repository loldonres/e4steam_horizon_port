package link.e4steam.api.lobby;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Map.Entry;
import java.util.concurrent.CompletionStage;
import java.util.regex.Pattern;
import link.e4steam.api.ApiResult;
import link.e4steam.api.ApiValidation;
import link.e4steam.api.identity.IdentityService;

public interface LobbyService {
   CompletionStage<ApiResult<LobbyService.LobbySnapshot>> create(LobbyService.CreateRequest var1);

   CompletionStage<ApiResult<LobbyService.LobbySnapshot>> join(LobbyService.LobbyId var1);

   CompletionStage<ApiResult<Boolean>> leave(LobbyService.LobbyId var1);

   CompletionStage<ApiResult<LobbyService.LobbySnapshot>> updateMetadata(LobbyService.LobbyId var1, LobbyService.Metadata var2);

   CompletionStage<ApiResult<LobbyService.SearchPage>> search(LobbyService.SearchQuery var1);

   public static final class CreateRequest {
      private final LobbyService.Visibility visibility;
      private final int memberLimit;
      private final LobbyService.Metadata metadata;

      public CreateRequest(LobbyService.Visibility visibility, int memberLimit, LobbyService.Metadata metadata) {
         this.visibility = Objects.requireNonNull(visibility, "visibility");
         if (memberLimit >= 2 && memberLimit <= 256) {
            this.memberLimit = memberLimit;
            this.metadata = Objects.requireNonNull(metadata, "metadata");
         } else {
            throw new IllegalArgumentException("invalid memberLimit");
         }
      }

      public LobbyService.Visibility visibility() {
         return this.visibility;
      }

      public int memberLimit() {
         return this.memberLimit;
      }

      public LobbyService.Metadata metadata() {
         return this.metadata;
      }
   }

   public static final class LobbyId {
      private static final Pattern FORMAT = Pattern.compile("^[1-9][0-9]{0,19}$");
      private final String value;

      public LobbyId(String value) {
         this.value = ApiValidation.identifier(value, "lobbyId", FORMAT);
      }

      public String value() {
         return this.value;
      }

      @Override
      public boolean equals(Object other) {
         return this == other || other instanceof LobbyService.LobbyId && this.value.equals(((LobbyService.LobbyId)other).value);
      }

      @Override
      public int hashCode() {
         return this.value.hashCode();
      }

      @Override
      public String toString() {
         return "LobbyId{opaque}";
      }
   }

   public static final class LobbySnapshot {
      private final LobbyService.LobbyId id;
      private final LobbyService.Visibility visibility;
      private final int memberLimit;
      private final List<LobbyService.MemberSnapshot> members;
      private final LobbyService.Metadata metadata;

      public LobbySnapshot(
         LobbyService.LobbyId id,
         LobbyService.Visibility visibility,
         int memberLimit,
         List<LobbyService.MemberSnapshot> members,
         LobbyService.Metadata metadata
      ) {
         this.id = Objects.requireNonNull(id, "id");
         this.visibility = Objects.requireNonNull(visibility, "visibility");
         if (memberLimit >= 2 && memberLimit <= 256) {
            this.memberLimit = memberLimit;
            this.members = ApiValidation.immutableList(members, 256, "members");
            if (this.members.size() > memberLimit) {
               throw new IllegalArgumentException("members exceed limit");
            } else {
               this.metadata = Objects.requireNonNull(metadata, "metadata");
            }
         } else {
            throw new IllegalArgumentException("invalid memberLimit");
         }
      }

      public LobbyService.LobbyId id() {
         return this.id;
      }

      public LobbyService.Visibility visibility() {
         return this.visibility;
      }

      public int memberLimit() {
         return this.memberLimit;
      }

      public List<LobbyService.MemberSnapshot> members() {
         return this.members;
      }

      public LobbyService.Metadata metadata() {
         return this.metadata;
      }
   }

   public static final class MemberSnapshot {
      private final IdentityService.PeerId peerId;
      private final boolean owner;

      public MemberSnapshot(IdentityService.PeerId peerId, boolean owner) {
         this.peerId = Objects.requireNonNull(peerId, "peerId");
         this.owner = owner;
      }

      public IdentityService.PeerId peerId() {
         return this.peerId;
      }

      public boolean owner() {
         return this.owner;
      }
   }

   public static final class Metadata {
      private static final Pattern KEY = Pattern.compile("^[a-z][a-z0-9_.-]{0,31}:[a-z][a-z0-9_.-]{0,63}$");
      private final Map<String, LobbyService.MetadataValue> values;

      public Metadata(Map<String, LobbyService.MetadataValue> values) {
         if (values == null) {
            throw new NullPointerException("values");
         } else if (values.size() > 64) {
            throw new IllegalArgumentException("too many metadata keys");
         } else {
            LinkedHashMap<String, LobbyService.MetadataValue> copy = new LinkedHashMap<>();

            for (Entry<String, LobbyService.MetadataValue> entry : values.entrySet()) {
               String key = ApiValidation.identifier(entry.getKey(), "metadata key", KEY);
               ApiValidation.rejectSensitiveName(key, "metadata key");
               if (key.startsWith("e4steam:core/")) {
                  throw new IllegalArgumentException("reserved core metadata");
               }

               copy.put(key, Objects.requireNonNull(entry.getValue(), "metadata value"));
            }

            this.values = Collections.unmodifiableMap(copy);
         }
      }

      public Map<String, LobbyService.MetadataValue> values() {
         return this.values;
      }

      @Override
      public String toString() {
         return "Metadata{keys=" + this.values.keySet() + '}';
      }
   }

   public static final class MetadataValue {
      private final LobbyService.MetadataValue.Type type;
      private final String value;

      private MetadataValue(LobbyService.MetadataValue.Type type, String value) {
         this.type = type;
         this.value = value;
      }

      public static LobbyService.MetadataValue text(String value) {
         String checked = ApiValidation.text(value, "metadata", 4096);
         if (looksSensitive(checked)) {
            throw new IllegalArgumentException("metadata value appears sensitive");
         } else {
            return new LobbyService.MetadataValue(LobbyService.MetadataValue.Type.STRING, checked);
         }
      }

      public static LobbyService.MetadataValue integer(long value) {
         return new LobbyService.MetadataValue(LobbyService.MetadataValue.Type.INTEGER, Long.toString(value));
      }

      public static LobbyService.MetadataValue bool(boolean value) {
         return new LobbyService.MetadataValue(LobbyService.MetadataValue.Type.BOOLEAN, Boolean.toString(value));
      }

      public LobbyService.MetadataValue.Type type() {
         return this.type;
      }

      public String value() {
         return this.value;
      }

      @Override
      public String toString() {
         return this.type == LobbyService.MetadataValue.Type.STRING ? "STRING{redacted-from-toString}" : this.type + ":" + this.value;
      }

      private static boolean looksSensitive(String value) {
         String lower = value.toLowerCase(Locale.ROOT);
         return lower.contains("bearer ") || lower.matches(".*(?:token|ticket|password|secret|cookie|authorization|joinaddress|gslt)=[^\\s]+.*");
      }

      public static enum Type {
         STRING,
         INTEGER,
         BOOLEAN;
      }
   }

   public static final class SearchPage {
      private final List<LobbyService.LobbySnapshot> results;
      private final String nextCursor;

      public SearchPage(List<LobbyService.LobbySnapshot> results, String nextCursor) {
         this.results = ApiValidation.immutableList(results, 100, "results");
         this.nextCursor = ApiValidation.optionalText(nextCursor, "nextCursor", 256);
      }

      public List<LobbyService.LobbySnapshot> results() {
         return this.results;
      }

      public String nextCursor() {
         return this.nextCursor;
      }
   }

   public static final class SearchQuery {
      private final LobbyService.Metadata filters;
      private final int limit;
      private final String cursor;

      public SearchQuery(LobbyService.Metadata filters, int limit, String cursor) {
         this.filters = Objects.requireNonNull(filters, "filters");
         if (limit >= 1 && limit <= 100) {
            this.limit = limit;
            this.cursor = ApiValidation.optionalText(cursor, "cursor", 256);
         } else {
            throw new IllegalArgumentException("invalid limit");
         }
      }

      public LobbyService.Metadata filters() {
         return this.filters;
      }

      public int limit() {
         return this.limit;
      }

      public String cursor() {
         return this.cursor;
      }
   }

   public static enum Visibility {
      PRIVATE,
      FRIENDS_ONLY,
      PUBLIC;
   }
}
