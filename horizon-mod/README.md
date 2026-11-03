# e4steam — нативный мод Horizon

Один файл `.hnmod`: Rust-мод, который Horizon собирает вместе с игрой. После установки
в чате появляется `/steam` — тот же интерфейс, что у Java-мода
[e4steam](https://github.com/Kamilhik/e4steam), и тот же wire-протокол `E4ST` v4, поэтому
хост на Horizon и гость на NeoForge/Fabric работают в одном лобби Steam.

Sidecar (`../cli`, бинарник `e4steam-rs.exe`) делает то же самое снаружи игры. Мод и sidecar —
две оболочки одного движка `mixins/e4steam/engine`; **одновременно их запускать нельзя**: один
Steam-аккаунт не держит два контекста и два лобби на канале 480.

## Готовый файл

`target/e4steam-0.1.0.hnmod` — 20 файлов, 58 725 Б, SHA-256
`5d97f590c4d6d437fa538333022a4285110d65582a3591e142f39e1320df01eb`.

Сборка воспроизводима: те же исходники дают те же байты (даты в zip фиксированы, порядок
файлов отсортирован).

| Что закреплено | Значение |
|---|---|
| Horizon | `c9d474dd9116e84c243f8992d75c11314df56f06` |
| horizon-mixins | `d9c83d7096b7ea5b83678da2efdfd6161f74997e` |
| Minecraft | `26.3` (protocol 777) |
| `crates/horizon-client/src/game.rs` | sha256 `4ab99203…e0d3e` (в `compatibility.json`) |

Пин на конкретный коммит, а не на `"hooks": N`, — намеренный: мод читает приватное поле
`Game.lan`, чтобы узнать порт LAN-мира, а это не стабильный API хук-пойнтов. На другом
коммите сборка честно упадёт, а не соберёт молча сломанное.

## Установка

Просто бросить файл в папку недостаточно: launcher держит список включённых нативных модов в
`native-mods.json` инстанса, а собирает их сам — на том коммите Horizon, который мод закрепил.

1. Horizon → страница **Mods**, выбери инстанс.
2. Установи мод: из реестра/GitHub, если он опубликован, или укажи `native-build` на папку с
   бандлом (`mixins/e4steam/`) либо на распакованный `.hnmod`:

   ```sh
   horizon-launcher native-build mixins/e4steam --instance NAME --trust-native-code
   ```

   Без `FOLDER` команда берёт нативные моды, включённые в инстансе. В окне то же самое делает
   **Build** → «I trust these mods: build».
3. Флаг доверия — решение игрока: нативные моды не изолированы и работают с полным доступом к
   компьютеру. Скрипты репозитория его не подставляют.

Установленные лежат в `<instance>/horizon-native-mods/` (в окне — **Open folder**). Первая
сборка скачивает собственный Rust и MinGW launcher'а и занимает 20–30 минут; дальше играет
кэш. Получается версия вида `native-<commit>-<key>`, и она не смешивается с модами,
собранными на других коммитах.

Чтобы правки собирались быстрее: `horizon-launcher settings native-profile dev`.

## Steam со стороны игры

Игровой процесс сам подгружает Steamworks (собственная «Play with friends» в Horizon — это
TCP/STUN, не Steam), поэтому нужно три вещи.

- **`steam_api64.dll`** должна быть доступна процессу игры. Порядок поиска: `E4STEAM_STEAM_API`
  (полный путь к dll), рядом с exe игры и в подпапке `steam/`, копии рядом с установленными
  играми в библиотеках Steam, затем обычный поиск Windows (Steam-каталог в PATH).
  Valve-овскую dll мы не распространяем — возьми её из своего Steam.
- **`steam_appid.txt`** со строкой `480` (test AppID, тот же, что у Java-мода) мод пишет сам
  в рабочий каталог или рядом с exe. Если каталог только для чтения, положи файл вручную.
- Steam-клиент должен быть запущен и залогинен; список друзей приходит из него.

`/steam status` скажет, что из этого не выполнено, словами — без стектрейсов.

## Команды

```
/steam host [--port N] [--access friends|invite|custom|local] [--discover MS] [--minecraft V]
/steam join [<invite code>|lobby <id>] [--port N] [--minecraft V]
/steam status
/steam stop
```

Хост — для мира, уже открытого по LAN (Open to LAN): порт берётся из multicast-анонса Horizon
`224.0.2.60:4445`, `--port` перебивает поиск, `--discover` меняет время ожидания (до
60 000 мс). Доступ по умолчанию `friends`.

Гость получает `127.0.0.1:<порт>` — вставлять в Multiplayer → Direct Connect. Код инвайта
`s-xxxxxxxxx-yyyyyyyy.steam` из оверлея Steam или `join lobby <id>`; голый `/steam join`
ждёт инвайта.

Ответы мода приходят строками в чат, а не в консоль: их печатает хук `frame`, сеть живёт на
отдельном потоке и игровой цикл не блокирует.

## Кросс-плей

С Java-модом 0.3.2/0.3.4 совпадают: версия кадра `4`, длина и флаги, zlib-рамка в `DATA`,
канал 480, метаданные лобби (`e4steam_minecraft` сравнивается посимвольно), токен инвайта
(16 байт, проверка постоянным временем), лимит 7 гостей. Подробности — `../re/PROTOCOL.md`.

Гость с другой строкой версии отклоняется до соединения.

## Сборка из исходников

```sh
cargo test --workspace          # 85 тестов; железо и второй аккаунт не нужны
python tools/package.py         # -> target/e4steam-<ver>.hnmod и его sha256
bash tools/validate.sh          # настоящие horizon-mixins plan + apply
```

`validate.sh` берёт исходники Horizon и horizon-mixins ровно по закреплённым коммитам в
`../../.hz`, прогоняет `plan` и `apply` — они только подготавливают исходники игры, ничего
не компилируют и не запускают, поэтому флаг доверия там не нужен, — и печатает форму плана:
3 впрыска, 1 модуль, 1 крейт.

`check/` — фикстура: мок `crate::game` с той же геометрией, что в Horizon, поэтому опечатки в
`runtime.rs` и хелперах видны в `cargo test`, а не в середине часовой сборки игры. Полную
компиляцию впрыснутого мода делает `horizon-mixins check` — это уже сборка нативного кода,
и запускает её игрок.

`tools/package.py` попутно проверяет правила реестра: в архив только `.rs`/`.toml` и
текстовые файлы крейта, зависимости только те, что уже лежат в `Cargo.lock` Horizon, запрет
`[workspace]`, `[patch]`, `[replace]`, `[profile]` и path-зависимостей, лимиты 512 файлов и
64 МиБ. Отказ видно локально, а не в реестре.

## Структура

```
mixins/e4steam/
  horizon-mixin.toml     [mod], [compatibility], [[crate]], [[module]], 3 [[injection]]
  runtime.rs             payload -> crate::game::e4steam
  command.rs commands.rs frame.rs   по одному приватному fn-хелперу на впрыск
  engine/                e4steam-engine: кодек E4ST, Steamworks Flat API, мост, /steam
compatibility.json       пины и sha256 game.rs
tools/                   package.py, validate.sh
check/                   фикстура геометрии для cargo test
```

## Безопасность

Инвайт-код `s-…​.steam` — это доступ в мир. Не выкладывай его в логи и скриншоты. Сообщения
мода маскируют чужие SteamID, адреса подключения и именованные секреты (`token`, `ticket`,
`password`, `secret`, `cookie`, `authorization`, `gslt`) до того, как попадут в чат.

## Лицензия

Apache-2.0; см. `../NOTICE.md` и `../THIRD_PARTY_NOTICES-e4steam.md`.
