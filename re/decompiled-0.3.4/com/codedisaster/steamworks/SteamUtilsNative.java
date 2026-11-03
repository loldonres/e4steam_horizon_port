package com.codedisaster.steamworks;

import java.nio.ByteBuffer;

final class SteamUtilsNative {
   static native long createCallback(SteamUtilsCallbackAdapter var0);

   static native int getSecondsSinceAppActive();

   static native int getSecondsSinceComputerActive();

   static native int getConnectedUniverse();

   static native int getServerRealTime();

   static native String getIPCountry();

   static native int getImageWidth(int var0);

   static native int getImageHeight(int var0);

   static native boolean getImageSize(int var0, int[] var1);

   static native boolean getImageRGBA(int var0, ByteBuffer var1, int var2, int var3);

   static native int getAppID();

   static native void setOverlayNotificationPosition(int var0);

   static native boolean isAPICallCompleted(long var0, boolean[] var2);

   static native int getAPICallFailureReason(long var0);

   static native void enableWarningMessageHook(long var0, boolean var2);

   static native boolean isOverlayEnabled();

   static native boolean isSteamInBigPictureMode();

   static native boolean isSteamChinaLauncher();

   static native boolean isSteamRunningOnSteamDeck();

   static native boolean showFloatingGamepadTextInput(int var0, int var1, int var2, int var3, int var4);

   static native void setGameLauncherMode(boolean var0);

   static native boolean dismissFloatingGamepadTextInput();

   static native boolean dismissGamepadTextInput();
}
