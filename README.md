# 2160 Player

Медиаплеер для Android-телефонов и Android TV на Media3 (ExoPlayer) + FFmpeg с интерфейсом на
Jetpack Compose. Играет почти всё - от IPTV-потоков до образов Blu-ray с домашнего NAS по SMB.
Ядро плеера (`player-core`) - отдельная библиотека, которую можно встроить в своё приложение
или вызывать из любого приложения через Intent (совместимо с MX Player и VLC).

*English summary - [below](#english).*

## Возможности

- **Форматы:** MKV, MP4, WebM, TS/M2TS, AVI, FLV, OGG, MP3, FLAC и др.; FFmpeg-декодеры для DTS/DTS-HD, TrueHD, AC-3/E-AC-3, MP3 и MPEG-1/2 там, где устройство их не умеет. MPEG-4 Part 2 (XviD/DivX) играет программный MediaCodec устройства, если поток ему подходит. MS-MPEG4 (`DIV3`/`DIV4`) открывается как `video/divx3` и пробует декодеры `divx3` и `video/mp43`. Длительность AVI берётся из video `strh`, а не из завышенного `avih`. Вшитый FFmpeg не содержит декодеры MPEG-4, MS-MPEG4, H.263, WMV и VC-1: тогда ошибка с именем кодека, а не звук без картинки. В AVI время MP3 берётся из кадров, а не из куска контейнера. Passthrough на ресивер.
- **Сеть:** HLS, DASH, SmoothStreaming, RTSP, HTTP(S) с заголовками; SMB 2/3 с сохранёнными серверами и обзором папок; **DLNA/UPnP**-серверы (Jellyfin, Plex, Kodi, NAS) с автопоиском в сети.
- **IPTV:** M3U-плейлисты с группами и логотипами, телегид XMLTV, режим эфира (без перемотки, текущая передача под названием канала).
- **Торренты:** magnet-ссылки и `.torrent`-файлы, просмотр во время загрузки (libtorrent), выбор файла из раздачи, остановка и продолжение раздач (свайпом влево/вправо или кнопкой); состояние раздачи видно по цвету и метке («Качается», «Раздаётся», «Остановлена»…), со скоростью, пирами и прогрессом. Настройки раздач: сеть («Везде», «Раздача по Wi-Fi» - по умолчанию,
  «Только Wi-Fi» с вопросом перед просмотром по мобильному интернету), сколько раздавать скачанное (всегда / до 1:1 /
  24 часа / не раздавать), раздача только на зарядке и ограничение загрузки по мобильному интернету.
- **Blu-ray:** образы `.iso` и папки `BDMV` (файлы, SMB, HTTP) - автоматический выбор основного фильма, главы, языки дорожек; M2TS с TrueHD, LPCM, DTS-HD MA, PGS.
- **Субтитры:** внешние SRT/ASS/VTT/TTML с автоопределением кодировки, автопоиск рядом с видео, задержка, стиль, **двойные субтитры**.
- **Главы и пропуск** вступлений, пересказов и титров: из глав, из Intent, ручные отметки на весь сериал; кнопка или автопропуск, карточка «Следующая серия».
- **Продолжение просмотра** с сохранением дорожек, скорости и задержки субтитров; возврат позиции вызывающему приложению. Файл, досмотренный до конца (или до последних 2 % / 30 с), в следующий раз открывается с начала.
- **Конец файла:** по умолчанию плейлист через 5 с переходит к следующему (Назад или «Отмена» останавливают отсчёт), один файл и последний в плейлисте показывают «Смотреть снова» и «Закрыть». Без нажатий плеер закрывается примерно через 12 с. В настройках «По окончании»: следующий, закрыть, остаться на кадре или повтор. «Остаться на кадре» через 3 минуты гасит картинку. Прямой эфир это не трогает.
- **Телефон и ТВ:** жесты, D-pad и пульт, цифровой ввод времени, превью кадров при перемотке, PiP.
- **Встроенный обзор файлов** для Android TV и приставок без системного выбора файлов: внутренняя память, флешки USB и карты памяти, папки Blu-ray и образы ISO, плейлист из папки для «Следующей серии».
- **Масштаб картинки:** целиком, по ширине, по высоте, заполнить экран с обрезкой, растянуть; на телефоне - щипком двумя пальцами.
- **Камеры в сети и приём потоков:** телефоны с 2160 Player, которые транслируют камеру, видны на главном экране других устройств (подключение - по разрешению или коду); открываются и `rtsp://`, HLS и `udp://` (MPEG-TS) с низкой задержкой.
- **Трансляция камеры и экрана:** телефон отдаёт видео с выбранной камеры (задняя, широкоугольная, телевик, фронтальная) или свой экран - со звуком микрофона и/или телефона со звуком: RTSP-сервер на телефоне (OBS, VLC, другой 2160 Player), SRT прямо в OBS, NDI® (OBS с DistroAV, vMix) или RTMP на YouTube/Twitch; режимы (до 4K и 60 fps) - по возможностям камеры и кодировщика телефона, вертикальное или горизонтальное видео - как держите телефон, фонарик, фокус касанием и зум, задержка ~0,5 с (NDI - ~0,1 с; переключатель «Минимальная задержка» укорачивает очереди), работает со свёрнутым приложением; только по Wi-Fi/LAN/точке доступа - мобильный интернет не используется. Пароль RTSP - отдельный на каждую трансляцию, не код сопряжения; сопряжённый 2160 Player берёт его сам, VLC и OBS - с экрана.
- **Экономный движок:** аппаратные декодеры в приоритете, буфер по памяти устройства, звук без видео - через аудиочип (offload), частота экрана под видео на ТВ (23,976/24/25/50 Гц), туннельный режим для Android TV и «Статистика поверх видео» (декодеры, пропущенные кадры, буфер, сеть, процессор, память).
- **«Продолжить просмотр» целиком:** нажмите на заголовок раздела на главном экране - откроется список всего недосмотренного.
- **Обложки в «Продолжить просмотр» и истории:** встроенные в файл, от DLNA-сервера или рядом с файлом (`poster.jpg`, `folder.jpg`, `<имя>-thumb.jpg`, постер сериала из папки выше сезона).
- **Таймер сна** (через 15–120 минут или в конце серии, с плавным затиханием) и **задержка звука** для отстающих Bluetooth-наушников и саундбаров.
- **Экран не гаснет во время просмотра** (включено по умолчанию, Настройки → Воспроизведение): пока идёт видео или прямой эфир, в том числе при подгрузке. На паузе, в конце и после выхода экран снова может погаснуть. Музыка без картинки экран не держит.
- **Справка и FAQ** прямо в приложении (Настройки → О приложении): ответы на частые вопросы и ссылка, куда сообщить об ошибке.
- **Поделиться приложением без интернета:** файлом (Quick Share, Bluetooth, Telegram) или по Wi-Fi с QR-кодом - второе устройство скачает приложение прямо с первого.
- **Умный выбор дорожек:** плеер запоминает ручной выбор озвучки и субтитров - отдельно для сериала и для набора языков (аниме - японский с субтитрами, фильмы - дубляж).
- **Ночной звук:** сжатие динамики и громкие диалоги, по расписанию (по умолчанию 23:00–10:00), настраивается прямо в плеере. В панели «Аудио» это переключатели, не галочки. Кнопка-рамка сверху открывает масштаб и качество и сама картинку не меняет.
- **Определение вступления** по звуку между сериями, **информация о файле** (кодеки, HDR, битрейт), превью при перемотке с пульта.
- **Продолжить на другом устройстве:** передача просмотра между телефоном и ТВ в одной сети Wi-Fi с той же позиции; на исходном устройстве - пауза. Если в сети несколько плееров - защита: новое устройство подключается один раз - кнопкой «Разрешить» в уведомлении или по коду (на сутки или свой, новый свой - от 6 цифр). На ТВ по умолчанию выбрано «Отклонить», код показан для сверки.
- **Фоновое воспроизведение:** уведомление с управлением, экран блокировки, гарнитура и Bluetooth; что делать при сворачивании - настраивается: картинка в картинке, звук видео в фоне, музыка в фоне или пауза.
- **Dolby Vision** на устройствах без декодера (профиль 7 из UHD-ремуксов) играет базовым слоем HDR10, а не теряет картинку.
- **Автообновление** из GitHub Releases: проверка при запуске (можно выключить в настройках), загрузка APK под архитектуру устройства, установка в пару нажатий. Если GitHub ограничивает запросы к API, проверка читает тег со страницы релиза и пишет, через сколько минут повторить.
- **6 тем** оформления, **локализация** JSON-пакетами (ru, en) с импортом пользовательских переводов.

## Установка

Скачайте APK из [последнего релиза](https://github.com/spacesarmat/2160player/releases/latest):
`arm` - подходит любому телефону и ТВ (рекомендуется; этот же файл потом можно передать другим через
«Поделиться приложением»), `arm64-v8a` / `armeabi-v7a` - поменьше, только под одну архитектуру,
`universal` - если не уверены. Дальше приложение обновляется само (Настройки → Обновления).

## Скриншоты

<!-- TODO: добавить скриншоты в docs/images/ -->

| Телефон | Android TV |
|---|---|
| _скоро_ | _скоро_ |

## Документация

- [docs/EMBEDDING.md](docs/EMBEDDING.md) - встраивание за 5 минут: подключение (модуль, `includeBuild`, mavenLocal, GitHub Packages), запуск, свой экран.
- [docs/API.md](docs/API.md) - полный справочник: Intent API, `Player2160`, `PlayerController`, `PlayerScreen`, настройки движка (`PlayerConfig`: буфер, тайм-ауты, фоновая работа), фоновое воспроизведение, история, SMB, DLNA, IPTV, торренты, отрезки, локализация, Blu-ray, ограничения.
- [samples/embed-demo](samples/embed-demo) - пример приложения-хоста.

Короткий пример:

```kotlin
// Библиотека в вашем APK
Player2160.play(context, PlaybackRequest.single(Uri.parse("smb://nas/Movies/Film.iso"), "Фильм"))

// Без библиотеки - Intent в установленный 2160 Player
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
баннере ТВ (ресурсы `app/src/debug/res`, генератор - `design/debug-badge/make_debug_icons.py`).

Установка на подключённый телефон или ТВ: `adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`
(или `app-universal-debug.apk`). Запуск плеера из другого приложения - Intent `tv.p2160.action.PLAY`
или `ACTION_VIEW` (см. [docs/API.md](docs/API.md) §3). Конфигурация движка для встраивания -
`Player2160.config = PlayerConfig(...)`; пользовательские настройки - экран «Настройки» и `PlayerSettings`.

Страницы в формате GitHub Wiki (по одной на тему, оглавление - `Home.md`): [docs/wiki/Home.md](docs/wiki/Home.md).

### Релиз

Релизы подписываются одним ключом - иначе автообновление не сможет поставить новую версию поверх старой.
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
# общий ARM-APK (arm64-v8a + armeabi-v7a): ставится на любой телефон и ТВ - основной для скачивания
./gradlew :app:assembleRelease -Pp2160.versionName=0.2.0 -Pp2160.versionCode=3 -Pp2160.abi=arm
gh release create v0.2.0 2160player-0.2.0-*.apk --title "2160 Player 0.2.0" --notes "..."
```

Тег релиза - `v<versionName>`; приложение сравнивает его со своей версией и берёт общий ARM-APK
(`-arm-` в имени) на ARM-устройствах, иначе APK с ABI устройства (`-arm64-v8a-`, `-armeabi-v7a-`…), иначе `universal`. `versionCode`
каждого релиза должен быть больше предыдущего.

## Настройки

Экран «Настройки» приложения (язык интерфейса - русский и английский, пакеты `assets/i18n`).
Значения лежат в `SharedPreferences` `p2160_settings` и доступны встраивающим приложениям как
`PlayerSettings` / `Settings` (полный список полей - [docs/API.md](docs/API.md) §9 и
[docs/wiki/Settings.md](docs/wiki/Settings.md)).

| Раздел | Что там |
|---|---|
| Интерфейс | Язык, тема |
| Субтитры | Размер, цвет, фон, обводка, языки |
| Воспроизведение | **Не выключать экран во время просмотра** (по умолчанию включено), продолжение с места, ночной звук и расписание, пропуск вступлений, скорость, шаг перемотки, декодер, частота экрана, туннель, offload, статистика |
| При сворачивании | Картинка в картинке, видео в фоне, музыка в фоне |
| Передача между устройствами | Защита кодом (свой - от 6 цифр; предупреждение при выключении) |
| Обновления | Автопроверка GitHub Releases |
| О приложении | Справка, NDI, донат, автор, «Поделиться» |

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
docs/                   документация (API.md, EMBEDDING.md, wiki/)
.github/                CI, шаблоны issue и PR
```

## Участие в разработке

Issue и pull request'ы приветствуются. Пожалуйста, используйте шаблоны:
[bug report](.github/ISSUE_TEMPLATE/bug_report.yml), [feature request](.github/ISSUE_TEMPLATE/feature_request.yml),
[pull request](.github/PULL_REQUEST_TEMPLATE.md). Перед PR убедитесь, что проходит
`./gradlew assembleDebug testDebugUnitTest lintDebug`, а изменения публичного API отражены в
[docs/API.md](docs/API.md). Новые строки интерфейса добавляйте во все языковые пакеты
(`assets/i18n/*/en.json`, `ru.json`) - это проверяет `TranslationsTest`.

## Автор

Andy_bum - Telegram: [@Andy_bum](https://t.me/Andy_bum). Вопросы, идеи и сообщения об ошибках - в Telegram или в
[Issues](https://github.com/spacesarmat/2160player/issues).

## Поддержать проект

Если плеер пригодился - можно поддержать разработку на [Boosty](https://boosty.to/djmaker/donate).

## Лицензия

[GNU GPL v3.0](LICENSE). Приложение и библиотеку `player-core` можно свободно использовать, изменять
и распространять, в том числе встраивать в свои приложения, - при условии, что производная работа
тоже распространяется под GPL v3 с открытым исходным кодом. Используемые компоненты: Media3
(Apache 2.0), FFmpeg через nextlib (LGPL 3), smbj (Apache 2.0), libtorrent4j (MIT/BSD), RootEncoder и RTSP-Server
(Apache 2.0; RTSP-Server - исходниками с правкой, см. `third-party/rtsp-server/NOTICE.md`), ZXing (Apache 2.0),
NDI® SDK (заголовки - MIT; библиотека `libndi` - лицензия NDI SDK, в репозиторий не входит; распространение вместе
с ней разрешено дополнительным разрешением [LICENSE-NDI-EXCEPTION.md](LICENSE-NDI-EXCEPTION.md)).
NDI® is a registered trademark of Vizrt NDI AB - https://ndi.video/

---

## English

**2160 Player** is an Android (phone + TV) media player built on Media3/ExoPlayer with FFmpeg
decoders and a Jetpack Compose UI. It plays local files (with a built-in file browser for Android TV boxes, USB drives and SD cards), HTTP/HLS/DASH/RTSP streams, SMB shares, DLNA servers, IPTV M3U playlists (XMLTV guide), torrents (stream while downloading)
and Blu-ray ISO images/BDMV folders (M2TS with TrueHD, LPCM, DTS-HD, PGS), supports chapters,
intro/credits skipping (incl. audio-based intro detection), dual subtitles, resume with track memory
(a finished file opens from the start; when playback ends, a playlist counts down 5 s to the next item,
and a single file offers Watch again and Close),
learned audio/subtitle preferences, scheduled night sound, hand-off between devices on the LAN,
themes, JSON language packs and self-updates from GitHub Releases (if the API rate-limits the check, the app reads the latest tag from the releases page and says when to retry). It can also stream the phone camera or screen
(RTSP server, SRT to OBS, NDI®, RTMP to YouTube/Twitch; LAN/Wi-Fi only, never mobile data; optional Lowest latency shortens send queues) with a per-session RTSP password that is not the pairing PIN, shows cameras of other 2160 Players on the
LAN, plays live RTSP/UDP with low latency, and has torrent seeding rules, a memory-sized buffer, audio offload,
display frame-rate matching and a stats overlay.

Install: grab an APK from the [latest release](https://github.com/spacesarmat/2160player/releases/latest)
(`arm` works on any phone or TV and is recommended; `arm64-v8a` / `armeabi-v7a` are smaller single-architecture builds; `universal` also covers x86).

The `player-core` module is an embeddable library:

- **Intent API** - call the standalone app (`tv.p2160.player`, debug `tv.p2160.player.debug`,
  activity `tv.p2160.core.Player2160Activity`) with MX Player–compatible extras (`title`, `position`,
  `headers`, `subs`, `video_list`, `return_result`…) plus `tv.p2160.extra.*` (segments, MIME types);
  results are returned in MX Player and VLC formats.
- **Library** - `implementation("tv.p2160:player-core:0.2.16")` (module, composite build, mavenLocal
  or GitHub Packages), `minSdk 24`, `compileSdk 37`, and the required
  `packaging { jniLibs.pickFirsts += listOf("**/libavcodec.so", "**/libavutil.so", "**/libswscale.so", "**/libswresample.so") }`.
  Then `Player2160.play(context, PlaybackRequest.single(uri))`, `Player2160.PlayContract()` for
  results, or embed the `PlayerScreen` composable with your own `PlayerController`.
- **Engine tuning** - `Player2160.config = PlayerConfig(...)`: buffer limit (`PlayerConfig.AUTO` by default - sized from device memory, 24–128 MB), network
  timeouts, switches for audio intro detection, chapter reading and history restore/save.
- **Extensibility** - observe `Player2160.nowPlaying`, add toolbar buttons with
  `Player2160.registerAction(PlayerAction(...))`, change `PlayerSettings`, read `ResumeStore`.

The screen stays on while a video or live stream is playing (Settings → Playback, on by default)
and can sleep again on pause, at the end, or when you leave. Docs are in Russian:
[docs/EMBEDDING.md](docs/EMBEDDING.md) (quick start), [docs/API.md](docs/API.md) (reference),
[docs/wiki/Home.md](docs/wiki/Home.md) (wiki-ready pages). Code samples are self-explanatory; see
[samples/embed-demo](samples/embed-demo). Build with `./gradlew :app:assembleDebug` (JDK 21, Android SDK platform 37).
License: [GPL-3.0](LICENSE) - embedding apps must be GPL-3.0 as well.
Author: Andy_bum - Telegram [@Andy_bum](https://t.me/Andy_bum). Support the project: [Boosty](https://boosty.to/djmaker/donate).
