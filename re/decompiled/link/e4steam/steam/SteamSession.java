package link.e4steam.steam;

import java.security.SecureRandom;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import link.e4steam.Config;
import link.e4steam.E4steamClient;
import link.e4steam.Mirror;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

public final class SteamSession {
   private static final SecureRandom SECURE_RANDOM = new SecureRandom();
   private static final long HOST_LOBBY_START_TIMEOUT_SECONDS = 75L;
   private final Object lifecycleLock = new Object();
   private final int localPort;
   private final SteamAccessMode accessMode;
   private final String customAccessModeId;
   private final byte[] inviteToken = new byte[16];
   private final AtomicBoolean startRequested = new AtomicBoolean();
   public volatile SteamSession.State state = SteamSession.State.STARTING;
   public volatile Throwable failureCause;
   private volatile SteamAddress address;
   private SteamRuntime.Activity runtimeActivity;

   public SteamSession(int localPort) {
      this(localPort, SteamAccessMode.FRIENDS_ONLY);
   }

   public SteamSession(int localPort, SteamAccessMode accessMode) {
      this(localPort, accessMode, "");
   }

   public SteamSession(int localPort, SteamAccessMode accessMode, String customAccessModeId) {
      if (localPort >= 1 && localPort <= 65535) {
         this.localPort = localPort;
         this.accessMode = Objects.requireNonNull(accessMode, "accessMode");
         String custom = customAccessModeId == null ? "" : customAccessModeId.trim();
         if (accessMode == SteamAccessMode.CUSTOM && !custom.matches("[a-z][a-z0-9_.-]{0,31}:[a-z][a-z0-9_.-]{0,63}")) {
            throw new IllegalArgumentException("Invalid custom access mode");
         } else {
            this.customAccessModeId = accessMode == SteamAccessMode.CUSTOM ? custom : "";
            SECURE_RANDOM.nextBytes(this.inviteToken);
         }
      } else {
         throw new IllegalArgumentException("Invalid LAN port: " + localPort);
      }
   }

   public int localPort() {
      return this.localPort;
   }

   public SteamAddress address() {
      return this.address;
   }

   public SteamAccessMode accessMode() {
      return this.accessMode;
   }

   public String customAccessModeId() {
      return this.customAccessModeId;
   }

   public void startAsync() {
      if (this.startRequested.compareAndSet(false, true)) {
         Thread thread = new Thread(this::start, "e4steam-steam-session-start");
         thread.setDaemon(true);
         thread.start();
      }
   }

   public void stop() {
      SteamRuntime.Activity activity;
      synchronized (this.lifecycleLock) {
         if (this.state == SteamSession.State.STOPPED || this.state == SteamSession.State.STOPPING) {
            return;
         }

         this.state = SteamSession.State.STOPPING;
         SteamRuntime.get().stopHosting(this);
         activity = this.detachRuntimeActivity();
         this.state = SteamSession.State.STOPPED;
      }

      closeActivity(activity);
   }

   public CompletableFuture<Void> openInviteOverlayAsync() {
      if (this.state != SteamSession.State.STARTED) {
         return failedFuture(new IllegalStateException("The Steam world is not ready for invitations"));
      } else {
         try {
            return SteamRuntime.get().openHostInviteOverlay(this);
         } catch (Throwable var2) {
            return failedFuture(var2);
         }
      }
   }

   private static <T> CompletableFuture<T> failedFuture(Throwable throwable) {
      CompletableFuture<T> failed = new CompletableFuture<>();
      failed.completeExceptionally(throwable);
      return failed;
   }

   private void start() {
      SteamRuntime runtime = SteamRuntime.get();

      try {
         SteamRuntime.Activity activity = runtime.acquireActivity();
         boolean canStart;
         synchronized (this.lifecycleLock) {
            canStart = this.state == SteamSession.State.STARTING;
            if (canStart) {
               this.runtimeActivity = activity;
            }
         }

         if (!canStart) {
            closeActivity(activity);
            return;
         }

         runtime.awaitReady();
         CompletableFuture<Long> lobbyCreated;
         SteamAddress newAddress;
         synchronized (this.lifecycleLock) {
            if (this.state != SteamSession.State.STARTING) {
               return;
            }

            runtime.startHosting(this, this.localPort, Config.INSTANCE.voiceChatPort.value(), this.inviteToken, this.accessMode);
            newAddress = new SteamAddress(runtime.steamIdValue(), this.inviteToken);
            this.address = newAddress;
            lobbyCreated = runtime.createHostLobby(this, this.accessMode, newAddress);
         }

         lobbyCreated.get(75L, TimeUnit.SECONDS);
         synchronized (this.lifecycleLock) {
            if (this.state != SteamSession.State.STARTING) {
               return;
            }

            this.state = SteamSession.State.STARTED;
         }

         E4steamClient.LOGGER.info("Steam LAN share ready for Steam user {}", Long.toUnsignedString(newAddress.steamId()));
         this.showReadyMessage(newAddress.inviteString());
      } catch (Throwable var14) {
         Throwable throwable = var14;
         SteamRuntime.Activity activity;
         synchronized (this.lifecycleLock) {
            if (this.state != SteamSession.State.STARTING) {
               return;
            }

            this.failureCause = throwable;
            this.state = SteamSession.State.UNHEALTHY;
            runtime.stopHosting(this);
            activity = this.detachRuntimeActivity();
         }

         closeActivity(activity);
         E4steamClient.LOGGER.error("Could not start the Steam LAN share", var14);
         this.showFailureMessage(var14);
      }
   }

