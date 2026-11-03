package link.e4steam.steam;

import com.codedisaster.steamworks.SteamID;
import com.codedisaster.steamworks.SteamMatchmaking;
import com.codedisaster.steamworks.SteamMatchmakingCallback;
import com.codedisaster.steamworks.SteamMatchmakingKeyValuePair;
import com.codedisaster.steamworks.SteamNativeHandle;
import com.codedisaster.steamworks.SteamResult;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import link.e4steam.api.lobby.LobbyService;

final class SteamAddonLobbyDirectory implements AutoCloseable {
   private static final String OWNER_KEY = "e4steam_addon_directory";
   private static final long TIMEOUT = 15000L;
   private final Map<String, SteamAddonLobbyDirectory.Handle> handles = new HashMap<>();

   static String namespace(String owner) {
      return owner.replace(':', '-') + ":";
   }

   private SteamAddonLobbyDirectory.Handle handle(String owner) throws IOException {
      SteamAddonLobbyDirectory.Handle value = this.handles.get(owner);
      if (value != null && value.retired) {
         throw new IOException("Addon lobby scope closing");
      } else {
         if (value == null) {
            if (this.handles.size() >= 16) {
               throw new IOException("Addon lobby scope limit");
            }

            value = new SteamAddonLobbyDirectory.Handle(owner);
            this.handles.put(owner, value);
         }

         return value;
      }
   }

   CompletableFuture<LobbyService.LobbySnapshot> create(String owner, LobbyService.CreateRequest request) throws IOException {
      return this.handle(owner).create(request);
   }

   CompletableFuture<LobbyService.SearchPage> search(String owner, LobbyService.SearchQuery query) throws IOException {
      return this.handle(owner).search(query);
   }

   LobbyService.LobbySnapshot update(String owner, LobbyService.LobbyId id, LobbyService.Metadata metadata) throws IOException {
      SteamAddonLobbyDirectory.Handle value = this.handles.get(owner);
      if (value != null && value.lobby != null && id.value().equals(value.lobby.id().value())) {
         value.write(SteamID.createFromNativeHandle(Long.parseUnsignedLong(id.value())), metadata);
         value.lobby = new LobbyService.LobbySnapshot(id, value.lobby.visibility(), value.lobby.memberLimit(), Collections.emptyList(), metadata);
         return value.lobby;
      } else {
         throw new IOException("Not an owned lobby");
      }
   }

   boolean leave(String owner, LobbyService.LobbyId id) {
      SteamAddonLobbyDirectory.Handle value = this.handles.get(owner);
      if (value != null && value.lobby != null && value.lobby.id().equals(id)) {
         value.matchmaking.leaveLobby(SteamID.createFromNativeHandle(Long.parseUnsignedLong(id.value())));
         value.lobby = null;
         return true;
      } else {
         return false;
      }
   }

   void release(String owner) {
      SteamAddonLobbyDirectory.Handle value = this.handles.get(owner);
      if (value != null) {
         value.retire();
      }
   }

   void tick(long now) {
      Iterator<SteamAddonLobbyDirectory.Handle> iterator = this.handles.values().iterator();

      while (iterator.hasNext()) {
         SteamAddonLobbyDirectory.Handle value = iterator.next();
         value.tick(now);
         if (value.retired && value.creating == null && value.searching == null) {
            value.close();
            iterator.remove();
         }
      }
   }

   boolean keepsRuntimeAlive() {
      return this.handles
         .values()
         .stream()
         .anyMatch(value -> value.creating != null && !value.creating.isDone() || value.searching != null && !value.searching.isDone());
   }

   @Override
   public void close() {
      for (SteamAddonLobbyDirectory.Handle value : this.handles.values()) {
         value.close();
      }

      this.handles.clear();
   }

   private static <T> CompletableFuture<T> failed(String message) {
      CompletableFuture<T> result = new CompletableFuture<>();
      result.completeExceptionally(new IOException(message));
      return result;
   }

   private static final class Handle implements AutoCloseable {
      private final String owner;
      private final SteamMatchmaking matchmaking;
      private LobbyService.LobbySnapshot lobby;
      private LobbyService.CreateRequest creation;
      private CompletableFuture<LobbyService.LobbySnapshot> creating;
      private CompletableFuture<LobbyService.SearchPage> searching;
      private LobbyService.SearchQuery query;
      private long createDeadline;
      private long searchDeadline;
      private boolean retired;

