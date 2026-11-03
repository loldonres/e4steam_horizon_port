# e4steam wire protocol (RE spec)

Источник: декомпилят `e4steam-neoforge-mc1.20.2-26.2-v0.3.2.jar` → `re/decompiled` (Vineflower 1.11.1).
Все пути ниже относительны `re/decompiled/link/e4steam/`. Назначение — побайтовая совместимость Rust-sidecar с Java-модом (кросс-плей).

## 1. Идентификаторы

| | |
|---|---|
| App ID | 480 (Spacewar), жёсткая проверка `getAppID()!=480` → fatal: `steam/SteamRuntime.java:1345`; `steam_appid.txt` создаётся в CWD как `480\n`: `:1439-1455` |
| виртуальный порт / канал SteamNetworkingMessages | 480: `steam/SteamRuntime.java:50-51`, `steam/SteamNetworkingSocketsP2PTransport.java:22` |
| wire version | 4 (`steam/SteamProtocol.java:10`) |
| лимиты мостов | ≤64 моста (`SteamRuntime.java:67,89`), ≤7 гостей (`:2004`) |

## 2. Выбор транспорта

- LAN (integrated host/guest): **ISteamNetworkingMessages** — `SendMessageToUser(id, data, len, flags, channel=480)`, `ReceiveMessagesOnChannel(480, msgs, 32)`, `AcceptSessionWithUser`, `CloseSessionWithUser`, `GetSessionConnectionInfo`, `InitRelayNetworkAccess` (`steam/SteamNetworkingMessagesTransport.java:320-346,374-417`). Identity: `type=16 (k_EIdentityType_SteamID)`, `size=8`, `value=SteamID64` (`:280-291`).
- Dedicated: **ISteamNetworkingSockets** (клиент `ConnectP2P(identity, vport=480, flags=0, options=null)` `P2P:121`; сервер `SteamGameServer` + `CreateListenSocketP2P(480,0,null)` `:113`, poll group `:108,152`, `AcceptConnection` `:148`).
- Переключатель: `dedicatedSessionGeneration()>0` → Sockets, иначе Messages (`SteamRuntime.java:1622-1627`).
- Флаги отправки: reliable → `9` (RELIABLE|NO_NAGLE), unreliable → `5` (NO_DELAY|UNRELIABLE) (`P2P:184`, `Messages:106`); `32` (AUTO_RESTART_BROKEN_SESSION) добавляется только к `DEDICATED_OPEN/ACK` (`SteamRuntime.java:1433-1437`; для LAN-хендшейка `AgnosImpl.autoRestartBrokenSteamSessionForHandshake()==false`).
- `NetworkingConnectionInfo` (options) = null, `FlushMessages` не вызывается, relay не запрещается.
- Состояния соединения: connecting=1, finding_route=2, connected=3, closed_by_peer=4, problem_detected=5 (`P2P:31-35`); layout `ConnectionInfo` size=768, state@176, endReason@180, endDebug@184[128] (`P2P:41-45`); `RealTimeStatus` size=128, state@0, pending_unreliable@36, pending_reliable@40, sent_unacked@44 (`:46-50`).

## 3. Кадр (1 Steam-сообщение = ровно 1 кадр)

Заголовок 12 байт, big-endian: `int magic=0x45345354 ("E4ST")`, `u8 version=4`, `u8 type`, `i16 flags`, `i32 connectionId` (0 запрещён). Дальше payload.

| type | имя | payload |
|---|---|---|
| 1 | OPEN | ровно 16 байт invite-token |
| 2 | DATA | 1..32768, TCP-байты Minecraft |
| 3 | FIN | пусто — graceful shutdown потока |
| 4 | RESET | пусто — немедленный разрыв |
| 5 | DATAGRAM | 1..1188 (voice UDP) |
| 6 | OPEN_ACK | `u8 clientPortMode`, `i16 hostPort` |
| 7 | BRIDGE_READY | пусто |
| 8 | DEDICATED_OPEN | `i64 generation`, `u8 nonceLen`, nonce[16..64], `u16 ticketLen`, ticket[1..4096] |
| 9 | DEDICATED_OPEN_ACK | `i64 generation` |
| 10/11 | ADDON_HELLO / ADDON_DATA | 1..32768 (в sidecar не нужен) |

