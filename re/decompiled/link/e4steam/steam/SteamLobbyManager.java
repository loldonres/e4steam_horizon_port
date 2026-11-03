package link.e4steam.steam;

import com.codedisaster.steamworks.SteamAPICall;
import com.codedisaster.steamworks.SteamFriends;
import com.codedisaster.steamworks.SteamFriendsCallback;
import com.codedisaster.steamworks.SteamID;
import com.codedisaster.steamworks.SteamMatchmaking;
import com.codedisaster.steamworks.SteamMatchmakingCallback;
import com.codedisaster.steamworks.SteamNativeHandle;
import com.codedisaster.steamworks.SteamResult;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongConsumer;
import link.e4steam.Agnos;
import link.e4steam.E4steamClient;
import link.e4steam.MinecraftVersion;
import link.e4steam.Mirror;

final class SteamLobbyManager implements AutoCloseable {
   static final int VANILLA_LOBBY_CAPACITY = 8;
   static final int VANILLA_MAX_GUESTS = 7;
   static final int HOST_LOBBY_MAX_ATTEMPTS = 6;
   private static final String KEY_PROTOCOL = "e4steam_protocol";
   private static final String KEY_MINECRAFT = "e4steam_minecraft";
   private static final String KEY_ENDPOINT = "e4steam_endpoint";
   private static final String PROTOCOL_VERSION = Byte.toString((byte)4);
   private static final String LOBBY_CONNECT_PREFIX = "e4steam-lobby:";
   private static final long GUEST_JOIN_TIMEOUT_MILLIS = 30000L;
   private static final long CANCELED_JOIN_TOMBSTONE_MILLIS = 60000L;
   private final SteamRuntime runtime;
   private final String minecraftVersion = MinecraftVersion.current();
   private final SteamFriends friends;
   private final SteamMatchmaking matchmaking;
   private SteamSession pendingHostOwner;
   private SteamAccessMode pendingHostAccessMode;
   private SteamAddress pendingHostAddress;
   private CompletableFuture<Long> pendingHostResult;
   private int pendingHostAttempts;
   private boolean pendingHostCanceled;
   private SteamSession queuedHostOwner;
   private SteamAccessMode queuedHostAccessMode;
   private SteamAddress queuedHostAddress;
   private CompletableFuture<Long> queuedHostResult;
   private SteamSession hostLobbyOwner;
   private SteamAccessMode hostLobbyAccessMode;
   private long hostLobbyId;
   private long guestLobbyId;
   private long guestHostSteamId;
   private long guestInviterSteamId;
   private SteamGuestJoinState guestJoinState;
   private String guestEndpoint;
   private long requestedLobbyId;
   private long requestedFriendId;
   private long requestedJoinDeadlineMillis;
   private boolean requestedDirectoryJoin;
   private boolean guestDirectoryJoin;
   private final Map<Long, Long> canceledJoinLobbyDeadlines = new HashMap<>();

