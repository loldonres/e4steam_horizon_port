# e4steam — нативный мод Horizon

[English](README.md) · **Русский**

Один файл `.hnmod`: Rust-мод, который Horizon компилирует вместе с игрой. После установки и сборки
в окне чата появляется `/steam` — тот же интерфейс, что у Java-мода
[e4steam](https://github.com/Kamilhik/e4steam), и тот же wire-протокол `E4ST` v4, поэтому хост на
Horizon и гость на NeoForge/Fabric оказываются в одной Steam-лобби.

Установка для игрока (требования, быстрый старт, неисправности) описана в
[главном README](../README.ru.md). Этот файл — про то, как собран бандл и что он меняет в игре.

> Нативные моды — это Rust внутри бинарника игры: без песочницы, с полным доступом к компьютеру.
> Horizon спрашивает разрешение этим доверием (`--trust-native-code`), и решать должен ты — скрипты
> и ассистенты в этом репозитории этот флаг не подставляют.

## Готовый файл

`target/e4steam-0.1.0.hnmod` — 20 файлов, 58 777 байт, SHA-256
`0116f86264261810b4c7c87188a405f764a9117cd64d7bf9f9cfbeecd737ad7d`.

Сборка воспроизводима: те же исходники дают те же байты. Паковщик фиксирует даты в zip на
2026-01-01, сортирует записи и закрепляет права файлов.

| Закреплено | Значение |
|---|---|
| коммит Horizon | `c9d474dd9116e84c243f8992d75c11314df56f06` |
| коммит horizon-mixins | `d9c83d7096b7ea5b83678da2efdfd6161f74997e` |
| Minecraft | `26.3` (protocol 777) |
| `crates/horizon-client/src/game.rs` | sha256 `4ab99203…e0d3e`, в `compatibility.json` |

Пин на коммит, а не объявление `"hooks": N`, — сознательный: payload читает приватное поле `Game.lan`,
 чтобы узнать порт LAN-мира, а это не часть стабильного API хук-пойнтов. На другом коммите сборка
громко упадёт, а не молча соберёт наполовину сломанное.

## Установка

Просто бросить файл в папку недостаточно: launcher хранит список включённых нативных модов в
`native-mods.json` инстанса и собирает их сам, на том коммите Horizon, который мод закрепил.

1. Horizon → страница **Mods**, выбери инстанс.
2. Установи мод: из реестра или с GitHub, когда он опубликован, — или укажи `native-build` на папку
   бандла (`mixins/e4steam/`) либо на распакованный `.hnmod`:

   ```sh
   horizon-launcher native-build mixins/e4steam --instance NAME --trust-native-code
   ```

   Без `FOLDER` команда берёт нативные моды, включённые в инстансе. В окне это кнопка **Build** и
   «I trust these mods: build».
3. Решение, доверять ли коду, остаётся за тобой.

Установленные моды лежат в `<instance>/horizon-native-mods/` (**Open folder** в окне). Первая сборка
скачивает собственные Rust и MinGW launcher'а и занимает 20–30 минут; дальше работает кэш.
Получается версия вида `native-<commit>-<key>`, и она не смешивается с модами с других коммитов.
Во время правок `horizon-launcher settings native-profile dev` ускоряет пересборку примерно в 6 раз.

## Steam внутри процесса игры

Собственная «Play with friends» в Horizon — это TCP/STUN, а не Steam, поэтому в процессе игры
контекста Steamworks нет и мод может занять его сам. Нужно всё равно три условия:

- **`steam_api64.dll`** должна быть доступна процессу игры. Порядок поиска: переменная окружения
  `E4STEAM_STEAM_API` (полный путь), папка рядом с exe игры, подпапка `steam/` рядом с ним, копии
  рядом с установленными играми в библиотеках Steam, затем обычный поиск Windows. Dll от Valve мы
  не распространяем — возьми её из своего Steam.
- **`steam_appid.txt`** со строкой `480` (тестовый AppID Steamworks, тот же, что у Java-мода) мод
  пишет сам в рабочий каталог или рядом с exe. Если каталог только для чтения — положи файл вручную.
- **Steam-клиент** запущен и в него выполнен вход; список друзей приходит оттуда.

`/steam status` словами скажет, чего из этого не хватает, без стектрейса.

## Как это вшито в игру

Бандл — это source-бандл horizon-mixins: Rust и манифест, которые подготавливаются в исходниках
Horizon до компиляции игры. `mixins/e4steam/horizon-mixin.toml` объявляет:

| Часть | Что делает |
|---|---|
| `[[crate]] engine` | `e4steam-engine` становится зависимостью `horizon-client` и укладывается в `native-crates/e4steam/engine/`. Внешняя зависимость одна — `flate2`, и она уже есть в `Cargo.lock` Horizon |
| `[[module]] runtime` | `runtime.rs` вставляется в `crates/horizon-client/src/game.rs` как `pub(crate) mod e4steam`, то есть является дочерним для `crate::game` и потому видит приватное состояние игры (`Game.lan`) и хелперы `pub(super)` |
| `[[injection]] command` | HEAD хука `command`: забирает `/steam …` из чата, пока сервер не увидел неизвестную команду |
| `[[injection]] commands` | HEAD хука `commands`: добавляет `steam` в автодополнение окна чата |
| `[[injection]] frame` | HEAD хука `frame`: выкладывает строки от Steam-потока в чат |

Каждый впрыск вызывает однострочный приватный хелпер (`command.rs`, `commands.rs`, `frame.rs`) —
форму, которую требует horizon-mixins, — а хелпер передаёт в `crate::game::e4steam::on_*`. Эти точки
входа объявлены `pub(super)`, а не `pub(crate)`: `Game` приватен для `crate::game`, поэтому
сигнатура уровня всего крейта называла бы тип менее видимый, чем сама функция.

Вся настоящая работа идёт в `mixins/e4steam/engine` и на **отдельном потоке**: Flat API Steamworks
не потокобезопасен, и `Runtime::tick` обязан принадлежать тому же потоку, что владеет контекстом,
поэтому игровой цикл только кладёт команды в канал и забирает строки обратно. Ничего в моде не
блокирует клиент.

## Сборка и проверка

```sh
cargo test --workspace          # 85 тестов; без железа и без второго аккаунта Steam
python tools/package.py         # -> target/e4steam-<ver>.hnmod и его sha256
bash tools/validate.sh          # настоящие horizon-mixins plan + apply на закреплённом исходнике
```

`validate.sh` берёт Horizon и horizon-mixins ровно по закреплённым коммитам в кэш-папку
(`~/.cache/e4steam-hz`, переопределяется `HZ_WORK`; путь к бандлу — `E4STEAM_MODS`), запускает
`plan`, затем `apply` и печатает форму плана — обязаны получиться **3 впрыска** (`frame`,
`command`, `commands`), **1 модуль** (`e4steam`) и **1 крейт** (`engine`) — после чего показывает
уже внедрённый код в `game.rs` и `game/native_hooks.rs`. Эти две подкоманды лишь преобразуют
исходники:
ничего не компилируется и не запускается, поэтому флаг доверия им не нужен. `horizon-mixins check`
уже компилирует впрыснутый мод — это решение игрока.

`check/` — то, благодаря чему payload проверяется по типам за секунды, а не за час: мок `crate::game`
с той же геометрией, что в Horizon (приватный `Game`, `lan: Option<LanHost>`, хелперы `pub(super)`,
`include!` файлов самого бандла), так что опечатка в `runtime.rs` или в хелпере видна в `cargo test`.

`tools/package.py` дополнительно локально проверяет правила реестра, чтобы отказ случился у тебя, а
не на ревью: в архив попадают только `.rs`, `.toml` и текстовые файлы крейта; зависимости обязаны уже
лежать в `Cargo.lock` Horizon; запрещены `[workspace]`, `[patch]`, `[replace]`, `[profile]`, path- и
git-зависимости; id обязаны быть строчными `[a-z0-9_]`; пин в манифесте и в `compatibility.json`
обязаны совпадать; максимум 512 файлов и 64 МиБ в распакованном виде.

## Перевод пина на новую версию Horizon

1. Поставь новый коммит в `[compatibility] horizon_revision` (`mixins/e4steam/horizon-mixin.toml`) и в
   `compatibility.json`, обнови `source_sha256` для `crates/horizon-client/src/game.rs`.
2. Проверь, что `Game.lan` и подписи трёх хуков на месте: `command`/`commands` появились в API 35,
   `frame` — версия 1.
3. `bash tools/validate.sh`, затем `cargo test --workspace`.
4. Подними `version` в `horizon-mixin.toml`, выполни `python tools/package.py` и выложи новый `.hnmod`
   ассетом релиза GitHub. Релиз с ассетом `.hnmod` реестр считает версией.

## Кросс-плей

Совпадает с e4steam 0.3.2 / 0.3.4: версия протокола `4`, длины кадров и флаги, конверт zlib внутри
`DATA`, метаданные лобби (`e4steam_minecraft`, сравнение посимвольное), канал 480, 16-байтный токен
инвайта с проверкой постоянным временем, 7 гостей, дедлайн рукопожатия 30 с, а также тайминги
ретраев и backpressure. Побайтовой контракт — в [`../re/PROTOCOL.md`](../re/PROTOCOL.md). Гость с
другой строкой версии отклоняется до всякого соединения.

## Что где лежит

```
mixins/e4steam/
  horizon-mixin.toml     [mod], [compatibility], [[crate]], [[module]], 3 [[injection]]
  runtime.rs             payload, который вставляется как crate::game::e4steam
  command.rs commands.rs frame.rs   по одному приватному fn-хелперу на впрыск
  engine/                e4steam-engine: кодек E4ST, Flat API Steamworks, мост, грамматика /steam
compatibility.json       пины и sha256 game.rs
tools/                   package.py (воспроизводимый архив), validate.sh (plan + apply)
check/                   фикстура геометрии crate::game для cargo test
```

## Безопасность

Код инвайта `s-….steam` — это **доступ в твой мир**: не публикуй его в логах и скриншотах. Мод сам
маскирует чужие SteamID, адреса подключения и именованные секреты (`token`, `ticket`, `password`,
`secret`, `cookie`, `authorization`, `gslt`) до того, как строка попадёт в чат.

## Лицензия

Apache-2.0; см. [`../NOTICE.md`](../NOTICE.md) и
[`../THIRD_PARTY_NOTICES-e4steam.md`](../THIRD_PARTY_NOTICES-e4steam.md). Это порт Java-мода
[e4steam](https://github.com/Kamilhik/e4steam) автора Kamilchik (Apache-2.0), который сам происходит
от e4mc (MIT).