      Handle(String owner) {
         this.owner = owner;
         this.matchmaking = new SteamMatchmaking(new SteamMatchmakingCallback() {
            @Override
            public void onLobbyCreated(SteamResult result, SteamID id) {
               Handle.this.created(result, id);
            }

            @Override
            public void onLobbyMatchList(int count) {
               Handle.this.found(count);
            }
         });
      }

      CompletableFuture<LobbyService.LobbySnapshot> create(LobbyService.CreateRequest request) {
         if (this.lobby == null && this.creating == null) {
            this.creation = request;
            this.creating = new CompletableFuture<>();
            CompletableFuture<LobbyService.LobbySnapshot> result = this.creating;
            this.createDeadline = System.currentTimeMillis() + 15000L;

            try {
               Field callback = SteamMatchmaking.class.getSuperclass().getDeclaredField("callback");
               callback.setAccessible(true);
               Method create = Class.forName("com.codedisaster.steamworks.SteamMatchmakingNative")
                  .getDeclaredMethod("createLobby", long.class, int.class, int.class);
               create.setAccessible(true);
               int visibility = request.visibility() == LobbyService.Visibility.PUBLIC
                  ? 2
                  : (request.visibility() == LobbyService.Visibility.FRIENDS_ONLY ? 1 : 0);
               long call = (Long)create.invoke(null, callback.getLong(this.matchmaking), visibility, request.memberLimit());
               if (call == 0L) {
                  throw new IOException("Steam rejected lobby creation");
               }
            } catch (IOException | ReflectiveOperationException var8) {
               result.completeExceptionally(new IOException("Steam lobby creation failed"));
               this.creating = null;
               this.creation = null;
            }

            return result;
         } else {
            return SteamAddonLobbyDirectory.failed("Addon lobby already active or pending");
         }
      }

      void created(SteamResult status, SteamID id) {
         if (this.creating != null) {
            CompletableFuture<LobbyService.LobbySnapshot> result = this.creating;
            LobbyService.CreateRequest request = this.creation;
            this.creating = null;
            this.creation = null;
            if (result.isDone()) {
               if (status == SteamResult.OK) {
                  this.matchmaking.leaveLobby(id);
               }
            } else {
               try {
                  if (status != SteamResult.OK || id == null || !id.isValid()) {
                     throw new IOException("Steam lobby create rejected");
                  }

                  this.write(id, request.metadata());
                  if (!this.matchmaking.setLobbyData(id, "e4steam_addon_directory", this.owner)) {
                     throw new IOException("Steam metadata rejected");
                  }

                  this.lobby = new LobbyService.LobbySnapshot(
                     new LobbyService.LobbyId(Long.toUnsignedString(SteamNativeHandle.getNativeHandle(id))),
                     request.visibility(),
                     request.memberLimit(),
                     Collections.emptyList(),
                     request.metadata()
                  );
                  result.complete(this.lobby);
               } catch (IOException | RuntimeException var6) {
                  if (status == SteamResult.OK && id != null) {
                     this.matchmaking.leaveLobby(id);
                  }

                  result.completeExceptionally(new IOException("Steam lobby publication failed"));
               }
            }
         }
      }

      void write(SteamID id, LobbyService.Metadata metadata) throws IOException {
         if (this.lobby != null) {
            for (String key : this.lobby.metadata().values().keySet()) {
               if (!metadata.values().containsKey(key)) {
                  this.matchmaking.deleteLobbyData(id, key);
               }
            }
         }

         for (Entry<String, LobbyService.MetadataValue> entry : metadata.values().entrySet()) {
            if (!entry.getKey().startsWith(SteamAddonLobbyDirectory.namespace(this.owner))) {
               throw new IOException("Wrong metadata namespace");
            }

            if (!this.matchmaking.setLobbyData(id, entry.getKey(), entry.getValue().value())) {
               throw new IOException("Metadata update failed");
            }
         }
      }

