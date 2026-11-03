package com.codedisaster.steamworks;

import java.nio.ByteBuffer;

class SteamEncryptedAppTicketNative {
   static native boolean decryptTicket(ByteBuffer var0, int var1, int var2, ByteBuffer var3, int var4, int var5, byte[] var6, int var7, int[] var8);

   static native boolean isTicketForApp(ByteBuffer var0, int var1, int var2, int var3);

   static native int getTicketIssueTime(ByteBuffer var0, int var1, int var2);

   static native long getTicketSteamID(ByteBuffer var0, int var1, int var2);

   static native int getTicketAppID(ByteBuffer var0, int var1, int var2);

   static native boolean userOwnsAppInTicket(ByteBuffer var0, int var1, int var2, int var3);

   static native boolean userIsVacBanned(ByteBuffer var0, int var1, int var2);

   static native int getUserVariableData(ByteBuffer var0, int var1, int var2, ByteBuffer var3, int var4, int var5);
}