   SteamLobbyManager(SteamRuntime runtime) {
      this.runtime = runtime;
      this.friends = new SteamFriends(
         new SteamFriendsCallback() {
            @Override
            public void onGameLobbyJoinRequested(SteamID lobby, SteamID friend) {
               SteamLobbyManager.this.requestJoin(lobby, friend, false);
            }

            @Override
            public void onGameRichPresenceJoinRequested(SteamID friend, String connect) {
               if (connect != null) {
                  long friendId = SteamNativeHandle.getNativeHandle(friend);
                  Optional<SteamAddress> direct = SteamAddress.tryParse(connect);
                  if (direct.isPresent()
                     && direct.get().steamId() == friendId
                     && SteamLobbyManager.this.friends.getFriendRelationship(friend) == SteamFriends.FriendRelationship.Friend) {
                     E4steamClient.acceptDirectSteamInvite(connect, SteamLobbyManager.this.friends.getFriendPersonaName(friend));
                  } else if (connect.startsWith("e4steam-lobby:")) {
                     try {
                        long lobbyId = Long.parseUnsignedLong(connect.substring("e4steam-lobby:".length()));
                        SteamLobbyManager.this.requestJoin(SteamID.createFromNativeHandle(lobbyId), friend, false);
                     } catch (NumberFormatException var8) {
                        E4steamClient.LOGGER.debug("Ignored an invalid Steam rich-presence join string");
                     }
                  }
               }
            }
         }
      );
      this.matchmaking = new SteamMatchmaking(new SteamMatchmakingCallback() {
         @Override
         public void onLobbyCreated(SteamResult result, SteamID lobby) {
            SteamLobbyManager.this.handleLobbyCreated(result, lobby);
         }

         @Override
         public void onLobbyEnter(SteamID lobby, int chatPermissions, boolean blocked, SteamMatchmaking.ChatRoomEnterResponse response) {
            SteamLobbyManager.this.handleLobbyEnter(lobby, response);
         }

         @Override
         public void onLobbyDataUpdate(SteamID lobby, SteamID member, boolean success) {
            long lobbyId = SteamNativeHandle.getNativeHandle(lobby);
            if (success && SteamLobbyManager.this.guestLobbyId == lobbyId && SteamLobbyManager.this.guestEndpoint == null) {
               SteamLobbyManager.this.resolveGuestEndpoint();
            }
         }

         @Override
         public void onLobbyChatUpdate(SteamID lobby, SteamID changedUser, SteamID makingChange, SteamMatchmaking.ChatMemberStateChange stateChange) {
            if (stateChange != SteamMatchmaking.ChatMemberStateChange.Entered) {
               long lobbyId = SteamNativeHandle.getNativeHandle(lobby);
               long userId = SteamNativeHandle.getNativeHandle(changedUser);
               if (SteamLobbyManager.this.hostLobbyOwner != null && SteamLobbyManager.this.hostLobbyId == lobbyId) {
                  if (userId == runtime.steamIdValue()) {
                     SteamLobbyManager.this.loseHostLobby("Steam removed the host from its lobby");
                     return;
                  }

                  runtime.closeRemoteBridges(userId);
               }

               if (SteamLobbyManager.this.guestLobbyId == lobbyId && (SteamLobbyManager.this.guestHostSteamId == userId || userId == runtime.steamIdValue())) {
                  SteamLobbyManager.this.loseGuestLobby();
               }
            }
         }

         @Override
         public void onLobbyKicked(SteamID lobby, SteamID admin, boolean disconnected) {
            long lobbyId = SteamNativeHandle.getNativeHandle(lobby);
            if (SteamLobbyManager.this.hostLobbyOwner != null && SteamLobbyManager.this.hostLobbyId == lobbyId) {
               SteamLobbyManager.this.loseHostLobby("Steam closed the host lobby");
            } else if (SteamLobbyManager.this.guestLobbyId == lobbyId) {
               SteamLobbyManager.this.loseGuestLobby();
            }
         }
      });
   }

   CompletableFuture<Long> createHostLobby(SteamSession owner, SteamAccessMode accessMode, SteamAddress address) {
      CompletableFuture<Long> result = new CompletableFuture<>();
      if (accessMode == SteamAccessMode.LOCAL_ONLY) {
         result.completeExceptionally(new IOException("Local-only mode does not create a Steam lobby"));
         return result;
      } else if (this.hostLobbyOwner != null || this.queuedHostOwner != null) {
         result.completeExceptionally(new IOException("A Steam lobby is already active"));
         return result;
      } else if (this.pendingHostOwner != null) {
         if (!this.pendingHostCanceled) {
            result.completeExceptionally(new IOException("A Steam lobby is already being created"));
         } else {
            this.queuedHostOwner = owner;
            this.queuedHostAccessMode = accessMode;
            this.queuedHostAddress = address;
            this.queuedHostResult = result;
         }

         return result;
      } else {
         this.issueHostCreate(owner, accessMode, address, result, 0);
         return result;
      }
   }

   void stopHosting(SteamSession owner) {
      if (this.pendingHostOwner == owner) {
         this.pendingHostCanceled = true;
         this.pendingHostResult.completeExceptionally(new IOException("Steam hosting was stopped"));
      }

      if (this.queuedHostOwner == owner) {
         CompletableFuture<Long> result = this.queuedHostResult;
         this.clearQueuedHost();
         result.completeExceptionally(new IOException("Steam hosting was stopped"));
      }

      if (this.hostLobbyOwner == owner) {
         if (this.hostLobbyId != 0L) {
            SteamID lobby = SteamID.createFromNativeHandle(this.hostLobbyId);
            this.matchmaking.setLobbyJoinable(lobby, false);
            this.matchmaking.leaveLobby(lobby);
         }

         this.clearHostLobby();
         this.friends.clearRichPresence();
      }
   }

   void openHostInviteOverlay(SteamSession owner) throws IOException {
      if (this.hostLobbyOwner != owner) {
         throw new IOException("Steam lobby is not ready");
      } else if (shouldUseStandaloneFriends(isUnix(), this.runtime.isOverlayEnabledOnWorker())) {
         openStandaloneSteamFriends();
      } else {
         this.requireOverlay();
         if (this.hostLobbyId == 0L) {
            openFriendsOverlayCompat();
         } else {
            this.friends.activateGameOverlayInviteDialog(SteamID.createFromNativeHandle(this.hostLobbyId));
         }
      }
   }

