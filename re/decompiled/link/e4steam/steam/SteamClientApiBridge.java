package link.e4steam.steam;

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

public final class SteamClientApiBridge {
   private static final SteamClientApiBridge.Delegate NOOP = new SteamClientApiBridge.Delegate() {
      @Override
      public String statusCode() {
         return "NEW";
      }

      @Override
      public String failureCategory() {
         return "";
      }

      @Override
      public SteamClientApiBridge.SessionView sessionView() {
         return SteamClientApiBridge.SessionView.inactive();
      }

      @Override
      public SteamClientApiBridge.MinecraftIdentity localIdentity() {
         return null;
      }

      @Override
      public SteamClientApiBridge.PeerIdentity resolvePeer(String opaquePeerId) {
         return null;
      }

      @Override
      public String opaquePeerId(long remoteSteamId) {
         return null;
      }

      @Override
      public boolean disconnect(long generation) {
         return false;
      }

      @Override
      public long authenticatedMinecraftPeer(SocketAddress remoteAddress) {
         return 0L;
      }

      @Override
      public boolean sendAddonHello(SteamConnectionBridge bridge, byte[] packet) {
         return false;
      }

      @Override
      public boolean sendAddonFrame(SteamConnectionBridge bridge, byte[] packet, boolean reliable) {
         return false;
      }

      @Override
      public CompletableFuture<SteamClientApiBridge.PublicLobbyTarget> publicHostLobbyTarget() {
         return CompletableFuture.completedFuture(null);
      }

      @Override
      public CompletableFuture<Boolean> joinPublicLobby(long lobbyId) {
         return CompletableFuture.completedFuture(Boolean.FALSE);
      }

      @Override
      public SteamClientApiBridge.PublicJoinSnapshot publicJoinSnapshot(long lobbyId) {
         return SteamClientApiBridge.PublicJoinSnapshot.idle();
      }

      @Override
      public void cancelGuestJoin() {
      }

      @Override
      public boolean acceptDirectSteamInvite(String descriptor, String displayName) {
         return false;
      }
   };
   private static final AtomicReference<SteamClientApiBridge.Delegate> CURRENT = new AtomicReference<>(NOOP);

   private SteamClientApiBridge() {
   }

   static AutoCloseable install(SteamClientApiBridge.Delegate delegate) {
      if (delegate == null) {
         throw new NullPointerException("delegate");
      } else if (!CURRENT.compareAndSet(NOOP, delegate)) {
         throw new IllegalStateException("Steam client API bridge is already installed");
      } else {
         return () -> CURRENT.compareAndSet(delegate, NOOP);
      }
   }

   public static String statusCode() {
      return CURRENT.get().statusCode();
   }

   public static String failureCategory() {
      return CURRENT.get().failureCategory();
   }

   public static SteamClientApiBridge.SessionView sessionView() {
      return CURRENT.get().sessionView();
   }

   public static SteamClientApiBridge.MinecraftIdentity localIdentity() {
      return CURRENT.get().localIdentity();
   }

   public static SteamClientApiBridge.PeerIdentity resolvePeer(String opaquePeerId) {
      return CURRENT.get().resolvePeer(opaquePeerId);
   }

   public static boolean disconnect(long generation) {
      return CURRENT.get().disconnect(generation);
   }

   public static long authenticatedMinecraftPeer(SocketAddress remoteAddress) {
      return CURRENT.get().authenticatedMinecraftPeer(remoteAddress);
   }

   public static CompletableFuture<SteamClientApiBridge.PublicLobbyTarget> publicHostLobbyTarget() {
      return CURRENT.get().publicHostLobbyTarget();
   }

   public static CompletableFuture<Boolean> joinPublicLobby(long lobbyId) {
      return CURRENT.get().joinPublicLobby(lobbyId);
   }

   public static SteamClientApiBridge.PublicJoinSnapshot publicJoinSnapshot(long lobbyId) {
      return CURRENT.get().publicJoinSnapshot(lobbyId);
   }

   public static void cancelGuestJoin() {
      CURRENT.get().cancelGuestJoin();
   }

   public static boolean acceptDirectSteamInvite(String descriptor, String displayName) {
      return CURRENT.get().acceptDirectSteamInvite(descriptor, displayName);
   }

   static String opaquePeerId(long remoteSteamId) {
      return CURRENT.get().opaquePeerId(remoteSteamId);
   }

   static boolean sendAddonHello(SteamConnectionBridge bridge, byte[] packet) {
      return CURRENT.get().sendAddonHello(bridge, packet);
   }

   static boolean sendAddonFrame(SteamConnectionBridge bridge, byte[] packet, boolean reliable) {
      return CURRENT.get().sendAddonFrame(bridge, packet, reliable);
   }

   interface Delegate {
      String statusCode();

      String failureCategory();

      SteamClientApiBridge.SessionView sessionView();

      SteamClientApiBridge.MinecraftIdentity localIdentity();

      SteamClientApiBridge.PeerIdentity resolvePeer(String var1);

      String opaquePeerId(long var1);

