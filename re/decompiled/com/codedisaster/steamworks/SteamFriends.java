package com.codedisaster.steamworks;

import java.util.Collection;

public class SteamFriends extends SteamInterface {
   public SteamFriends(SteamFriendsCallback callback) {
      super(SteamFriendsNative.createCallback(new SteamFriendsCallbackAdapter(callback)));
   }

   public String getPersonaName() {
      return SteamFriendsNative.getPersonaName();
   }

   public SteamFriends.PersonaState getPersonaState() {
      return SteamFriends.PersonaState.byOrdinal(SteamFriendsNative.getPersonaState());
   }

   public int getFriendCount(SteamFriends.FriendFlags friendFlag) {
      return SteamFriendsNative.getFriendCount(friendFlag.bits);
   }

   public int getFriendCount(Collection<SteamFriends.FriendFlags> friendFlags) {
      return SteamFriendsNative.getFriendCount(SteamFriends.FriendFlags.asBits(friendFlags));
   }

   public SteamID getFriendByIndex(int friend, SteamFriends.FriendFlags friendFlag) {
      return new SteamID(SteamFriendsNative.getFriendByIndex(friend, friendFlag.bits));
   }

   public SteamID getFriendByIndex(int friend, Collection<SteamFriends.FriendFlags> friendFlags) {
      return new SteamID(SteamFriendsNative.getFriendByIndex(friend, SteamFriends.FriendFlags.asBits(friendFlags)));
   }

   public SteamFriends.FriendRelationship getFriendRelationship(SteamID steamIDFriend) {
      return SteamFriends.FriendRelationship.byOrdinal(SteamFriendsNative.getFriendRelationship(steamIDFriend.handle));
   }

   public SteamFriends.PersonaState getFriendPersonaState(SteamID steamIDFriend) {
      return SteamFriends.PersonaState.byOrdinal(SteamFriendsNative.getFriendPersonaState(steamIDFriend.handle));
   }

   public String getFriendPersonaName(SteamID steamIDFriend) {
      return SteamFriendsNative.getFriendPersonaName(steamIDFriend.handle);
   }

   public boolean getFriendGamePlayed(SteamID steamIDFriend, SteamFriends.FriendGameInfo friendGameInfo) {
      return SteamFriendsNative.getFriendGamePlayed(steamIDFriend.handle, friendGameInfo);
   }

   public void setInGameVoiceSpeaking(SteamID steamID, boolean speaking) {
      SteamFriendsNative.setInGameVoiceSpeaking(steamID.handle, speaking);
   }

   public int getSmallFriendAvatar(SteamID steamID) {
      return SteamFriendsNative.getSmallFriendAvatar(steamID.handle);
   }

   public int getMediumFriendAvatar(SteamID steamID) {
      return SteamFriendsNative.getMediumFriendAvatar(steamID.handle);
   }

   public int getLargeFriendAvatar(SteamID steamID) {
      return SteamFriendsNative.getLargeFriendAvatar(steamID.handle);
   }

   public boolean requestUserInformation(SteamID steamID, boolean requireNameOnly) {
      return SteamFriendsNative.requestUserInformation(steamID.handle, requireNameOnly);
   }

   public void activateGameOverlay(SteamFriends.OverlayDialog dialog) {
      SteamFriendsNative.activateGameOverlay(dialog.id);
   }

   public void activateGameOverlayToUser(SteamFriends.OverlayToUserDialog dialog, SteamID steamID) {
      SteamFriendsNative.activateGameOverlayToUser(dialog.id, steamID.handle);
   }

   public void activateGameOverlayToWebPage(String url, SteamFriends.OverlayToWebPageMode mode) {
      SteamFriendsNative.activateGameOverlayToWebPage(url, mode.ordinal());
   }

   public void activateGameOverlayToStore(int appID, SteamFriends.OverlayToStoreFlag flag) {
      SteamFriendsNative.activateGameOverlayToStore(appID, flag.ordinal());
   }

   public void setPlayedWith(SteamID steamIDUserPlayedWith) {
      SteamFriendsNative.setPlayedWith(steamIDUserPlayedWith.handle);
   }

   public void activateGameOverlayInviteDialog(SteamID steamIDLobby) {
      SteamFriendsNative.activateGameOverlayInviteDialog(steamIDLobby.handle);
   }

   public boolean setRichPresence(String key, String value) {
      return SteamFriendsNative.setRichPresence(key, value != null ? value : "");
   }

   public void clearRichPresence() {
      SteamFriendsNative.clearRichPresence();
   }

   public String getFriendRichPresence(SteamID steamIDFriend, String key) {
      return SteamFriendsNative.getFriendRichPresence(steamIDFriend.handle, key);
   }

   public int getFriendRichPresenceKeyCount(SteamID steamIDFriend) {
      return SteamFriendsNative.getFriendRichPresenceKeyCount(steamIDFriend.handle);
   }