   void openFriendsOverlay() throws IOException {
      if (shouldUseStandaloneFriends(isUnix(), this.runtime.isOverlayEnabledOnWorker())) {
         openStandaloneSteamFriends();
      } else {
         this.requireOverlay();
         openFriendsOverlayCompat();
      }
   }

   boolean allows(SteamSession owner, long remoteSteamId) {
      return this.hostLobbyOwner != owner ? false : this.isAllowedHostPeer(remoteSteamId);
   }

   long publicHostLobbyId() {
      return this.hostLobbyOwner != null
            && this.hostLobbyAccessMode == SteamAccessMode.CUSTOM
            && SteamCustomAccessGate.isRegistered(this.hostLobbyOwner.customAccessModeId())
         ? this.hostLobbyId
         : 0L;
   }

   boolean joinPublicLobby(long lobbyId) {
      return lobbyId == 0L ? false : this.requestJoin(SteamID.createFromNativeHandle(lobbyId), null, true);
   }

   boolean mayAcceptPeer(long remoteSteamId) {
      return this.guestLobbyId != 0L && this.guestHostSteamId == remoteSteamId ? true : this.hostLobbyOwner != null && this.isAllowedHostPeer(remoteSteamId);
   }

   void forEachKnownSessionPeer(LongConsumer consumer) {
      if (Agnos.proactivelyAcceptSteamPeerSessions()) {
         if (this.guestLobbyId != 0L && this.guestHostSteamId != 0L) {
            consumer.accept(this.guestHostSteamId);
         }

         if (this.hostLobbyOwner != null) {
            if (preAcceptsFriendsForDirectAddress(this.hostLobbyAccessMode)) {
               int friendCount = this.friends.getFriendCount(SteamFriends.FriendFlags.Immediate);

               for (int index = 0; index < friendCount; index++) {
                  SteamID friend = this.friends.getFriendByIndex(index, SteamFriends.FriendFlags.Immediate);
                  if (friend != null) {
                     long remoteSteamId = SteamNativeHandle.getNativeHandle(friend);
                     if (remoteSteamId != 0L && remoteSteamId != this.runtime.steamIdValue()) {
                        consumer.accept(remoteSteamId);
                     }
                  }
               }
            }

            if (this.hostLobbyId != 0L) {
               SteamID lobbyId = SteamID.createFromNativeHandle(this.hostLobbyId);
               int memberCount = this.matchmaking.getNumLobbyMembers(lobbyId);

               for (int indexx = 0; indexx < memberCount; indexx++) {
                  SteamID member = this.matchmaking.getLobbyMemberByIndex(lobbyId, indexx);
                  if (member != null) {
                     long remoteSteamId = SteamNativeHandle.getNativeHandle(member);
                     if (remoteSteamId != 0L
                        && remoteSteamId != this.runtime.steamIdValue()
                        && (
                           this.hostLobbyAccessMode == SteamAccessMode.CUSTOM
                              || this.friends.getFriendRelationship(member) == SteamFriends.FriendRelationship.Friend
                        )) {
                        consumer.accept(remoteSteamId);
                     }
                  }
               }
            }
         }
      }
   }

   static boolean preAcceptsFriendsForDirectAddress(SteamAccessMode accessMode) {
      return accessMode == SteamAccessMode.FRIENDS_ONLY;
   }

   boolean keepsRuntimeAlive() {
      return this.pendingHostOwner != null && !this.pendingHostCanceled
         || this.queuedHostOwner != null
         || this.hostLobbyOwner != null
         || this.guestLobbyId != 0L
         || this.requestedLobbyId != 0L;
   }

   void clientBridgeOpened(long remoteSteamId) {
      if (this.guestLobbyId != 0L && this.guestHostSteamId == remoteSteamId) {
         this.guestJoinState.connected();
         if (this.guestDirectoryJoin) {
            this.runtime.publicJoinActive(this.guestLobbyId);
         }
      }
   }

   void clientBridgeClosed(long remoteSteamId, boolean anotherBridgeExists) {
      if (this.guestLobbyId != 0L && this.guestHostSteamId == remoteSteamId && !anotherBridgeExists) {
         this.leaveGuestLobby();
      }
   }

