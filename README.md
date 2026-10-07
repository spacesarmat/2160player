# 2160 Player

Медиаплеер для Android-телефонов и Android TV на Media3 (ExoPlayer) + FFmpeg с интерфейсом на
Jetpack Compose. Играет почти всё — от IPTV-потоков до образов Blu-ray с домашнего NAS по SMB.
Ядро плеера (`player-core`) — отдельная библиотека, которую можно встроить в своё приложение
или вызывать из любого приложения через Intent (совместимо с MX Player и VLC).

*English summary — [below](#english).*

## Возможности

- **Форматы:** MKV, MP4, WebM, TS/M2TS, AVI, FLV, OGG, MP3, FLAC и др.; FFmpeg-декодеры для DTS/DTS-HD, TrueHD, AC-3/E-AC-3, MPEG-2, VC-1 там, где устройство их не умеет; passthrough на ресивер.
- **Сеть:** HLS, DASH, SmoothStreaming, RTSP, HTTP(S) с заголовками; SMB 2/3 с сохранёнными серверами и обзором папок; **DLNA/UPnP**-серверы (Jellyfin, Plex, Kodi, NAS) с автопоиском в сети.
- **IPTV:** M3U-плейлисты с группами и логотипами, телегид XMLTV, режим эфира (без перемотки, текущая передача под названием канала).
- **Торренты:** magnet-ссылки и `.torrent`-файлы, просмотр во время загрузки (libtorrent), выбор файла из раздачи, остановка и продолжение раздач.
- **Blu-ray:** образы `.iso` и папки `BDMV` (файлы, SMB, HTTP) — автоматический выбор основного фильма, главы, языки дорожек; M2TS с TrueHD, LPCM, DTS-HD MA, PGS.
- **Субтитры:** внешние SRT/ASS/VTT/TTML с автоопределением кодировки, автопоиск рядом с видео, задержка, стиль, **двойные субтитры**.
- **Главы и пропуск** вступлений, пересказов и титров: из глав, из Intent, ручные отметки на весь сериал; кнопка или автопропуск, карточка «Следующая серия».
- **Продолжение просмотра** с сохранением дорожек, скорости и задержки субтитров; возврат позиции вызывающему приложению.
- **Телефон и ТВ:** жесты, D-pad и пульт, цифровой ввод времени, превью кадров при перемотке, PiP.
- **Встроенный обзор файлов** для Android TV и приставок без системного выбора файлов: внутренняя память, флешки USB и карты памяти, папки Blu-ray и образы ISO, плейлист из папки для «Следующей серии».
- **Масштаб картинки:** целиком, по ширине, по высоте, заполнить экран с обрезкой, растянуть; на телефоне — щипком двумя пальцами.
- **Обложки в «Продолжить просмотр» и истории:** встроенные в файл, от DLNA-сервера или рядом с файлом (`poster.jpg`, `folder.jpg`, `<имя>-thumb.jpg`, постер сериала из папки выше сезона).
- **Таймер сна** (через 15–120 минут или в конце серии, с плавным затиханием) и **задержка звука** для отстающих Bluetooth-наушников и саундбаров.
- **Поделиться приложением без интернета:** файлом (Quick Share, Bluetooth, Telegram) или по Wi-Fi с QR-кодом — второе устройство скачает приложение прямо с первого.
- **Умный выбор дорожек:** плеер запоминает ручной выбор озвучки и субтитров — отдельно для сериала и для набора языков (аниме — японский с субтитрами, фильмы — дубляж).
- **Ночной звук:** сжатие динамики и громкие диалоги, по расписанию (по умолчанию 23:00–10:00), настраивается прямо в плеере.
- **Определение вступления** по звуку между сериями, **информация о файле** (кодеки, HDR, битрейт), превью при перемотке с пульта.
- **Продолжить на другом устройстве:** передача просмотра между телефоном и ТВ в одной сети Wi-Fi с той же позиции; на исходном устройстве — пауза. Если в сети несколько плееров — защита: новое устройство подключается один раз — кнопкой «Разрешить» в уведомлении или по коду (на сутки или свой).
- **Фоновое воспроизведение:** уведомление с управлением, экран блокировки, гарнитура и Bluetooth; что делать при сворачивании — настраивается: картинка в картинке, звук видео в фоне, музыка в фоне или пауза.
- **Dolby Vision** на устройствах без декодера (профиль 7 из UHD-ремуксов) играет базовым слоем HDR10, а не теряет картинку.
- **Автообновление** из GitHub Releases: проверка при запуске (можно выключить в настройках), загрузка APK под архитектуру устройства, установка в пару нажатий.
- **6 тем** оформления, **локализация** JSON-пакетами (ru, en) с импортом пользовательских переводов.

## Установка

Скачайте APK из [последнего релиза](https://github.com/spacesarmat/2160player/releases/latest):
`arm` — подходит любому телефону и ТВ (рекомендуется; этот же файл потом можно передать другим через
«Поделиться приложением»), `arm64-v8a` / `armeabi-v7a` — поменьше, только под одну архитектуру,
`universal` — если не уверены. Дальше приложение обновляется само (Настройки → Обновления).

## Скриншоты

<!-- TODO: добавить скриншоты в docs/images/ -->

| Телефон | Android TV |
|---|---|
| _скоро_ | _скоро_ |

## Документация

- [docs/EMBEDDING.md](docs/EMBEDDING.md) — встраивание за 5 минут: подключение (модуль, `includeBuild`, mavenLocal, GitHub Packages), запуск, свой экран.
- [docs/API.md](docs/API.md) — полный справочник: Intent API, `Player2160`, `PlayerController`, `PlayerScreen`, настройки движка (`PlayerConfig`: буфер, тайм-ауты, фоновая работа), фоновое воспроизведение, история, SMB, DLNA, IPTV, торренты, отрезки, локализация, Blu-ray, ограничения.
- [samples/embed-demo](samples/embed-demo) — пример приложения-хоста.

Короткий пример:

```kotlin
// Библиотека в вашем APK
Player2160.play(context, PlaybackRequest.single(Uri.parse("smb://nas/Movies/Film.iso"), "Фильм"))

// Без библиотеки — Intent в установленный 2160 Player
startActivity(
    Intent(Intent.ACTION_VIEW)
        .setDataAndType(Uri.parse("https://example.com/movie.mkv"), "video/*")
        .setPackage("tv.p2160.player")
        .putExtra("title", "Фильм")
)
```

## Сборка

Требуется JDK 21 (для Gradle) и Android SDK с платформой 37.

```sh
# если нет local.properties:
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

./gradlew :app:assembleDebug                     # приложение (APK по ABI + universal)
./gradlew :player-core:testDebugUnitTest         # тесты библиотеки
./gradlew :samples:embed-demo:assembleDebug      # пример встраивания
./gradlew :player-core:publishReleasePublicationToMavenLocal   # AAR в ~/.m2
```

APK: `app/build/outputs/apk/debug/`. applicationId: `tv.p2160.player` (release), `tv.p2160.player.debug` (debug).
Отладочная сборка ставится отдельным приложением «2160 Тест» с красной полосой «ТЕСТ» на иконке и
баннере ТВ (ресурсы `app/src/debug/res`, генератор — `design/debug-badge/make_debug_icons.py`).

### Релиз

Релизы подписываются одним ключом — иначе автообновление не сможет поставить новую версию поверх старой.
Ключ и пароли лежат в `keystore.properties` в корне проекта (не в git):

```properties
storeFile=/path/to/2160player-release.jks
storePassword=...
keyAlias=2160player
keyPassword=...
```

или в переменных окружения `P2160_KEYSTORE`, `P2160_KEYSTORE_PASSWORD`, `P2160_KEY_ALIAS`, `P2160_KEY_PASSWORD`.

```sh
# APK под каждую архитектуру + universal
./gradlew :app:assembleRelease -Pp2160.versionName=0.2.0 -Pp2160.versionCode=3
# общий ARM-APK (arm64-v8a + armeabi-v7a): ставится на любой телефон и ТВ — основной для скачивания
./gradlew :app:assembleRelease -Pp2160.versionName=0.2.0 -Pp2160.versionCode=3 -Pp2160.abi=arm
gh release create v0.2.0 2160player-0.2.0-*.apk --title "2160 Player 0.2.0" --notes "..."
```

Тег релиза — `v<versionName>`; приложение сравнивает его со своей версией и берёт общий ARM-APK
(`-arm-` в имени) на ARM-устройствах, иначе APK с ABI устройства (`-arm64-v8a-`, `-armeabi-v7a-`…), иначе `universal`. `versionCode`
каждого релиза должен быть больше предыдущего.

## Структура проекта

```
player-core/            библиотека плеера (tv.p2160.core)
  api/                  публичный фасад: Player2160, PlaybackRequest, IntentApi, NowPlaying, PlayerAction, SkipSegment
  engine/               PlayerController, сборка ExoPlayer, дорожки, двойные субтитры, отрезки
  ui/                   Compose: PlayerScreen, панели, жесты, темы
  bluray/, m2ts/        чтение ISO/UDF и BDMV, MPLS/CLPI, экстрактор M2TS
  source/               SMB (smbj), DLNA, маршрутизация источников, произвольный доступ
  iptv/                 M3U, XMLTV
  settings/, resume/, i18n/, subtitle/
  src/main/assets/i18n/core/   языковые пакеты ядра
source-torrent/         торренты (libtorrent4j), схема torrent://
app/                    приложение 2160 Player: главный экран, история, сеть, IPTV, торренты,
                        передача между устройствами (handoff/), автообновление (update/), настройки
samples/embed-demo/     пример встраивания библиотеки
design/                 исходники логотипа и баннера
docs/                   документация
.github/                CI, шаблоны issue и PR
```

## Участие в разработке

Issue и pull request'ы приветствуются. Пожалуйста, используйте шаблоны:
[bug report](.github/ISSUE_TEMPLATE/bug_report.yml), [feature request](.github/ISSUE_TEMPLATE/feature_request.yml),
[pull request](.github/PULL_REQUEST_TEMPLATE.md). Перед PR убедитесь, что проходит
`./gradlew assembleDebug testDebugUnitTest lintDebug`, а изменения публичного API отражены в
[docs/API.md](docs/API.md). Новые строки интерфейса добавляйте во все языковые пакеты
(`assets/i18n/*/en.json`, `ru.json`) — это проверяет `TranslationsTest`.

## Лицензия

[GNU GPL v3.0](LICENSE). Приложение и библиотеку `player-core` можно свободно использовать, изменять
и распространять, в том числе встраивать в свои приложения, — при условии, что производная работа
тоже распространяется под GPL v3 с открытым исходным кодом. Используемые компоненты: Media3
(Apache 2.0), FFmpeg через nextlib (GPL), smbj (Apache 2.0), libtorrent4j (MIT/BSD).

---

## English

**2160 Player** is an Android (phone + TV) media player built on Media3/ExoPlayer with FFmpeg
decoders and a Jetpack Compose UI. It plays local files (with a built-in file browser for Android TV boxes, USB drives and SD cards), HTTP/HLS/DASH/RTSP streams, SMB shares, DLNA servers, IPTV M3U playlists (XMLTV guide), torrents (stream while downloading)
and Blu-ray ISO images/BDMV folders (M2TS with TrueHD, LPCM, DTS-HD, PGS), supports chapters,
intro/credits skipping (incl. audio-based intro detection), dual subtitles, resume with track memory,
learned audio/subtitle preferences, scheduled night sound, hand-off between devices on the LAN,
themes, JSON language packs and self-updates from GitHub Releases.

Install: grab an APK from the [latest release](https://github.com/spacesarmat/2160player/releases/latest)
(`arm64-v8a` for most devices, `armeabi-v7a` for older 32-bit TVs, `universal` if unsure).

The `player-core` module is an embeddable library:

- **Intent API** — call the standalone app (`tv.p2160.player`, debug `tv.p2160.player.debug`,
  activity `tv.p2160.core.Player2160Activity`) with MX Player–compatible extras (`title`, `position`,
  `headers`, `subs`, `video_list`, `return_result`…) plus `tv.p2160.extra.*` (segments, MIME types);
  results are returned in MX Player and VLC formats.
- **Library** — `implementation("tv.p2160:player-core:0.2.0")` (module, composite build, mavenLocal
  or GitHub Packages), `minSdk 24`, `compileSdk 37`, and the required
  `packaging { jniLibs.pickFirsts += listOf("**/libavcodec.so", "**/libavutil.so", "**/libswscale.so", "**/libswresample.so") }`.
  Then `Player2160.play(context, PlaybackRequest.single(uri))`, `Player2160.PlayContract()` for
  results, or embed the `PlayerScreen` composable with your own `PlayerController`.
- **Engine tuning** — `Player2160.config = PlayerConfig(...)`: buffer limit (64 MB by default), network
  timeouts, switches for audio intro detection, chapter reading and history restore/save.
- **Extensibility** — observe `Player2160.nowPlaying`, add toolbar buttons with
  `Player2160.registerAction(PlayerAction(...))`, change `PlayerSettings`, read `ResumeStore`.

Docs are in Russian: [docs/EMBEDDING.md](docs/EMBEDDING.md) (quick start),
[docs/API.md](docs/API.md) (reference). Code samples are self-explanatory; see
[samples/embed-demo](samples/embed-demo). Build with `./gradlew :app:assembleDebug` (JDK 21).
License: [GPL-3.0](LICENSE) — embedding apps must be GPL-3.0 as well.
