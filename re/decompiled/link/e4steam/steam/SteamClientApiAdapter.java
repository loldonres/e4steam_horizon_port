package link.e4steam.steam;

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import link.e4steam.E4steamClient;

public final class SteamClientApiAdapter {
   private static final AtomicBoolean INSTALLED = new AtomicBoolean();
   private static volatile AutoCloseable lease;

   private SteamClientApiAdapter() {
   }

   public static void install() {
      if (INSTALLED.compareAndSet(false, true)) {
         try {
            lease = SteamClientApiBridge.install(
               new SteamClientApiBridge.Delegate() {
                  @Override
                  public String statusCode() {
                     return SteamRuntime.get().safeStatusCode();
                  }

                  @Override
                  public String failureCategory() {
                     return SteamRuntime.get().safeFailureCategory();
                  }

                  @Override
                  public SteamClientApiBridge.SessionView sessionView() {
                     SteamRuntime.SafeSessionView view = SteamRuntime.get().safeSessionView();
                     ArrayList<SteamClientApiBridge.PeerIdentity> peers = new ArrayList<>();

                     for (SteamRuntime.SafePeerIdentity peer : view.peers()) {
                        peers.add(new SteamClientApiBridge.PeerIdentity(peer.opaquePeerId(), peer.minecraftUuid(), peer.minecraftName()));
                     }

                     return new SteamClientApiBridge.SessionView(view.sessionId(), view.generation(), view.roleCode(), view.stateCode(), view.capacity(), peers);
                  }

                  @Override
                  public SteamClientApiBridge.MinecraftIdentity localIdentity() {
                     SteamRuntime.SafeMinecraftIdentity identity = SteamRuntime.get().safeLocalMinecraftIdentity();
                     return identity == null ? null : new SteamClientApiBridge.MinecraftIdentity(identity.minecraftUuid(), identity.minecraftName());
                  }

                  @Override
                  public SteamClientApiBridge.PeerIdentity resolvePeer(String opaquePeerId) {
                     SteamRuntime.SafePeerIdentity peer = SteamRuntime.get().safeResolvePeer(opaquePeerId);
                     return peer == null ? null : new SteamClientApiBridge.PeerIdentity(peer.opaquePeerId(), peer.minecraftUuid(), peer.minecraftName());
                  }

                  @Override
                  public String opaquePeerId(long remoteSteamId) {
                     return SteamRuntime.get().safeOpaquePeerId(remoteSteamId);
                  }

                  @Override
                  public boolean disconnect(long generation) {
                     return SteamRuntime.get().disconnectSafeSession(generation);
                  }

                  @Override
                  public long authenticatedMinecraftPeer(SocketAddress remoteAddress) {
                     return SteamRuntime.get().authenticatedMinecraftPeer(remoteAddress);
                  }

                  @Override
                  public boolean sendAddonHello(SteamConnectionBridge bridge, byte[] packet) {
                     return SteamRuntime.get().sendAddonHello(bridge, packet);
                  }

                  @Override
                  public boolean sendAddonFrame(SteamConnectionBridge bridge, byte[] packet, boolean reliable) {
                     return SteamRuntime.get().sendAddonFrame(bridge, packet, reliable);
                  }

                  @Override
                  public CompletableFuture<SteamClientApiBridge.PublicLobbyTarget> publicHostLobbyTarget() {
                     return SteamRuntime.get()
                        .publicHostLobbyTarget()
                        .thenApply(target -> target == null ? null : new SteamClientApiBridge.PublicLobbyTarget(target.lobbyId(), target.generation()));
                  }

                  @Override
                  public CompletableFuture<Boolean> joinPublicLobby(long lobbyId) {
                     return SteamRuntime.get().joinPublicLobby(lobbyId);
                  }

                  @Override
                  public SteamClientApiBridge.PublicJoinSnapshot publicJoinSnapshot(long lobbyId) {
                     SteamRuntime.SafePublicJoinSnapshot snapshot = SteamRuntime.get().publicJoinSnapshot(lobbyId);
                     return new SteamClientApiBridge.PublicJoinSnapshot(snapshot.stateCode(), snapshot.detailCode());
                  }

                  @Override
                  public void cancelGuestJoin() {
                     SteamRuntime.get().cancelGuestJoin();
                  }

                  @Override
                  public boolean acceptDirectSteamInvite(String descriptor, String displayName) {
                     if (!SteamDedicatedAddress.tryParse(descriptor).isPresent()) {
                        return false;
                     } else {
                        E4steamClient.acceptDirectSteamInvite(descriptor, displayName);
                        return true;
                     }
                  }
               }
            );
         } catch (RuntimeException var1) {
            INSTALLED.set(false);
            throw var1;
         }
      }
   }

   static AutoCloseable leaseForTests() {
      return lease;
   }
}