   void cancelGuestJoin() {
      long directoryLobby = this.requestedDirectoryJoin ? this.requestedLobbyId : (this.guestDirectoryJoin ? this.guestLobbyId : 0L);
      if (directoryLobby != 0L) {
         this.runtime.publicJoinCancelled(directoryLobby);
      }

      this.leaveGuestLobby();
   }

   boolean claimGuestInvite(String endpoint) {
      return this.guestLobbyId != 0L && endpoint != null && endpoint.equals(this.guestEndpoint) && this.guestJoinState.claim();
   }

   boolean beginGuestConnect(String endpoint) {
      if (this.guestLobbyId != 0L && endpoint != null && endpoint.equals(this.guestEndpoint)) {
         boolean started = this.guestJoinState.beginConnect(System.currentTimeMillis() + 30000L);
         if (started && this.guestDirectoryJoin) {
            this.runtime.publicJoinConnecting(this.guestLobbyId);
         }

         return started;
      } else {
         return false;
      }
   }

   void cleanup(long now) {
      this.canceledJoinLobbyDeadlines.entrySet().removeIf(entry -> entry.getValue() <= now);
      if (this.requestedLobbyId != 0L && this.requestedJoinDeadlineMillis <= now) {
         long timedOutLobby = this.requestedLobbyId;
         boolean directoryJoin = this.requestedDirectoryJoin;
         if (directoryJoin) {
            this.runtime.publicJoinFailed(timedOutLobby, SteamPublicJoinTracker.Phase.FAILED, "steam-lobby-timeout");
         }

         this.leaveGuestLobby();
         E4steamClient.showSteamJoinFailure(Mirror.translatable("text.e4steam_minecraft.joinLobbyTimeout"));
      } else {
         if (this.guestLobbyId != 0L && this.guestJoinState.expired(now)) {
            if (this.runtime.hasClientBridgeForRemote(this.guestHostSteamId)) {
               this.guestJoinState.connected();
               return;
            }

            if (this.guestDirectoryJoin) {
               this.runtime.publicJoinFailed(this.guestLobbyId, SteamPublicJoinTracker.Phase.FAILED, "minecraft-connect-timeout");
            }

            this.leaveGuestLobby();
            E4steamClient.showSteamJoinFailure(Mirror.translatable("text.e4steam_minecraft.joinConnectTimeout"));
         }
      }
   }

   private void handleLobbyCreated(SteamResult result, SteamID lobby) {
      SteamSession owner = this.pendingHostOwner;
      SteamAccessMode accessMode = this.pendingHostAccessMode;
      SteamAddress address = this.pendingHostAddress;
      CompletableFuture<Long> hostResult = this.pendingHostResult;
      int attempts = this.pendingHostAttempts;
      boolean canceled = this.pendingHostCanceled;
      this.clearPendingHost();
      long lobbyId = SteamNativeHandle.getNativeHandle(lobby);
      if (owner == null) {
         if (lobbyId != 0L) {
            this.matchmaking.leaveLobby(lobby);
         }
      } else if (canceled) {
         if (lobbyId != 0L) {
            this.matchmaking.leaveLobby(lobby);
         }

         this.issueQueuedHostCreate();
      } else if (shouldRetryHostCreation(result, attempts)) {
         E4steamClient.LOGGER.warn("Steam could not create the lobby ({}); retrying ({}/{})", new Object[]{result, attempts + 1, 6});
         this.issueHostCreate(owner, accessMode, address, hostResult, attempts);
      } else if (result == SteamResult.OK && lobbyId != 0L) {
         boolean metadataReady = this.matchmaking.setLobbyJoinable(lobby, false)
            && this.matchmaking.setLobbyData(lobby, "e4steam_protocol", PROTOCOL_VERSION)
            && this.matchmaking.setLobbyData(lobby, "e4steam_minecraft", this.minecraftVersion)
            && this.matchmaking.setLobbyData(lobby, "e4steam_endpoint", address.inviteString())
            && this.matchmaking.setLobbyJoinable(lobby, true);
         if (!metadataReady) {
            this.matchmaking.leaveLobby(lobby);
            if (!hostResult.isDone()) {
               hostResult.completeExceptionally(new IOException("Steam rejected e4steam lobby metadata"));
            } else {
               E4steamClient.LOGGER.warn("Steam rejected lobby metadata; continuing friends-only sharing by address");
            }

            this.issueQueuedHostCreate();
         } else {
            this.hostLobbyOwner = owner;
            this.hostLobbyAccessMode = accessMode;
            this.hostLobbyId = lobbyId;
            this.friends.clearRichPresence();
            this.friends.setRichPresence("status", "Hosting a Minecraft LAN world");
            if (accessMode == SteamAccessMode.FRIENDS_ONLY || accessMode == SteamAccessMode.CUSTOM) {
               this.friends.setRichPresence("connect", "e4steam-lobby:" + Long.toUnsignedString(lobbyId));
            }

            hostResult.complete(lobbyId);
         }
      } else {
         if (!hostResult.isDone()) {
            hostResult.completeExceptionally(new IOException("Steam lobby creation failed: " + result));
         } else {
            E4steamClient.LOGGER.warn("Steam lobby creation failed with {}; continuing friends-only sharing by address", result);
         }

         this.issueQueuedHostCreate();
      }
   }