   public String getFriendRichPresenceKeyByIndex(SteamID steamIDFriend, int index) {
      return SteamFriendsNative.getFriendRichPresenceKeyByIndex(steamIDFriend.handle, index);
   }

   public void requestFriendRichPresence(SteamID steamIDFriend) {
      SteamFriendsNative.requestFriendRichPresence(steamIDFriend.handle);
   }

   public boolean inviteUserToGame(SteamID steamIDFriend, String connectString) {
      return SteamFriendsNative.inviteUserToGame(steamIDFriend.handle, connectString);
   }

   public int getCoplayFriendCount() {
      return SteamFriendsNative.getCoplayFriendCount();
   }

   public SteamID getCoplayFriend(int index) {
      return new SteamID(SteamFriendsNative.getCoplayFriend(index));
   }

   public int getFriendCoplayTime(SteamID steamIDFriend) {
      return SteamFriendsNative.getFriendCoplayTime(steamIDFriend.handle);
   }

   public int getFriendCoplayGame(SteamID steamIDFriend) {
      return SteamFriendsNative.getFriendCoplayGame(steamIDFriend.handle);
   }

   public static enum FriendFlags {
      None(0),
      Blocked(1),
      FriendshipRequested(2),
      Immediate(4),
      ClanMember(8),
      OnGameServer(16),
      RequestingFriendship(128),
      RequestingInfo(256),
      Ignored(512),
      IgnoredFriend(1024),
      ChatMember(4096),
      All(65535);

      private final int bits;

      private FriendFlags(int bits) {
         this.bits = bits;
      }

      static int asBits(Collection<SteamFriends.FriendFlags> friendFlags) {
         int bits = 0;

         for (SteamFriends.FriendFlags flags : friendFlags) {
            bits |= flags.bits;
         }

         return bits;
      }
   }

   public static class FriendGameInfo {
      private long gameID;
      private int gameIP;
      private short gamePort;
      private short queryPort;
      private long steamIDLobby;

      public long getGameID() {
         return this.gameID;
      }

      public int getGameIP() {
         return this.gameIP;
      }

      public short getGamePort() {
         return this.gamePort;
      }

      public short getQueryPort() {
         return this.queryPort;
      }

      public SteamID getSteamIDLobby() {
         return new SteamID(this.steamIDLobby);
      }
   }

   public static enum FriendRelationship {
      None,
      Blocked,
      Recipient,
      Friend,
      RequestInitiator,
      Ignored,
      IgnoredFriend,
      Suggested_DEPRECATED,
      Max;

      private static final SteamFriends.FriendRelationship[] values = values();

      static SteamFriends.FriendRelationship byOrdinal(int friendRelationship) {
         return values[friendRelationship];
      }
   }

   public static enum OverlayDialog {
      Friends("Friends"),
      Community("Community"),
      Players("Players"),
      Settings("Settings"),
      OfficialGameGroup("OfficialGameGroup"),
      Stats("Stats"),
      Achievements("Achievements");

      private final String id;

      private OverlayDialog(String id) {
         this.id = id;
      }
   }

   public static enum OverlayToStoreFlag {
      None,
      AddToCart,
      AddToCartAndShow;
   }

   public static enum OverlayToUserDialog {
      SteamID("steamid"),
      Chat("chat"),
      JoinTrade("jointrade"),
      Stats("stats"),
      Achievements("achievements"),
      FriendAdd("friendadd"),
      FriendRemove("friendremove"),
      FriendRequestAccept("friendrequestaccept"),
      FriendRequestIgnore("friendrequestignore");

      private final String id;

      private OverlayToUserDialog(String id) {
         this.id = id;
      }
   }

   public static enum OverlayToWebPageMode {
      Default,
      Modal;
   }

   public static enum PersonaChange {
      Name(1),
      Status(2),
      ComeOnline(4),
      GoneOffline(8),
      GamePlayed(16),
      GameServer(32),
      Avatar(64),
      JoinedSource(128),
      LeftSource(256),
      RelationshipChanged(512),
      NameFirstSet(1024),
      Broadcast(2048),
      Nickname(4096),
      SteamLevel(8192),
      RichPresence(16384);

      private final int bits;

      private PersonaChange(int bits) {
         this.bits = bits;
      }

      static boolean isSet(SteamFriends.PersonaChange value, int bitMask) {
         return (value.bits & bitMask) == value.bits;
      }
   }

   public static enum PersonaState {
      Offline,
      Online,
      Busy,
      Away,
      Snooze,
      LookingToTrade,
      LookingToPlay,
      Invisible;

      private static final SteamFriends.PersonaState[] values = values();

      static SteamFriends.PersonaState byOrdinal(int personaState) {
         return values[personaState];
      }
   }
}
