# e4steam-rs — Steam-транспорт для LAN-миров Minecraft (Horizon)

Мостит трафик `127.0.0.1` в Steam P2P и говорит по тому же wire-протоколу `E4ST` v4, что и
Java-мод [e4steam](https://github.com/Kamilhik/e4steam). Поэтому в одном мире могут играть
Horizon (Rust) и NeoForge/Fabric (Java-мод) — кросс-плей держится на побайтовой совместимости
кадра, инвайтов и лобби.

Две формы, один движок (`horizon-mod/mixins/e4steam/engine`):

| Форма | Как запускается | Команды |
|---|---|---|
| **Нативный мод Horizon** — `horizon-mod/target/e4steam-<ver>.hnmod` | launcher собирает его вместе с игрой; Rust ставит сам launcher | `/steam …` в чате |
| **Sidecar** — `cli/`, бинарник `e4steam-rs.exe` | рядом с любой сборкой Horizon | `e4steam-rs.exe host \| join \| doctor` |

Одновременно — нельзя: один Steam-аккаунт не держит два контекста и два лобби на канале 480.
Установка, пины и команды мода: [horizon-mod/README.md](horizon-mod/README.md).

## Сборка (sidecar)

Нужен Rust (GNU toolchain на Windows) и `steam_api64.dll` из Steam-клиента или из
мода (в репозитории есть копия: `re/decompiled/steam_api64.dll`).

```sh
cargo build --release
# рядом с exe кладём steam_api64.dll
cp target/release/e4steam-rs.exe Steam/steam_api64.dll ./game/
```

`steam_appid.txt` (содержимое `480`) создаётся автоматически при запуске — это
тестовый AppID Steamworks, тот же, что использует Java-мод.

## Запуск

Хост (открытый по LAN мир уже должен работать в Horizon):

```sh
e4steam-rs.exe host                      # порт мира находится сам по LAN-анонсу
e4steam-rs.exe host --port 51234         # или задать вручную
e4steam-rs.exe host --access invite      # friends | invite | custom | local
e4steam-rs.exe host --minecraft 26.3     # строка, которую обязаны повторить гости
```

Гость:

```sh
e4steam-rs.exe join s-xxxxxxxxx-yyyyyyyy.steam   # код из инвайта
e4steam-rs.exe join --lobby 109775243275130618   # по id лобби
e4steam-rs.exe join                              # ждать инвайт из оверлея Steam
```

После запуска гость печатает `127.0.0.1:<порт>` — его вставляют в Minecraft →
Multiplayer → Direct Connect.

Диагностика (что видно: DLL, логин Steam, LAN-интерфейс, анонс мира):

```sh
e4steam-rs.exe doctor
```

Остановка — Ctrl+C: лобби закрывается, rich presence чистится, мосты рвутся.

## Кросс-плей

| Сторона | Что должно совпасть |
|---|---|
| Java-мод 0.3.2 / 0.3.4 | wire-версия `4`, длина кадра, флаги, канал `480` |
| Версия Minecraft | `e4steam_minecraft` в метаданных лобби сравнивается **посимвольно** (26.3 = protocol 777) |
| Идентичность пира | SteamID64 берётся из Steam-сообщения, а не из Minecraft-логина |

Гость с другой строкой версии отклоняется до соединения — так же, как в моде.

## Что реализовано

- Кодек кадров `E4ST` (DATA со сжатием zlib, FIN/RESET/OPEN/OPEN_ACK/BRIDGE_READY), лимиты и флаги.
- Steamworks Flat API без SDK и C++: `InitFlat` + manual dispatch, ISteamUser/Friends/Matchmaking,
  ISteamNetworkingMessages (канал 480), лобби, rich presence, инвайт-диалог.
- Мост localhost TCP ↔ Steam: чанки 32 КиБ, backpressure (2048 слотов, резерв 64 под терминальные
  кадры), входящая очередь 256, ретраи отправки с hold-пакетом, отдельная очередь RESET
  (64 записи, 10 попыток, экспоненциальный backoff 25→2000 мс + джиттер), graceful close 2 с.
- Хост: приём OPEN, проверка токера (постоянное время), лимит 7 гостей, TCP-клиент к порту LAN-мира,
  публикация метаданных лобби и rich presence.
- Гость: листенер на `127.0.0.1`, OPEN с токеном из инвайта, разрешение адреса через лобби,
  дедлайн 30 с.
- Автопоиск порта LAN-мира Horizon по multicast-анонсу `224.0.2.60:4445`.

## Чего пока нет

- Dedicated-сервер через Steam (`SteamGameServer`, `DEDICATED_OPEN`, auth-тикеты) — в процессе.
- Голосовой UDP-канал и аддон-протокол — сознательно вне объёма.
- Подмена UUID игроков (`e4steam:steam-identity:v1:`); Horizon берёт offline-UUID из ника.

## Проверки

`cargo test --workspace` — 85 тестов без железа и без второго аккаунта: кодек кадров,
адреса/инвайты, семафоры исходящей очереди, жизненный цикл моста на настоящих loopback-сокетах,
сквозной тест потока 200 КиБ через два сцепленных моста (хост↔гость без Steam), layout-гарды
Steamworks (`const _: () = assert!(size_of…)`), ретраи RESET, правила допуска и рукопожатия,
грамматика `/steam` и маскирование SteamID.

`horizon-mod/check` — мок `crate::game` с геометрией впрыска как в Horizon: payload и хелперы
проходят типизацию до часовой сборки игры. `bash horizon-mod/tools/validate.sh` прогоняет
настоящие `horizon-mixins plan`/`apply` на закреплённом коммите.

## Лицензия

Apache-2.0. Производное от протокола и семантики Java-мода `e4steam` — см. `NOTICE.md`.
Инвайт-код (`s-…​.steam`) = доступ в мир: не публикуй его в логах и скриншотах; консоль
sidecar'а маскирует чужие SteamID, коды и секреты в сообщениях об ошибках.