   private void issueHostCreate(SteamSession owner, SteamAccessMode accessMode, SteamAddress address, CompletableFuture<Long> result, int completedAttempts) {
      this.pendingHostOwner = owner;
      this.pendingHostAccessMode = accessMode;
      this.pendingHostAddress = address;
      this.pendingHostResult = result;
      this.pendingHostAttempts = completedAttempts + 1;
      this.pendingHostCanceled = false;

      long call;
      try {
         call = createLobbyCompat(this.matchmaking, accessMode, 8);
      } catch (ReflectiveOperationException var9) {
         this.clearPendingHost();
         result.completeExceptionally(new IOException("Steam lobby compatibility call failed", var9));
         this.issueQueuedHostCreate();
         return;
      }

      if (call == 0L) {
         this.clearPendingHost();
         result.completeExceptionally(new IOException("Steam rejected the lobby creation request"));
         this.issueQueuedHostCreate();
      }
   }

   static boolean shouldRetryHostCreation(SteamResult result, int completedAttempts) {
      boolean temporaryNetworkFailure = result == SteamResult.Timeout
         || result == SteamResult.NoConnection
         || result == SteamResult.ServiceUnavailable
         || result == SteamResult.Busy;
      return temporaryNetworkFailure && completedAttempts < 6;
   }

   static boolean canStartBeforeLobby(SteamAccessMode accessMode) {
      return false;
   }

   private void issueQueuedHostCreate() {
      if (this.pendingHostOwner == null && this.hostLobbyOwner == null && this.queuedHostOwner != null) {
         SteamSession owner = this.queuedHostOwner;
         SteamAccessMode accessMode = this.queuedHostAccessMode;
         SteamAddress address = this.queuedHostAddress;
         CompletableFuture<Long> result = this.queuedHostResult;
         this.clearQueuedHost();
         this.issueHostCreate(owner, accessMode, address, result, 0);
      }
   }

   private void clearPendingHost() {
      this.pendingHostOwner = null;
      this.pendingHostAccessMode = null;
      this.pendingHostAddress = null;
      this.pendingHostResult = null;
      this.pendingHostAttempts = 0;
      this.pendingHostCanceled = false;
   }

   private void clearQueuedHost() {
      this.queuedHostOwner = null;
      this.queuedHostAccessMode = null;
      this.queuedHostAddress = null;
      this.queuedHostResult = null;
   }

   private void clearHostLobby() {
      this.hostLobbyOwner = null;
      this.hostLobbyAccessMode = null;
      this.hostLobbyId = 0L;
   }

   private void clearGuestLobby() {
      this.guestLobbyId = 0L;
      this.guestHostSteamId = 0L;
      this.guestInviterSteamId = 0L;
      this.guestJoinState = null;
      this.guestEndpoint = null;
      this.guestDirectoryJoin = false;
   }

   private static long createLobbyCompat(SteamMatchmaking matchmaking, SteamAccessMode accessMode, int capacity) throws ReflectiveOperationException {
      Class<?> steamInterface = matchmaking.getClass().getSuperclass();
      Field callback = steamInterface.getDeclaredField("callback");
      callback.setAccessible(true);
      long callbackHandle = callback.getLong(matchmaking);
      Class<?> nativeType = Class.forName("com.codedisaster.steamworks.SteamMatchmakingNative");
      Method createLobby = nativeType.getDeclaredMethod("createLobby", long.class, int.class, int.class);
      createLobby.setAccessible(true);
      int lobbyType = accessMode == SteamAccessMode.FRIENDS_ONLY ? 1 : (accessMode == SteamAccessMode.CUSTOM ? 2 : 0);
      return (Long)createLobby.invoke(null, callbackHandle, lobbyType, capacity);
   }