Валидация декодера (`SteamProtocol.java:152-219`): magic/version → иначе null; `flags & ~1 != 0` → null; `flags != 0 && type != 2` → null; `connectionId==0` → null; длина payload по типу (см. таблицу; DEDICATED_OPEN 28..4171); неизвестный type → null; кадр с ошибкой просто игнорируется (`SteamRuntime.java:1788-1797`), >1048576 байт отбрасывается.

Сжатие: **encoder никогда не ставит flags** (все `header(...)` → flags=0), `Deflater` в декомпайле отсутствует целиком. Декодер обязан принимать `flags=1` только для type=2: `i32 uncompressedLength (1..32768)` + полный zlib-поток (RFC1950, adler32), принимается лишь когда поток завершился ровно на `uncompressedLength` и хвостов нет (`SteamProtocol.java:239-273`).

`connectionId`: случайный ненулевой `int` на госте, перевыбор при коллизии (`SteamBridgeRegistry.java:21-28`, `SteamRuntime.java:726-728`); хост переиспользует id из принятого OPEN как ключ, пара `(steamId, connectionId)`.

## 4. Адреса (`SteamAddress.java:13-14,91`, `SteamDedicatedAddress.java:10,34`)

- `s-<SteamID64 base36>-<token128 base36>.steam` (kanon; `BigInteger(1,token).toString(36)`, без ведущих нулей)
- legacy: `e4steam-<SteamID64 dec>-<token hex32>.steam`
- dedicated: `d-<SteamID64 base36>-<generation base36>.steam`
- суффикс `.steam` с завершающей точкой допустим (`\.steam\.?$`, lowercase-normalизация входа)

## 5. Хендшейка LAN (главный путь для Horizon)

Гость: loopback-листенер `127.0.0.1:0`, backlog=1, `setReuseAddress(false)`, soTimeout 30 с (`SteamClientBridge.java:34-41`); после accept → `TCP_NODELAY`+`SO_KEEPALIVE` (`:85-86`) → `sendOpen(token)` (`:96`) → `bridge.start()`.
Хост `handleOpen` (`SteamRuntime.java:1953-1992`): сверка token (`MessageDigest.isEqual`, `SteamInvitationAuthorizer.java:9-16`) + `lobbyManager.allows(steamId)` → иначе standalone RESET; затем TCP-клиент к `127.0.0.1:<lanPort>` с таймаутом 100 мс, NODELAY+KEEPALIVE (`:2022-2024`); регистрация `AuthenticatedLoopbackPeer` по локальному порту (`:2041-2048`); `waitForPeerReadyUntil(now+2000)`; `OPEN_ACK(clientPortMode, hostPort)` (`:800-808`, voice-выключен → mode=1, port=0: `VoiceChatUdpEndpoint.java:44,52-60`).
Гость на OPEN_ACK: поднимает UDP-мост (если port>0), `sendBridgeReady()`, `markPeerReady` (`SteamRuntime.java:1901-1926`). Хост на BRIDGE_READY: `markPeerReady` (`:1942-1951`).
DATA не отправляются до peer-ready; fallback-дедлайны: хост now+2000 мс, гость now+10000 мс (`:736,2057`; `SteamConnectionBridge.java:149-155`).
FIN: EOF локального сокета → 1× FIN + graceful close; удалённый FIN → `flush(); shutdownOutput()`; общий дедлайн graceful 2000 мс (`SteamConnectionBridge.java:24,222-229,300-306,353-358,381-400`). RESET удалённого → `close(false)` сразу (`:231-233`); локальный `close(notifyRemote=true)` → RESET (`:272`).