      CompletableFuture<LobbyService.SearchPage> search(LobbyService.SearchQuery request) {
         if (this.searching != null) {
            return SteamAddonLobbyDirectory.failed("Steam lobby search pending");
         } else if (!request.cursor().isEmpty()) {
            return SteamAddonLobbyDirectory.failed("Steam lobby search has no cursor");
         } else {
            this.query = request;
            this.searching = new CompletableFuture<>();
            CompletableFuture<LobbyService.SearchPage> result = this.searching;
            this.searchDeadline = System.currentTimeMillis() + 15000L;

            try {
               this.matchmaking.addRequestLobbyListStringFilter("e4steam_addon_directory", this.owner, SteamMatchmaking.LobbyComparison.Equal);

               for (Entry<String, LobbyService.MetadataValue> entry : request.filters().values().entrySet()) {
                  this.matchmaking.addRequestLobbyListStringFilter(entry.getKey(), entry.getValue().value(), SteamMatchmaking.LobbyComparison.Equal);
               }

               this.matchmaking.addRequestLobbyListDistanceFilter(SteamMatchmaking.LobbyDistanceFilter.Worldwide);
               this.matchmaking.addRequestLobbyListResultCountFilter(request.limit());
               if (!this.matchmaking.requestLobbyList().isValid()) {
                  throw new IllegalStateException("Steam search rejected");
               }
            } catch (RuntimeException var5) {
               this.searching = null;
               result.completeExceptionally(new IOException("Steam lobby search failed"));
            }

            return result;
         }
      }

      void found(int count) {
         if (this.searching != null) {
            CompletableFuture<LobbyService.SearchPage> result = this.searching;
            this.searching = null;
            if (!result.isDone()) {
               List<LobbyService.LobbySnapshot> values = new ArrayList<>();

               for (int index = 0; index < Math.min(Math.max(count, 0), this.query.limit()); index++) {
                  try {
                     SteamID id = this.matchmaking.getLobbyByIndex(index);
                     if (this.owner.equals(this.matchmaking.getLobbyData(id, "e4steam_addon_directory"))) {
                        int keys = this.matchmaking.getLobbyDataCount(id);
                        if (keys >= 0 && keys <= 65) {
                           Map<String, LobbyService.MetadataValue> metadata = new LinkedHashMap<>();
                           SteamMatchmakingKeyValuePair pair = new SteamMatchmakingKeyValuePair();

                           for (int keyIndex = 0; keyIndex < keys; keyIndex++) {
                              if (this.matchmaking.getLobbyDataByIndex(id, keyIndex, pair)
                                 && pair.getKey().startsWith(SteamAddonLobbyDirectory.namespace(this.owner))) {
                                 metadata.put(pair.getKey(), LobbyService.MetadataValue.text(pair.getValue()));
                              }
                           }

                           values.add(
                              new LobbyService.LobbySnapshot(
                                 new LobbyService.LobbyId(Long.toUnsignedString(SteamNativeHandle.getNativeHandle(id))),
                                 LobbyService.Visibility.PUBLIC,
                                 this.matchmaking.getLobbyMemberLimit(id),
                                 Collections.emptyList(),
                                 new LobbyService.Metadata(metadata)
                              )
                           );
                        }
                     }
                  } catch (RuntimeException var10) {
                  }
               }

               result.complete(new LobbyService.SearchPage(values, ""));
            }
         }
      }

      void tick(long now) {
         if (this.creating != null && now >= this.createDeadline && !this.creating.isDone()) {
            this.creating.completeExceptionally(new IOException("Steam lobby creation timed out"));
         }

         if (this.searching != null && now >= this.searchDeadline && !this.searching.isDone()) {
            this.searching.completeExceptionally(new IOException("Steam lobby search timed out"));
         }
      }

      void retire() {
         this.retired = true;
         if (this.creating != null) {
            this.creating.completeExceptionally(new IOException("Addon stopped"));
         }

         if (this.searching != null) {
            this.searching.completeExceptionally(new IOException("Addon stopped"));
         }

         if (this.lobby != null) {
            this.matchmaking.leaveLobby(SteamID.createFromNativeHandle(Long.parseUnsignedLong(this.lobby.id().value())));
         }

         this.lobby = null;
      }

      @Override
      public void close() {
         this.retire();
         this.matchmaking.dispose();
      }
   }
}