   private static void openFriendsOverlayCompat() throws IOException {
      try {
         Class<?> nativeType = Class.forName("com.codedisaster.steamworks.SteamFriendsNative");
         Method activate = nativeType.getDeclaredMethod("activateGameOverlay", String.class);
         activate.setAccessible(true);
         activate.invoke(null, "Friends");
      } catch (ReflectiveOperationException var2) {
         throw new IOException("Steam friends overlay compatibility call failed", var2);
      }
   }

   private static boolean isLinux() {
      return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
   }

   private static boolean isMacOS() {
      String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
      return os.contains("mac") || os.contains("darwin") || os.contains("os x");
   }

   private static boolean isUnix() {
      return isLinux() || isMacOS();
   }

   static boolean shouldUseStandaloneFriends(boolean unix, boolean overlayEnabled) {
      return unix && !overlayEnabled;
   }

   private static void openStandaloneSteamFriends() throws IOException {
      if (isMacOS()) {
         openMacSteamFriends();
      } else {
         openLinuxSteamFriends();
      }
   }

   private static void openLinuxSteamFriends() throws IOException {
      String uri = "steam://open/friends";
      String[][] commands = new String[][]{{"xdg-open", uri}, {"gio", "open", uri}, {"steam", uri}};
      IOException lastFailure = null;

      for (String[] command : commands) {
         try {
            new ProcessBuilder(command).start();
            return;
         } catch (IOException var8) {
            lastFailure = var8;
         }
      }

      throw new IOException("Could not open the Steam friends window on Linux", lastFailure);
   }

   private static void openMacSteamFriends() throws IOException {
      try {
         new ProcessBuilder("open", "steam://open/friends").start();
      } catch (IOException var1) {
         throw new IOException("Could not open the Steam friends window on macOS", var1);
      }
   }

   private boolean requestJoin(SteamID lobby, SteamID friend, boolean directoryJoin) {
      long lobbyId = SteamNativeHandle.getNativeHandle(lobby);
      if (lobbyId != 0L && (this.hostLobbyOwner == null || this.hostLobbyId != lobbyId)) {
         long now = System.currentTimeMillis();
         Long canceledUntil = this.canceledJoinLobbyDeadlines.get(lobbyId);
         if (canceledUntil != null && canceledUntil > now) {
            E4steamClient.showSteamJoinFailure(Mirror.translatable("text.e4steam_minecraft.joinCancelPending"));
            return false;
         } else {
            this.canceledJoinLobbyDeadlines.remove(lobbyId);
            if (this.requestedLobbyId != lobbyId && this.guestLobbyId != lobbyId) {
               if (this.guestLobbyId == 0L
                  || this.guestEndpoint == null && !this.guestJoinState.isConnected() && !this.runtime.hasClientBridgeForRemote(this.guestHostSteamId)) {
                  if (this.guestLobbyId != 0L || this.requestedLobbyId != 0L) {
                     this.leaveGuestLobby();
                  }

                  this.requestedLobbyId = lobbyId;
                  this.requestedFriendId = friend == null ? 0L : SteamNativeHandle.getNativeHandle(friend);
                  this.requestedDirectoryJoin = directoryJoin;
                  this.requestedJoinDeadlineMillis = now + 30000L;
                  SteamAPICall call = this.matchmaking.joinLobby(lobby);
                  if (call != null && call.isValid()) {
                     if (directoryJoin) {
                        this.runtime.publicJoinBegin(lobbyId);
                     }

                     return true;
                  } else {
                     this.requestedLobbyId = 0L;
                     this.requestedFriendId = 0L;
                     this.requestedDirectoryJoin = false;
                     this.requestedJoinDeadlineMillis = 0L;
                     if (directoryJoin) {
                        this.runtime.publicJoinFailed(lobbyId, SteamPublicJoinTracker.Phase.FAILED, "steam-join-rejected");
                     }

                     E4steamClient.showSteamJoinFailure(Mirror.translatable("text.e4steam_minecraft.joinRejected"));
                     return false;
                  }
               } else {
                  E4steamClient.showSteamJoinFailure(Mirror.translatable("text.e4steam_minecraft.joinCurrentSession"));
                  return false;
               }
            } else {
               return false;
            }
         }
      } else {
         return false;
      }
   }

