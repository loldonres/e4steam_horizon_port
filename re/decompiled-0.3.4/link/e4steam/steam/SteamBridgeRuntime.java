package link.e4steam.steam;

interface SteamBridgeRuntime {
   boolean sendData(SteamConnectionBridge var1, byte[] var2);

   boolean sendFin(SteamConnectionBridge var1);

   boolean sendReset(SteamConnectionBridge var1);

   void closeUdpBridge(SteamConnectionBridge var1);

   void unregister(SteamConnectionBridge var1);
}