## 6. Backpressure/тайминги (повторять в Rust)

Исходящая очередь: `ArrayBlockingQueue(2048)`, семафоры DATA=1344 / DATAGRAM=320 / ADDON=128 / handshake(OPEN,OPEN_ACK,DEDICATED_*,BRIDGE_READY,ADDON_HELLO)=64 / standalone RESET=64 (`SteamRuntime.java:52-57,87`, `SteamOutboundQueue.java:16-24,113-135`). `terminalReserve=64`: не-терминальные пакеты отклоняются при `remainingCapacity ≤ 64` (`:23,56-58`). `offer` неблокирующий; читатель сокета при полной очереди спинит 10 мс до 30 с, затем `IOException` → `close(true)` (`SteamConnectionBridge.java:22-23,324-345`). Входящая: 256 слотов + `ArrayBlockingQueue(257)`; переполнение или данные после FIN → `closeForSlowOrInvalidPeer` (`:193-220`). Тик воркера: sleep 10 мс, ≤32 отправки/тик, ≤32 приёма/тик (`SteamRuntime.java:58,1077-1079,1093,1774`). Retry отправ: `LimitExceeded|Busy|NoConnection|ServiceUnavailable` → single-slot hold + backoff 25 мс, дедлайн 30 с, потом дроп (`:1485-1488,1561-1594`); non-retryable → `bridge.close(false)`; RESET → отдельная очередь 64 записи/10 попыток/30 с/25→2000 мс/≤8 за тик (`:61-66,1630-1668`).

## 7. Лобби и доступ

`createLobby(type, capacity=8)` (`SteamLobbyManager.java:434,503-513`); type: FRIENDS_ONLY→1, CUSTOM→2, INVITE_ONLY→0. Ретраи создания только при Timeout/NoConnection/ServiceUnavailable/Busy, ≤6 (`:449-455`). Публикация metadata атомарно: `setLobbyJoinable(false)` → 3× `setLobbyData` → `setLobbyJoinable(true)`, при отказе `leaveLobby` (`:387-401`).

| ключ | значение |
|---|---|
| `e4steam_protocol` | `"4"` (byte→ASCII dec, `:32`) |
| `e4steam_minecraft` | release name vanilla-константы или `"unknown"` (`MinecraftVersion.java:17-37`) |
| `e4steam_endpoint` | `SteamAddress.inviteString()` = `s-…​.steam` |

Rich presence: `status="Hosting a Minecraft LAN world"`, `connect="e4steam-lobby:<lobbyId unsigned dec>"` (RP только для FRIENDS_ONLY/CUSTOM, `:406-408,85`).
Членство/допуск решаются **только на хосте** (`SteamLobbyManager.java:219-221,726-748`): не-друг отсекается, кроме CUSTOM; FRIENDS_ONLY пускает всех друзей; иначе обязательна membership в лобби. `LOCAL_ONLY` не создаёт лобби (`:148-150`). Guest-путь: `joinLobby` → `onLobbyEnter` (таймаут/owner==self/не-друг → `leaveLobby`) → проверка `protocol`+`minecraft` и `parsed.steamId()==guestHostSteamId` (`:682-718`) → `SteamGuestJoinState` фазы RESOLVING→WAITING_FOR_CONFIRMATION→CONNECTING(дедлайн 30 с)→CONNECTED/CANCELED/LOST (`SteamGuestJoinState.java:12-64`).
Лобби перезапуск не переживает: `close()` → `setLobbyJoinable(false)`+`leaveLobby`+`clearRichPresence` (`:796-826`) → старый адрес мёртв.

## 8. Идентичность и Minecraft-логин