   private void handleLobbyEnter(SteamID lobby, SteamMatchmaking.ChatRoomEnterResponse response) {
      long lobbyId = SteamNativeHandle.getNativeHandle(lobby);
      Long canceledUntil = this.canceledJoinLobbyDeadlines.remove(lobbyId);
      if (canceledUntil != null && canceledUntil > System.currentTimeMillis()) {
         this.matchmaking.leaveLobby(lobby);
      } else if (this.hostLobbyOwner == null || this.hostLobbyId != lobbyId) {
         if (this.requestedLobbyId != lobbyId) {
            this.matchmaking.leaveLobby(lobby);
         } else {
            this.requestedLobbyId = 0L;
            this.requestedJoinDeadlineMillis = 0L;
            boolean directoryJoin = this.requestedDirectoryJoin;
            this.requestedDirectoryJoin = false;
            if (response != SteamMatchmaking.ChatRoomEnterResponse.Success) {
               this.requestedFriendId = 0L;
               if (directoryJoin) {
                  SteamPublicJoinTracker.Phase phase = publicJoinFailurePhase(response);
                  this.runtime.publicJoinFailed(lobbyId, phase, phase == SteamPublicJoinTracker.Phase.FULL ? "steam-lobby-full" : "steam-lobby-rejected");
               }

               E4steamClient.showSteamJoinFailure(Mirror.translatable("text.e4steam_minecraft.joinLobbyEnterFailed", response));
            } else {
               SteamID owner = this.matchmaking.getLobbyOwner(lobby);
               long ownerId = owner == null ? 0L : SteamNativeHandle.getNativeHandle(owner);
               if (ownerId == 0L
                  || ownerId == this.runtime.steamIdValue()
                  || !directoryJoin && this.friends.getFriendRelationship(owner) != SteamFriends.FriendRelationship.Friend) {
                  this.matchmaking.leaveLobby(lobby);
                  this.requestedFriendId = 0L;
                  if (directoryJoin) {
                     this.runtime.publicJoinFailed(lobbyId, SteamPublicJoinTracker.Phase.STALE, "invalid-lobby-owner");
                  }

                  E4steamClient.showSteamJoinFailure(Mirror.translatable("text.e4steam_minecraft.joinOwnerNotFriend"));
               } else {
                  this.guestLobbyId = lobbyId;
                  this.guestHostSteamId = ownerId;
                  this.guestInviterSteamId = this.requestedFriendId;
                  this.guestDirectoryJoin = directoryJoin;
                  this.guestJoinState = new SteamGuestJoinState(System.currentTimeMillis() + 30000L);
                  this.guestEndpoint = null;
                  this.requestedFriendId = 0L;
                  if (directoryJoin) {
                     this.runtime.publicJoinAuthenticating(lobbyId);
                  }

                  this.resolveGuestEndpoint();
               }
            }
         }
      }
   }

   private void resolveGuestEndpoint() {
      if (this.guestLobbyId != 0L && this.guestEndpoint == null) {
         SteamID lobby = SteamID.createFromNativeHandle(this.guestLobbyId);
         String protocol = this.matchmaking.getLobbyData(lobby, "e4steam_protocol");
         String minecraft = this.matchmaking.getLobbyData(lobby, "e4steam_minecraft");
         String endpoint = this.matchmaking.getLobbyData(lobby, "e4steam_endpoint");
         if (protocol == null || protocol.trim().isEmpty() || endpoint == null || endpoint.trim().isEmpty()) {
            this.matchmaking.requestLobbyData(lobby);
         } else if (PROTOCOL_VERSION.equals(protocol) && this.minecraftVersion.equals(minecraft)) {
            Optional<SteamAddress> parsed = SteamAddress.tryParse(endpoint);
            if (parsed.isPresent() && parsed.get().steamId() == this.guestHostSteamId) {
               this.guestEndpoint = endpoint;
               if (this.guestDirectoryJoin) {
                  this.runtime.publicJoinConnecting(this.guestLobbyId);
               }

               this.guestJoinState.waitForConfirmation();
               String hostName = this.friends.getFriendPersonaName(SteamID.createFromNativeHandle(this.guestHostSteamId));
               E4steamClient.acceptSteamInvite(endpoint, hostName);
            } else {
               if (this.guestDirectoryJoin) {
                  this.runtime.publicJoinFailed(this.guestLobbyId, SteamPublicJoinTracker.Phase.STALE, "invalid-target");
               }

               this.leaveGuestLobby();
               E4steamClient.showSteamJoinFailure(Mirror.translatable("text.e4steam_minecraft.joinInvalidAddress"));
            }
         } else {
            if (this.guestDirectoryJoin) {
               this.runtime.publicJoinFailed(this.guestLobbyId, SteamPublicJoinTracker.Phase.INCOMPATIBLE, "minecraft-version");
            }

            this.leaveGuestLobby();
            E4steamClient.showSteamJoinFailure(Mirror.translatable("text.e4steam_minecraft.joinIncompatible"));
         }
      }
   }

