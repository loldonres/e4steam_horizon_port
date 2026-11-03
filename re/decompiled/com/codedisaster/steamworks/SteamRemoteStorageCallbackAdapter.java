package com.codedisaster.steamworks;

class SteamRemoteStorageCallbackAdapter extends SteamCallbackAdapter<SteamRemoteStorageCallback> {
   SteamRemoteStorageCallbackAdapter(SteamRemoteStorageCallback callback) {
      super(callback);
   }

   void onFileShareResult(long fileHandle, String fileName, int result) {
      this.callback.onFileShareResult(new SteamUGCHandle(fileHandle), fileName, SteamResult.byValue(result));
   }

   void onDownloadUGCResult(long fileHandle, int result) {
      this.callback.onDownloadUGCResult(new SteamUGCHandle(fileHandle), SteamResult.byValue(result));
   }

   void onPublishFileResult(long publishedFileID, boolean needsToAcceptWLA, int result) {
      this.callback.onPublishFileResult(new SteamPublishedFileID(publishedFileID), needsToAcceptWLA, SteamResult.byValue(result));
   }

   void onUpdatePublishedFileResult(long publishedFileID, boolean needsToAcceptWLA, int result) {
      this.callback.onUpdatePublishedFileResult(new SteamPublishedFileID(publishedFileID), needsToAcceptWLA, SteamResult.byValue(result));
   }

   void onPublishedFileSubscribed(long publishedFileID, int appID) {
      this.callback.onPublishedFileSubscribed(new SteamPublishedFileID(publishedFileID), appID);
   }

   void onPublishedFileUnsubscribed(long publishedFileID, int appID) {
      this.callback.onPublishedFileUnsubscribed(new SteamPublishedFileID(publishedFileID), appID);
   }

   void onPublishedFileDeleted(long publishedFileID, int appID) {
      this.callback.onPublishedFileDeleted(new SteamPublishedFileID(publishedFileID), appID);
   }

   void onFileWriteAsyncComplete(int result) {
      this.callback.onFileWriteAsyncComplete(SteamResult.byValue(result));
   }

   void onFileReadAsyncComplete(long fileReadAsync, int result, int offset, int read) {
      this.callback.onFileReadAsyncComplete(new SteamAPICall(fileReadAsync), SteamResult.byValue(result), offset, read);
   }
}
