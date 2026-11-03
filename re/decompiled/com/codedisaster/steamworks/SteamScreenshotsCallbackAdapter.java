package com.codedisaster.steamworks;

public class SteamScreenshotsCallbackAdapter extends SteamCallbackAdapter<SteamScreenshotsCallback> {
   SteamScreenshotsCallbackAdapter(SteamScreenshotsCallback callback) {
      super(callback);
   }

   void onScreenshotReady(int local, int result) {
      this.callback.onScreenshotReady(new SteamScreenshotHandle(local), SteamResult.byValue(result));
   }

   void onScreenshotRequested() {
      this.callback.onScreenshotRequested();
   }
}
