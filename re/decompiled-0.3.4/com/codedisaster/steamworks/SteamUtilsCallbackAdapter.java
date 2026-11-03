package com.codedisaster.steamworks;

class SteamUtilsCallbackAdapter extends SteamCallbackAdapter<SteamUtilsCallback> {
   private SteamAPIWarningMessageHook messageHook;

   SteamUtilsCallbackAdapter(SteamUtilsCallback callback) {
      super(callback);
   }

   void setWarningMessageHook(SteamAPIWarningMessageHook messageHook) {
      this.messageHook = messageHook;
   }

   void onWarningMessage(int severity, String message) {
      if (this.messageHook != null) {
         this.messageHook.onWarningMessage(severity, message);
      }
   }

   void onSteamShutdown() {
      this.callback.onSteamShutdown();
   }

   void onFloatingGamepadTextInputDismissed() {
      this.callback.onFloatingGamepadTextInputDismissed();
   }
}