   private void requireOverlay() throws IOException {
      if (!this.runtime.isOverlayEnabledOnWorker()) {
         throw new IOException("Steam Overlay is unavailable. Open the Steam friends window and use Join Game instead");
      }
   }

   private boolean isAllowedHostPeer(long remoteSteamId) {
      SteamID remote = SteamID.createFromNativeHandle(remoteSteamId);
      boolean customPublic = this.hostLobbyAccessMode == SteamAccessMode.CUSTOM
         && this.hostLobbyOwner != null
         && SteamCustomAccessGate.isRegistered(this.hostLobbyOwner.customAccessModeId());
      if (!customPublic && this.friends.getFriendRelationship(remote) != SteamFriends.FriendRelationship.Friend) {
         return false;
      } else if (this.hostLobbyAccessMode == SteamAccessMode.FRIENDS_ONLY) {
         return true;
      } else {
         SteamID lobbyId = SteamID.createFromNativeHandle(this.hostLobbyId);
         int memberCount = this.matchmaking.getNumLobbyMembers(lobbyId);

         for (int index = 0; index < memberCount; index++) {
            SteamID member = this.matchmaking.getLobbyMemberByIndex(lobbyId, index);
            if (member != null && SteamNativeHandle.getNativeHandle(member) == remoteSteamId) {
               return true;
            }
         }

         return false;
      }
   }

   private void loseHostLobby(String detail) {
      SteamSession lostOwner = this.hostLobbyOwner;
      if (lostOwner != null) {
         this.clearHostLobby();
         this.friends.clearRichPresence();
         lostOwner.runtimeFailed(new IOException(detail));
      }
   }

   private void loseGuestLobby() {
      if (this.guestLobbyId != 0L) {
         if (this.guestDirectoryJoin) {
            this.runtime.publicJoinFailed(this.guestLobbyId, SteamPublicJoinTracker.Phase.FAILED, "steam-lobby-lost");
         }

         this.guestJoinState.loseLobby();
         this.runtime.closeRemoteBridges(this.guestHostSteamId);
         this.leaveGuestLobby();
         E4steamClient.showSteamJoinFailure(Mirror.translatable("text.e4steam_minecraft.joinLobbyClosed"));
      }
   }

   private static SteamPublicJoinTracker.Phase publicJoinFailurePhase(SteamMatchmaking.ChatRoomEnterResponse response) {
      String value = String.valueOf(response).toLowerCase(Locale.ROOT);
      return value.contains("full") ? SteamPublicJoinTracker.Phase.FULL : SteamPublicJoinTracker.Phase.FAILED;
   }

   private void leaveGuestLobby() {
      long currentLobbyId = this.guestLobbyId;
      SteamGuestJoinState currentJoinState = this.guestJoinState;
      long requested = this.requestedLobbyId;
      this.clearGuestLobby();
      this.requestedLobbyId = 0L;
      this.requestedFriendId = 0L;
      this.requestedDirectoryJoin = false;
      this.requestedJoinDeadlineMillis = 0L;
      if (currentLobbyId != 0L) {
         currentJoinState.cancel();
         this.matchmaking.leaveLobby(SteamID.createFromNativeHandle(currentLobbyId));
      } else if (requested != 0L) {
         this.canceledJoinLobbyDeadlines.put(requested, System.currentTimeMillis() + 60000L);
         this.matchmaking.leaveLobby(SteamID.createFromNativeHandle(requested));
      }
   }

   @Override
   public void close() {
      CompletableFuture<Long> pendingResult = this.pendingHostResult;
      boolean hadPendingHost = this.pendingHostOwner != null;
      this.clearPendingHost();
      if (hadPendingHost) {
         pendingResult.completeExceptionally(new IOException("Steam runtime stopped before creating the lobby"));
      }

      CompletableFuture<Long> queuedResult = this.queuedHostResult;
      boolean hadQueuedHost = this.queuedHostOwner != null;
      this.clearQueuedHost();
      if (hadQueuedHost) {
         queuedResult.completeExceptionally(new IOException("Steam runtime stopped before creating the lobby"));
      }

      if (this.hostLobbyOwner != null) {
         if (this.hostLobbyId != 0L) {
            SteamID lobby = SteamID.createFromNativeHandle(this.hostLobbyId);
            this.matchmaking.setLobbyJoinable(lobby, false);
            this.matchmaking.leaveLobby(lobby);
         }

         this.clearHostLobby();
      }

      this.leaveGuestLobby();
      this.canceledJoinLobbyDeadlines.clear();
      this.friends.clearRichPresence();
      this.matchmaking.dispose();
      this.friends.dispose();
   }
}
