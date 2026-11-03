package link.e4steam.internal.api;

import java.util.Map;
import link.e4steam.api.ApiErrorCode;
import link.e4steam.api.ApiResult;
import link.e4steam.api.addon.AddonId;
import link.e4steam.api.logging.SafeLogger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

final class CoreSafeLogger implements SafeLogger {
   private static final Logger LOGGER = LogManager.getLogger("e4steam.addons");
   private final AddonId owner;

   CoreSafeLogger(AddonId owner) {
      this.owner = owner;
   }

   @Override
   public ApiResult<Boolean> log(SafeLogger.Level level, String messageCode, Map<String, SafeLogger.SafeValue> fields) {
      if (level != null && messageCode != null) {
         Map<String, SafeLogger.SafeValue> checked;
         try {
            checked = SafeLogger.fields(fields);
         } catch (IllegalArgumentException var7) {
            return SafeApiErrors.failure(ApiErrorCode.SECURITY_REJECTION, "logger.log", "SensitiveField");
         }

         String code = messageCode.trim();
         if (!code.matches("[a-z][a-z0-9_.-]{0,95}")) {
            return SafeApiErrors.failure(ApiErrorCode.INVALID_ARGUMENT, "logger.log", "MessageCode");
         } else {
            String safe = "addon=" + this.owner + " code=" + code + " fields=" + checked.keySet();
            switch (level) {
               case DEBUG:
                  LOGGER.debug(safe);
                  break;
               case INFO:
                  LOGGER.info(safe);
                  break;
               case WARN:
                  LOGGER.warn(safe);
                  break;
               case ERROR:
                  LOGGER.error(safe);
                  break;
               default:
                  return SafeApiErrors.failure(ApiErrorCode.INVALID_ARGUMENT, "logger.log", "Level");
            }

            return ApiResult.success(Boolean.TRUE);
         }
      } else {
         return SafeApiErrors.failure(ApiErrorCode.INVALID_ARGUMENT, "logger.log", "Validation");
      }
   }
}
