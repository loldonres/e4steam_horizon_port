package link.e4steam.api;

public final class ApiLimits {
   public static final int MAX_IDENTIFIER_LENGTH = 96;
   public static final int MAX_DISPLAY_NAME_LENGTH = 64;
   public static final int MAX_ADDON_DEPENDENCIES = 64;
   public static final int MAX_REQUESTED_CAPABILITIES = 64;
   public static final int MAX_EVENT_SUBSCRIPTIONS = 1024;
   public static final int MAX_QUEUED_TASKS = 256;
   public static final long MAX_LIFECYCLE_CALLBACK_MILLIS = 10000L;
   public static final int MAX_CHANNEL_MESSAGE_BYTES = 1048576;
   public static final int MAX_DATAGRAM_BYTES = 65507;
   public static final int MAX_PAGE_SIZE = 100;
   public static final int MAX_MAP_ENTRIES = 256;
   public static final int MAX_VALUE_LENGTH = 4096;
   public static final int MAX_STORAGE_BLOB_BYTES = 4194304;
   public static final int MAX_MODPACK_ENTRIES = 2048;
   public static final int MAX_SKIN_BYTES = 2097152;
   public static final int MAX_DIAGNOSTIC_FIELDS = 256;
   public static final int MAX_COMMAND_SUGGESTIONS = 100;
   public static final int MAX_REGISTRATIONS_PER_FAMILY = 1000;
   public static final long MAX_OPERATION_TIMEOUT_MILLIS = 30000L;

   private ApiLimits() {
   }
}
