package com.codedisaster.steamworks;

class SteamUGCCallbackAdapter extends SteamCallbackAdapter<SteamUGCCallback> {
   SteamUGCCallbackAdapter(SteamUGCCallback callback) {
      super(callback);
   }

   void onUGCQueryCompleted(long handle, int numResultsReturned, int totalMatchingResults, boolean isCachedData, int result) {
      this.callback.onUGCQueryCompleted(new SteamUGCQuery(handle), numResultsReturned, totalMatchingResults, isCachedData, SteamResult.byValue(result));
   }

   void onSubscribeItem(long publishedFileID, int result) {
      this.callback.onSubscribeItem(new SteamPublishedFileID(publishedFileID), SteamResult.byValue(result));
   }

   void onUnsubscribeItem(long publishedFileID, int result) {
      this.callback.onUnsubscribeItem(new SteamPublishedFileID(publishedFileID), SteamResult.byValue(result));
   }

   void onRequestUGCDetails(
      long publishedFileID,
      int result,
      int fileType,
      String title,
      String description,
      boolean tagsTruncated,
      String tags,
      long fileHandle,
      long previewFileHandle,
      String fileName,
      int fileSize,
      int previewFileSize,
      String url,
      int votesUp,
      int votesDown,
      long ownerID,
      int timeCreated,
      int timeUpdated,
      float score,
      int numChildren,
      long totalFileSize
   ) {
      SteamUGCDetails details = new SteamUGCDetails();
      details.publishedFileID = publishedFileID;
      details.result = result;
      details.fileType = fileType;
      details.title = title;
      details.description = description;
      details.tagsTruncated = tagsTruncated;
      details.tags = tags;
      details.fileHandle = fileHandle;
      details.previewFileHandle = previewFileHandle;
      details.fileName = fileName;
      details.fileSize = fileSize;
      details.previewFileSize = previewFileSize;
      details.votesUp = votesUp;
      details.votesDown = votesDown;
      details.ownerID = ownerID;
      details.timeCreated = timeCreated;
      details.timeUpdated = timeUpdated;
      details.score = score;
      details.numChildren = numChildren;
      details.totalFileSize = totalFileSize;
      this.callback.onRequestUGCDetails(details, SteamResult.byValue(result));
   }

   void onCreateItem(long publishedFileID, boolean needsToAcceptWLA, int result) {
      this.callback.onCreateItem(new SteamPublishedFileID(publishedFileID), needsToAcceptWLA, SteamResult.byValue(result));
   }

   void onSubmitItemUpdate(long publishedFileID, boolean needsToAcceptWLA, int result) {
      this.callback.onSubmitItemUpdate(new SteamPublishedFileID(publishedFileID), needsToAcceptWLA, SteamResult.byValue(result));
   }

   void onDownloadItemResult(int appID, long publishedFileID, int result) {
      this.callback.onDownloadItemResult(appID, new SteamPublishedFileID(publishedFileID), SteamResult.byValue(result));
   }

   void onUserFavoriteItemsListChanged(long publishedFileID, boolean wasAddRequest, int result) {
      this.callback.onUserFavoriteItemsListChanged(new SteamPublishedFileID(publishedFileID), wasAddRequest, SteamResult.byValue(result));
   }

   void onSetUserItemVote(long publishedFileID, boolean voteUp, int result) {
      this.callback.onSetUserItemVote(new SteamPublishedFileID(publishedFileID), voteUp, SteamResult.byValue(result));
   }

   void onGetUserItemVote(long publishedFileID, boolean votedUp, boolean votedDown, boolean voteSkipped, int result) {
      this.callback.onGetUserItemVote(new SteamPublishedFileID(publishedFileID), votedUp, votedDown, voteSkipped, SteamResult.byValue(result));
   }

   void onStartPlaytimeTracking(int result) {
      this.callback.onStartPlaytimeTracking(SteamResult.byValue(result));
   }

   void onStopPlaytimeTracking(int result) {
      this.callback.onStopPlaytimeTracking(SteamResult.byValue(result));
   }

   void onStopPlaytimeTrackingForAllItems(int result) {
      this.callback.onStopPlaytimeTrackingForAllItems(SteamResult.byValue(result));
   }

   void onDeleteItem(long publishedFileID, int result) {
      this.callback.onDeleteItem(new SteamPublishedFileID(publishedFileID), SteamResult.byValue(result));
   }
}