      boolean disconnect(long var1);

      long authenticatedMinecraftPeer(SocketAddress var1);

      boolean sendAddonHello(SteamConnectionBridge var1, byte[] var2);

      boolean sendAddonFrame(SteamConnectionBridge var1, byte[] var2, boolean var3);

      CompletableFuture<SteamClientApiBridge.PublicLobbyTarget> publicHostLobbyTarget();

      CompletableFuture<Boolean> joinPublicLobby(long var1);

      SteamClientApiBridge.PublicJoinSnapshot publicJoinSnapshot(long var1);

      void cancelGuestJoin();

      boolean acceptDirectSteamInvite(String var1, String var2);
   }

   public static final class MinecraftIdentity {
      private final UUID minecraftUuid;
      private final String minecraftName;

      MinecraftIdentity(UUID minecraftUuid, String minecraftName) {
         this.minecraftUuid = Objects.requireNonNull(minecraftUuid, "minecraftUuid");
         this.minecraftName = Objects.requireNonNull(minecraftName, "minecraftName");
      }

      public UUID minecraftUuid() {
         return this.minecraftUuid;
      }

      public String minecraftName() {
         return this.minecraftName;
      }

      @Override
      public String toString() {
         return "SteamClientMinecraftIdentity{uuid=" + this.minecraftUuid + "}";
      }
   }

   public static final class PeerIdentity {
      private final String opaquePeerId;
      private final UUID minecraftUuid;
      private final String minecraftName;

      PeerIdentity(String opaquePeerId, UUID minecraftUuid, String minecraftName) {
         this.opaquePeerId = Objects.requireNonNull(opaquePeerId, "opaquePeerId");
         this.minecraftUuid = Objects.requireNonNull(minecraftUuid, "minecraftUuid");
         this.minecraftName = Objects.requireNonNull(minecraftName, "minecraftName");
      }

      public String opaquePeerId() {
         return this.opaquePeerId;
      }

      public UUID minecraftUuid() {
         return this.minecraftUuid;
      }

      public String minecraftName() {
         return this.minecraftName;
      }

      @Override
      public String toString() {
         return "SteamClientPeerIdentity{opaque}";
      }
   }

   public static final class PublicJoinSnapshot {
      private final String stateCode;
      private final String detailCode;

      PublicJoinSnapshot(String stateCode, String detailCode) {
         this.stateCode = stateCode == null ? "IDLE" : stateCode;
         this.detailCode = detailCode == null ? "" : detailCode;
      }

      static SteamClientApiBridge.PublicJoinSnapshot idle() {
         return new SteamClientApiBridge.PublicJoinSnapshot("IDLE", "");
      }

      public String stateCode() {
         return this.stateCode;
      }

      public String detailCode() {
         return this.detailCode;
      }

      @Override
      public String toString() {
         return "PublicJoinSnapshot{state=" + this.stateCode + ", target=opaque}";
      }
   }

   public static final class PublicLobbyTarget {
      private final long lobbyId;
      private final long generation;

      PublicLobbyTarget(long lobbyId, long generation) {
         this.lobbyId = lobbyId;
         this.generation = generation;
      }

      public long lobbyId() {
         return this.lobbyId;
      }

      public long generation() {
         return this.generation;
      }

      @Override
      public String toString() {
         return "PublicLobbyTarget{lobby=opaque,generation=" + this.generation + "}";
      }
   }

   public static final class SessionView {
      private final String sessionId;
      private final long generation;
      private final String roleCode;
      private final String stateCode;
      private final int capacity;
      private final List<SteamClientApiBridge.PeerIdentity> peers;

      SessionView(String sessionId, long generation, String roleCode, String stateCode, int capacity, List<SteamClientApiBridge.PeerIdentity> peers) {
         this.sessionId = sessionId == null ? "" : sessionId;
         this.generation = generation;
         this.roleCode = roleCode == null ? "NONE" : roleCode;
         this.stateCode = stateCode == null ? "NONE" : stateCode;
         this.capacity = Math.max(0, capacity);
         this.peers = Collections.unmodifiableList(new ArrayList<>(peers == null ? Collections.emptyList() : peers));
      }

      static SteamClientApiBridge.SessionView inactive() {
         return new SteamClientApiBridge.SessionView("", 0L, "NONE", "NONE", 0, Collections.emptyList());
      }

      public boolean active() {
         return this.generation > 0L;
      }

      public String sessionId() {
         return this.sessionId;
      }

      public long generation() {
         return this.generation;
      }

      public String roleCode() {
         return this.roleCode;
      }

      public String stateCode() {
         return this.stateCode;
      }

      public int capacity() {
         return this.capacity;
      }

      public List<SteamClientApiBridge.PeerIdentity> peers() {
         return this.peers;
      }

      @Override
      public String toString() {
         return "SteamClientSessionView{generation="
            + this.generation
            + ", role="
            + this.roleCode
            + ", state="
            + this.stateCode
            + ", peers="
            + this.peers.size()
            + "}";
      }
   }
}