   private void showReadyMessage(String endpoint) {
      try {
         Component message;
         if (this.accessMode == SteamAccessMode.INVITE_ONLY) {
            message = Mirror.translatable("text.e4steam_minecraft.privateLobbyReady");
         } else {
            boolean hidden = Config.INSTANCE.hideDomainInChat.value();
            Component visible = hidden ? Mirror.translatable("text.e4steam_minecraft.hiddenDomain") : Mirror.literal(endpoint);
            Component clickableAddress = Mirror.withStyle(
               visible,
               style -> style.withColor(ChatFormatting.GREEN)
                  .withClickEvent(Mirror.copyToClipboard(endpoint))
                  .withHoverEvent(Mirror.showText(Mirror.translatable("text.e4steam_minecraft.addressCopyHelp")))
            );
            message = Mirror.translatable("text.e4steam_minecraft.domainAssigned", clickableAddress);
         }

         Component stopButton = Mirror.withStyle(
            Mirror.translatable("text.e4steam_minecraft.clickToStop"),
            style -> style.withColor(ChatFormatting.RED)
               .withClickEvent(Mirror.runCommand("/e4steam stop"))
               .withHoverEvent(Mirror.showText(Mirror.translatable("text.e4steam_minecraft.stopSharingHelp")))
         );
         Component inviteButton = Mirror.withStyle(
            Mirror.translatable("text.e4steam_minecraft.inviteFriends"),
            style -> style.withColor(ChatFormatting.BLUE)
               .withClickEvent(Mirror.runCommand("/e4steam invite"))
               .withHoverEvent(Mirror.showText(Mirror.translatable("text.e4steam_minecraft.inviteFriendsHelp")))
         );
         Component readyMessage = Mirror.append(
            Mirror.append(Mirror.append(message, Mirror.literal(" [")), inviteButton),
            Mirror.append(Mirror.literal("] ["), Mirror.append(stopButton, Mirror.literal("]")))
         );
         Mirror.addMessageIf(readyMessage, () -> this.state == SteamSession.State.STARTED && E4steamClient.session == this);
      } catch (Throwable var6) {
         E4steamClient.LOGGER.warn("Steam share started, but its chat message could not be displayed", var6);
      }
   }

   private void showFailureMessage(Throwable throwable) {
      try {
         String detail = throwable.getMessage();
         Component message = Mirror.translatable("text.e4steam_minecraft.error");
         if (detail != null && !detail.trim().isEmpty()) {
            message = Mirror.append(message, Mirror.literal(": " + detail));
         }

         Component retryButton = Mirror.withStyle(
            Mirror.translatable("text.e4steam_minecraft.steamUnavailable"), style -> style.withClickEvent(Mirror.runCommand("/e4steam restart"))
         );
         Component failureMessage = Mirror.append(Mirror.append(message, Mirror.literal(" [")), Mirror.append(retryButton, Mirror.literal("]")));
         Mirror.addMessageIf(failureMessage, () -> this.state == SteamSession.State.UNHEALTHY && E4steamClient.session == this);
      } catch (Throwable var6) {
         E4steamClient.LOGGER.warn("Could not display the Steam initialization error in chat", var6);
      }
   }

   void runtimeFailed(Throwable throwable) {
      SteamRuntime.Activity activity;
      synchronized (this.lifecycleLock) {
         if (this.state == SteamSession.State.STOPPED || this.state == SteamSession.State.STOPPING || this.state == SteamSession.State.UNHEALTHY) {
            return;
         }

         this.failureCause = throwable;
         this.state = SteamSession.State.UNHEALTHY;
         SteamRuntime.get().stopHosting(this);
         activity = this.detachRuntimeActivity();
      }

      closeActivity(activity);
      this.showFailureMessage(throwable);
   }

   private SteamRuntime.Activity detachRuntimeActivity() {
      SteamRuntime.Activity activity = this.runtimeActivity;
      this.runtimeActivity = null;
      return activity;
   }

   private static void closeActivity(SteamRuntime.Activity activity) {
      if (activity != null) {
         activity.close();
      }
   }

   public static enum State {
      STARTING,
      STARTED,
      UNHEALTHY,
      STOPPING,
      STOPPED;
   }
}
