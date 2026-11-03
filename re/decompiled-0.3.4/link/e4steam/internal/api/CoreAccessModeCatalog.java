package link.e4steam.internal.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import link.e4steam.api.access.AccessService;
import link.e4steam.steam.SteamAccessMode;

public final class CoreAccessModeCatalog {
   private static final String FAMILY = "access";

   private CoreAccessModeCatalog() {
   }

   public static CoreAccessModeCatalog.Selection normalize(SteamAccessMode mode, String customModeId) {
      SteamAccessMode current = mode == null ? SteamAccessMode.FRIENDS_ONLY : mode;
      if (current != SteamAccessMode.CUSTOM) {
         return builtin(current);
      } else {
         for (CoreAccessModeCatalog.Selection selection : selections()) {
            if (selection.mode == SteamAccessMode.CUSTOM && selection.customModeId.equals(customModeId)) {
               return selection;
            }
         }

         return builtin(SteamAccessMode.FRIENDS_ONLY);
      }
   }

   public static CoreAccessModeCatalog.Selection next(SteamAccessMode mode, String customModeId) {
      List<CoreAccessModeCatalog.Selection> values = selections();
      CoreAccessModeCatalog.Selection current = normalize(mode, customModeId);
      int index = values.indexOf(current);
      return values.get((index < 0 ? 0 : index + 1) % values.size());
   }

   public static boolean registered(String customModeId) {
      if (customModeId != null && !customModeId.isEmpty()) {
         for (CoreAccessModeCatalog.Selection selection : selections()) {
            if (selection.mode == SteamAccessMode.CUSTOM && customModeId.equals(selection.customModeId)) {
               return true;
            }
         }

         return false;
      } else {
         return false;
      }
   }

   private static List<CoreAccessModeCatalog.Selection> selections() {
      ArrayList<CoreAccessModeCatalog.Selection> values = new ArrayList<>();
      values.add(builtin(SteamAccessMode.LOCAL_ONLY));
      values.add(builtin(SteamAccessMode.FRIENDS_ONLY));
      values.add(builtin(SteamAccessMode.INVITE_ONLY));
      CoreApiPlatform platform = CoreApiPlatform.current();
      if (platform != null) {
         for (Object value : platform.contributions().snapshotValues("access")) {
            if (value instanceof AccessService.AccessModeProvider provider) {
               String title = "";
               String message = "";
               if (provider instanceof AccessService.ConfirmableAccessModeProvider confirmable) {
                  title = confirmable.confirmationTitleKey();
                  message = confirmable.confirmationMessageKey();
               }

               values.add(new CoreAccessModeCatalog.Selection(SteamAccessMode.CUSTOM, provider.id().value(), provider.displayNameKey(), title, message));
            }
         }
      }

      values.subList(3, values.size()).sort(Comparator.comparing(CoreAccessModeCatalog.Selection::customModeId));
      return values;
   }

   private static CoreAccessModeCatalog.Selection builtin(SteamAccessMode mode) {
      return new CoreAccessModeCatalog.Selection(mode, "", mode.translationKey(), "", "");
   }

   public static final class Selection {
      private final SteamAccessMode mode;
      private final String customModeId;
      private final String translationKey;
      private final String confirmationTitleKey;
      private final String confirmationMessageKey;

      private Selection(SteamAccessMode mode, String customModeId, String translationKey, String confirmationTitleKey, String confirmationMessageKey) {
         this.mode = mode;
         this.customModeId = customModeId;
         this.translationKey = translationKey;
         this.confirmationTitleKey = confirmationTitleKey;
         this.confirmationMessageKey = confirmationMessageKey;
      }

      public SteamAccessMode mode() {
         return this.mode;
      }

      public String customModeId() {
         return this.customModeId;
      }

      public String translationKey() {
         return this.translationKey;
      }

      public boolean requiresConfirmation() {
         return !this.confirmationMessageKey.isEmpty();
      }

      public String confirmationTitleKey() {
         return this.confirmationTitleKey;
      }

      public String confirmationMessageKey() {
         return this.confirmationMessageKey;
      }

      @Override
      public boolean equals(Object other) {
         if (this == other) {
            return true;
         } else {
            return !(other instanceof CoreAccessModeCatalog.Selection that) ? false : this.mode == that.mode && this.customModeId.equals(that.customModeId);
         }
      }

      @Override
      public int hashCode() {
         return 31 * this.mode.hashCode() + this.customModeId.hashCode();
      }
   }
}
