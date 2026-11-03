package link.e4steam.steam;

public enum SteamAccessMode {
   LOCAL_ONLY("text.e4steam_minecraft.access.local"),
   FRIENDS_ONLY("text.e4steam_minecraft.access.friends"),
   INVITE_ONLY("text.e4steam_minecraft.access.invite"),
   CUSTOM("text.e4steam_minecraft.access.external");

   private final String translationKey;

   private SteamAccessMode(String translationKey) {
      this.translationKey = translationKey;
   }

   public String translationKey() {
      return this.translationKey;
   }
}
