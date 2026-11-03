package com.codedisaster.steamworks;

public class SteamUserStats extends SteamInterface {
   public SteamUserStats(SteamUserStatsCallback callback) {
      super(SteamUserStatsNative.createCallback(new SteamUserStatsCallbackAdapter(callback)));
   }

   public int getStatI(String name, int defaultValue) {
      int[] values = new int[1];
      return SteamUserStatsNative.getStat(name, values) ? values[0] : defaultValue;
   }

   public boolean setStatI(String name, int value) {
      return SteamUserStatsNative.setStat(name, value);
   }

   public float getStatF(String name, float defaultValue) {
      float[] values = new float[1];
      return SteamUserStatsNative.getStat(name, values) ? values[0] : defaultValue;
   }

   public boolean setStatF(String name, float value) {
      return SteamUserStatsNative.setStat(name, value);
   }

   public boolean isAchieved(String name, boolean defaultValue) {
      boolean[] achieved = new boolean[1];
      return SteamUserStatsNative.getAchievement(name, achieved) ? achieved[0] : defaultValue;
   }

   public boolean setAchievement(String name) {
      return SteamUserStatsNative.setAchievement(name);
   }

   public boolean clearAchievement(String name) {
      return SteamUserStatsNative.clearAchievement(name);
   }

   public boolean storeStats() {
      return SteamUserStatsNative.storeStats();
   }

   public boolean indicateAchievementProgress(String name, int curProgress, int maxProgress) {
      return SteamUserStatsNative.indicateAchievementProgress(name, curProgress, maxProgress);
   }

   public int getNumAchievements() {
      return SteamUserStatsNative.getNumAchievements();
   }

   public String getAchievementName(int index) {
      return SteamUserStatsNative.getAchievementName(index);
   }

   public boolean resetAllStats(boolean achievementsToo) {
      return SteamUserStatsNative.resetAllStats(achievementsToo);
   }

   public SteamAPICall findOrCreateLeaderboard(
      String leaderboardName, SteamUserStats.LeaderboardSortMethod leaderboardSortMethod, SteamUserStats.LeaderboardDisplayType leaderboardDisplayType
   ) {
      return new SteamAPICall(
         SteamUserStatsNative.findOrCreateLeaderboard(this.callback, leaderboardName, leaderboardSortMethod.ordinal(), leaderboardDisplayType.ordinal())
      );
   }

   public SteamAPICall findLeaderboard(String leaderboardName) {
      return new SteamAPICall(SteamUserStatsNative.findLeaderboard(this.callback, leaderboardName));
   }

   public String getLeaderboardName(SteamLeaderboardHandle leaderboard) {
      return SteamUserStatsNative.getLeaderboardName(leaderboard.handle);
   }

   public int getLeaderboardEntryCount(SteamLeaderboardHandle leaderboard) {
      return SteamUserStatsNative.getLeaderboardEntryCount(leaderboard.handle);
   }

   public SteamUserStats.LeaderboardSortMethod getLeaderboardSortMethod(SteamLeaderboardHandle leaderboard) {
      return SteamUserStats.LeaderboardSortMethod.values()[SteamUserStatsNative.getLeaderboardSortMethod(leaderboard.handle)];
   }

   public SteamUserStats.LeaderboardDisplayType getLeaderboardDisplayType(SteamLeaderboardHandle leaderboard) {
      return SteamUserStats.LeaderboardDisplayType.values()[SteamUserStatsNative.getLeaderboardDisplayType(leaderboard.handle)];
   }

   public SteamAPICall downloadLeaderboardEntries(
      SteamLeaderboardHandle leaderboard, SteamUserStats.LeaderboardDataRequest leaderboardDataRequest, int rangeStart, int rangeEnd
   ) {
      return new SteamAPICall(
         SteamUserStatsNative.downloadLeaderboardEntries(this.callback, leaderboard.handle, leaderboardDataRequest.ordinal(), rangeStart, rangeEnd)
      );
   }

   public SteamAPICall downloadLeaderboardEntriesForUsers(SteamLeaderboardHandle leaderboard, SteamID[] users) {
      int count = users.length;
      long[] handles = new long[count];

      for (int i = 0; i < count; i++) {
         handles[i] = users[i].handle;
      }

      return new SteamAPICall(SteamUserStatsNative.downloadLeaderboardEntriesForUsers(this.callback, leaderboard.handle, handles, count));
   }

   public boolean getDownloadedLeaderboardEntry(SteamLeaderboardEntriesHandle leaderboardEntries, int index, SteamLeaderboardEntry entry, int[] details) {
      return details == null
         ? SteamUserStatsNative.getDownloadedLeaderboardEntry(leaderboardEntries.handle, index, entry)
         : SteamUserStatsNative.getDownloadedLeaderboardEntry(leaderboardEntries.handle, index, entry, details, details.length);
   }

   public SteamAPICall uploadLeaderboardScore(
      SteamLeaderboardHandle leaderboard, SteamUserStats.LeaderboardUploadScoreMethod method, int score, int[] scoreDetails
   ) {
      return new SteamAPICall(
         scoreDetails == null
            ? SteamUserStatsNative.uploadLeaderboardScore(this.callback, leaderboard.handle, method.ordinal(), score)
            : SteamUserStatsNative.uploadLeaderboardScore(this.callback, leaderboard.handle, method.ordinal(), score, scoreDetails, scoreDetails.length)
      );
   }

   public SteamAPICall getNumberOfCurrentPlayers() {
      return new SteamAPICall(SteamUserStatsNative.getNumberOfCurrentPlayers(this.callback));
   }

   public SteamAPICall requestGlobalStats(int historyDays) {
      return new SteamAPICall(SteamUserStatsNative.requestGlobalStats(this.callback, historyDays));
   }

   public long getGlobalStat(String name, long defaultValue) {
      long[] values = new long[1];
      return SteamUserStatsNative.getGlobalStat(name, values) ? values[0] : defaultValue;
   }

   public double getGlobalStat(String name, double defaultValue) {
      double[] values = new double[1];
      return SteamUserStatsNative.getGlobalStat(name, values) ? values[0] : defaultValue;
   }

   public int getGlobalStatHistory(String name, long[] data) {
      return SteamUserStatsNative.getGlobalStatHistory(name, data, data.length);
   }

   public int getGlobalStatHistory(String name, double[] data) {
      return SteamUserStatsNative.getGlobalStatHistory(name, data, data.length);
   }

   public static enum LeaderboardDataRequest {
      Global,
      GlobalAroundUser,
      Friends,
      Users;
   }

   public static enum LeaderboardDisplayType {
      None,
      Numeric,
      TimeSeconds,
      TimeMilliSeconds;
   }

   public static enum LeaderboardSortMethod {
      None,
      Ascending,
      Descending;
   }

   public static enum LeaderboardUploadScoreMethod {
      None,
      KeepBest,
      ForceUpdate;
   }
}