- Идентичность пира даёт Steam (SteamID64 из сообщения/соединения), не Minecraft.
- Для авторизованного Steam-ingress хост отключает vanilla-аутентификацию (`@Redirect usesAuthentication()` — `mixin/ServerLoginPacketListenerImplMixin.java:43-53`) и подменяет UUID: `UUID.nameUUIDFromBytes("e4steam:steam-identity:v1:"+unsignedSteamId64)` (`SteamMinecraftIdentity.java:9,15-19`), имя сохраняется; login timeout 600→2400 тиков (`:81-91`).
- Dedicated-хост рвёт любой не-Steam ingress («requires an authenticated e4steam connection», `:55-65`) — поэтому «просто TCP» в Java-dedicated не пройдёт; в integrated-хост пройдёт.
- Dedicated-авторизация: клиент `ISteamUser::GetAuthSessionTicket` (≤4096) + 32-байтный nonce → кадр 8; сервер `ISteamGameServer::BeginAuthSession` + `ValidateAuthTicketResponse` (только `OK`), таймаут auth 15 с, admission 16 с, rate-limit 8/60 с на SteamID, anti-replay nonce SHA-256 base64url TTL 60 с, whitelist обязателен при accessMode≠UNLISTED (`DedicatedAdmissionGate.java:41-125,147-196`; `SteamGameServerRuntimeBackend.java:115-157`).
- Порядок на dedicated: admit → TCP к `127.0.0.1:mcPort` (1000 мс) → `registerAuthenticatedIngress` → `DEDICATED_OPEN_ACK` → старт pump; обратный порядок ломает клиента (`SteamDedicatedServerTransport.java:361-382`). UDP в dedicated выключен (datagram capacity=0, типы 5/6/7 игнорируются, `:42,266-271,469`).
- Внешних HTTP-эндпоинтов нет; `DevelopmentRegistryAttestation` — dev-only за системным свойством.

## 10. Эталонная сборка для кросс-плея (проверено)

MC 26.2 = protocol **776**, MC 26.3/Horizon = protocol **777**, транскода в Horizon нет → друзьям нужна сборка под 26.3. Скачан и декомпилирован `e4steam-neoforge-mc1.20.2-26.3-v0.3.4.jar` (GitHub `Kamilhik/e4steam` releases, SHA-256 `b7c388393b7c27d188c17981073a2471c7ac52b95d5cff8b8dc8f19946d67be0`, `re/decompiled-0.3.4`): `SteamProtocol.java` и `SteamAddress.java` побайтово идентичны 0.3.2, wire-version = 4. Отличия 0.3.4 — только производительность: `SteamReliableDataPacer(4194304, 65536)` (`SteamRuntime.java:107`) и попытка поднять ceiling через `SteamAPI_ISteamNetworkingUtils_SetGlobalConfigValueInt32(utils, 11, 4194304)` (`SteamNetworkingMessagesTransport.java:74,418`) — проверено по официальным заголовкам: `SetGlobalConfigValueInt32(self, ESteamNetworkingConfigValue eValue, int32 val)` — второй аргумент это **enum**, а не строка (строку принимает только `SetGlobalConfigValueString`), так что Java-каст `(char*)11` фактически корректен: 11 = `k_ESteamNetworkingConfig_SendRateMax`. Rust зовёт `set_global_int32(utils, 11, 4194304)`; строки `"SendRateCeiling"` в SDK/DLL вообще нет. Также добавлен `isReliableQueueBackpressured(steamId, maxPendingBytes, maxQueueMicros)` по `GetSessionConnectionInfo` (`:221-232`).

`SteamOverlayRelauncher`/`SteamOverlayLoader` (перезапуск JVM под overlay), mixin-подмены GameProfile/ингресса, `/e4steam-dedicated` brigadier-команды, аддон-API/`SteamAddonProtocol`, публичная директория, `RetroDedicatedServerTransport` (в декомпайле не инстанцируется — мёртвый код), голосовой UDP (не выбран). Сохраняем: redaction секретов в логах (`Doctor.java:17-21,178-192`) и `activateGameOverlayInviteDialog(lobbyId)` (`SteamLobbyManager.java:195-208`).
