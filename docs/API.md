# 2160 Player — справочник API

Документ описывает публичный API модуля `player-core` (пакет `tv.p2160.core`) и Intent API отдельного
приложения 2160 Player. Все сигнатуры сверены с исходниками; если что-то в коде объявлено `public`,
но здесь помечено как «внутреннее», — не опирайтесь на это: такие классы могут измениться без
предупреждения.

Быстрый старт за 5 минут — в [EMBEDDING.md](EMBEDDING.md). Рабочий пример — модуль
[`samples/embed-demo`](../samples/embed-demo).

## Содержание

1. [Обзор и возможности](#1-обзор-и-возможности)
2. [Способы интеграции](#2-способы-интеграции)
3. [Intent API (без библиотеки)](#3-intent-api-без-библиотеки)
4. [Подключение библиотеки](#4-подключение-библиотеки)
5. [Запуск плеера из библиотеки](#5-запуск-плеера-из-библиотеки)
6. [Встраивание `PlayerScreen` в свой экран](#6-встраивание-playerscreen-в-свой-экран)
7. [Наблюдение за воспроизведением](#7-наблюдение-за-воспроизведением)
8. [Свои кнопки в плеере: `PlayerAction`](#8-свои-кнопки-в-плеере-playeraction)
9. [Настройки и темы](#9-настройки-и-темы)
10. [История и позиции остановки](#10-история-и-позиции-остановки)
11. [SMB](#11-smb)
12. [Главы и пропускаемые отрезки](#12-главы-и-пропускаемые-отрезки)
13. [Локализация](#13-локализация)
14. [Blu-ray: ISO и BDMV](#14-blu-ray-iso-и-bdmv)
15. [Потоки, жизненный цикл, ошибки, ограничения](#15-потоки-жизненный-цикл-ошибки-ограничения) (15.7 — производительность)
16. [Ночной звук](#16-ночной-звук)
17. [Умный выбор дорожек](#17-умный-выбор-дорожек)
18. [Поиск вступлений по звуку и сводка по файлу](#18-поиск-вступлений-по-звуку-и-сводка-по-файлу)
19. [DLNA/UPnP](#19-dlnaupnp)
20. [IPTV: M3U, XMLTV, режим эфира](#20-iptv-m3u-xmltv-режим-эфира)
21. [Торренты: модуль `source-torrent`](#21-торренты-модуль-source-torrent)
22. [Функции приложения 2160 Player (не библиотеки)](#22-функции-приложения-2160-player-не-библиотеки)
23. [Справочник классов](#23-справочник-классов)

---

## 1. Обзор и возможности

`player-core` — Android-библиотека (minSdk 24, compileSdk 37) с готовым полноэкранным плеером на
Jetpack Compose и Media3/ExoPlayer. Её можно использовать тремя способами: запустить готовую
`Player2160Activity`, встроить Compose-экран `PlayerScreen` в свою Activity или вызвать отдельное
приложение 2160 Player через Intent, вообще не подключая библиотеку.

| Возможность | Подробности |
|---|---|
| Форматы | Всё, что умеет Media3 (MP4, MKV, WebM, TS, FLV, OGG, MP3, FLAC…), плюс FFmpeg-декодеры (nextlib) для того, чего не умеет устройство: DTS/DTS-HD, TrueHD, AC-3/E-AC-3 без лицензии, FLAC/ALAC/Opus в MKV, MPEG-2/VC-1. Режим декодеров выбирается в настройках (`DecoderPreference`). |
| Сетевые потоки | HLS (`.m3u8`), DASH, SmoothStreaming, RTSP, прогрессивный HTTP(S) с заголовками (`User-Agent`, `Referer`, `Authorization`…). Разрешён cleartext HTTP. |
| Blu-ray | Образы `.iso` и папки `BDMV` с файлов, `content://`, SMB и HTTP(S) (для ISO). Основной фильм выбирается автоматически, главы берутся из плейлиста диска. |
| M2TS | Свой экстрактор 192-байтных пакетов: TrueHD (+ AC-3 ядро), LPCM, DTS-HD HRA/MA, E-AC-3, субтитры PGS, языки дорожек из MPLS. |
| SMB | SMB 2/3 (smbj), сохранённые серверы с логином, список общих папок и файлов, `smb://` URI в любом месте API. |
| DLNA/UPnP | Поиск медиасерверов по SSDP и по адресу, обзор ContentDirectory, воспроизведение по HTTP с внешними субтитрами (§19). |
| IPTV | Разбор M3U/M3U8 и телегида XMLTV, хранилище плейлистов с избранным, режим эфира с переключением каналов (§20). |
| Торренты | Отдельный модуль `source-torrent` (libtorrent4j): magnet/.torrent, просмотр во время загрузки по схеме `torrent://` (§21). |
| Звук | «Ночной звук» (компрессия динамики + выделение диалогов) вручную или по расписанию; автоматический откат с passthrough на декодирование (§16). |
| Дорожки | Обучаемый выбор озвучки и субтитров по сериалу и по набору языков файла (§17). |
| Субтитры | Внешние SRT/ASS/SSA/VTT/TTML (автоопределение кодировки), автопоиск файлов рядом с видео (file:// и smb://), задержка, размер/цвет/обводка, **двойные субтитры** (вторая дорожка сверху). |
| Главы и отрезки | Главы из контейнера (FFmpeg) и с Blu-ray; пропуск вступления/пересказа/титров/анонса: из Intent, из названий глав, из ручных отметок (на весь сериал), автоопределение по звуку соседних серий (§18). Режимы «кнопка» и «авто». |
| Продолжение просмотра | SQLite-история: позиция, аудио- и текстовая дорожка, скорость, задержка субтитров. Возврат позиции вызывающему приложению (MX Player/VLC-совместимо). |
| UI | Телефон и Android TV (D-pad, пульт, цифровой ввод времени), жесты, PiP, превью кадров при перемотке, 6 тем оформления. |
| Локализация | JSON-пакеты в assets, импорт/экспорт пользовательских переводов. Встроены `en`, `ru`. |
| Производительность | Буфер по памяти устройства, offload звука, частота экрана под видео, туннельный режим, статистика поверх видео ([§15.7](#157-производительность-буфер-частота-экрана-статистика)). |
| Живые потоки | `rtsp://`, `rtmp://`, `udp://` (MPEG-TS) с низкой задержкой; в приложении — трансляция камеры/экрана и «Камеры в сети» (§22.7). |

---

## 2. Способы интеграции

| Путь | Нужна библиотека? | Когда выбирать |
|---|---|---|
| **(a) Intent API** | Нет | Ваше приложение (медиацентр, IPTV, клиент медиасервера) просто открывает видео во внешнем плеере. Совместимо с MX Player/VLC — если вы уже их поддерживаете, достаточно добавить пакет `tv.p2160.player`. |
| **(b)+(c) Библиотека + готовая Activity** | Да | Плеер должен быть частью вашего APK; достаточно `Player2160.play(...)`. |
| **(d) Библиотека + свой экран** | Да | Нужно разместить плеер внутри своей Activity/навигации, управлять жизненным циклом и PiP самостоятельно. |

---

## 3. Intent API (без библиотеки)

Отдельное приложение 2160 Player принимает Intent'ы в формате MX Player (де-факто стандарт, его
используют Kodi, Stremio, Jellyfin, Lampa и др.), собственные расширения `tv.p2160.*` и некоторые
ключи VLC.

### 3.1. Пакеты и компоненты

| Сборка | applicationId | Activity плеера |
|---|---|---|
| release | `tv.p2160.player` | `tv.p2160.core.Player2160Activity` |
| debug | `tv.p2160.player.debug` | `tv.p2160.core.Player2160Activity` |

Имя класса Activity одинаково в обеих сборках (класс живёт в библиотеке), меняется только пакет.

### 3.2. Intent-фильтры приложения

| Action | Схемы `data` | MIME |
|---|---|---|
| `tv.p2160.action.PLAY` | `content`, `file`, `http`, `https`, `rtsp`, `rtmp` | любой или без MIME |
| `android.intent.action.VIEW` (+ `BROWSABLE`) | `content`, `file`, `http`, `https` | `video/*`, `audio/*`, `application/x-mpegURL`, `application/vnd.apple.mpegurl`, `application/dash+xml`, `application/vnd.ms-sstr+xml`, `application/mp4`, `application/x-matroska`, `application/ogg` |
| `android.intent.action.VIEW` (+ `BROWSABLE`) | `rtsp`, `rtmp` | без MIME |

> `ACTION_VIEW` с `http(s)`-ссылкой **без MIME-типа** под фильтры не попадает (иначе плеер
> перехватывал бы все веб-ссылки) — указывайте тип (`video/*`) или явный компонент.
> Схемы `smb://` в фильтрах нет: для SMB используйте явный компонент (см. 3.5).

### 3.3. Входные extras

Обязательное поле одно — `data` (URI текущего файла). Все extras необязательны.

**Совместимые с MX Player**

| Ключ | Тип | Значение |
|---|---|---|
| `title` | `String` | Заголовок текущего файла. |
| `position` | `Int` или `Long` (мс); строка тоже читается | Позиция старта. Если не задана — продолжение с сохранённого места (если включено «Продолжать просмотр»). |
| `from_start` | `Boolean` | `true` — начать с нуля, игнорируя сохранённую позицию и `position` (ключ VLC, у MX аналогичное поведение). |
| `headers` | `String[]` | HTTP-заголовки парами: `["User-Agent", "App/1.0", "Referer", "https://…"]`. `User-Agent` заменяет стандартный `2160Player/1.0 (Linux; Android) ExoPlayerLib`. Применяются ко всем элементам плейлиста и к внешним субтитрам. |
| `return_result` | `Boolean` | Вернуть результат (см. 3.4). При вызове через `startActivityForResult` результат возвращается и без этого флага. |
| `subs` | `Parcelable[]` (`Uri[]`) | Внешние субтитры для текущего файла. |
| `subs.name` | `String[]` | Отображаемые имена субтитров (по индексу `subs`). |
| `subs.enable` | `Parcelable[]` (`Uri[]`) | Какие из `subs` включить сразу. |
| `video_list` | `Parcelable[]` (`Uri[]`) | Плейлист. Стартовый элемент — тот, что совпадает с `data` (иначе первый). |
| `video_list.name` | `String[]` | Заголовки элементов плейлиста. |

**Совместимые с VLC**

| Ключ | Тип | Значение |
|---|---|---|
| `subtitles_location` | `String` | Путь или URI одного файла субтитров; включается сразу. Строка без `://` считается путём (`file://`). |
| `from_start` | `Boolean` | См. выше. |

**Собственные `tv.p2160.*`**

| Ключ | Тип | Значение |
|---|---|---|
| `tv.p2160.extra.PLAYLIST` | `String[]` | Плейлист строками URI — альтернатива `video_list` (Parcelable[]), удобная из adb (`--esa`), веб-оболочек и скриптов. `data` Intent'а задаёт стартовый элемент. |
| `tv.p2160.extra.TITLES` | `String[]` | Заголовки плейлиста (используется, если нет `video_list.name`). |
| `tv.p2160.extra.MIME_TYPES` | `String[]` | MIME по элементам плейлиста. Нужен для HLS/DASH без расширения в URL (`application/x-mpegURL`, `application/dash+xml`, `application/vnd.ms-sstr+xml`). Для текущего файла без плейлиста используется `Intent.type`. |
| `tv.p2160.extra.SEGMENTS` | `String` | Пропускаемые отрезки текущего файла: `intro:0-90000;credits:1320000-` (см. [§12](#12-главы-и-пропускаемые-отрезки)). |
| `tv.p2160.extra.INTRO_START` | `Int`/`Long` (мс) | Начало вступления (по умолчанию 0). Учитывается только вместе с `INTRO_END`. |
| `tv.p2160.extra.INTRO_END` | `Int`/`Long` (мс) | Конец вступления. |
| `tv.p2160.extra.CREDITS_START` | `Int`/`Long` (мс) | Начало финальных титров (до конца файла). |
| `tv.p2160.extra.ARTWORK` | `String` (URI) | Обложка текущего файла (`MediaEntry.artworkUri`). |
| `tv.p2160.extra.ARTWORKS` | `String[]` | Обложки элементов плейлиста по порядку; пустая строка — нет. |
| `tv.p2160.extra.LIVE` | `Boolean` | Плейлист — телеканалы (`PlaybackRequest.liveTv`): без продолжения с места и истории, «Эфир» вместо полосы перемотки, стрелки вверх/вниз (и CH+/CH−, цифры) переключают каналы по кругу. |

Субтитры, MIME из `Intent.type` и отрезки относятся только к **стартовому** элементу плейлиста.

### 3.4. Результат

Плеер возвращает `RESULT_OK` и Intent с action `com.mxtech.intent.result.VIEW` (как MX Player),
когда пользователь закрывает плеер кнопкой «Назад» и при этом был передан `return_result=true`
или плеер вызван через `startActivityForResult`.

| Ключ | Тип | Значение |
|---|---|---|
| `data` | `Uri` | URI элемента, на котором остановились (в плейлисте — текущий). |
| `position` | `Int` (мс) | Позиция остановки (MX Player). |
| `duration` | `Int` (мс) | Длительность (MX Player). |
| `end_by` | `String` | `"user"` — закрыл пользователь, `"playback_completion"` — досмотрено до конца. |
| `extra_position` | `Long` (мс) | Позиция (VLC). |
| `extra_duration` | `Long` (мс) | Длительность (VLC). |

Если плеер закрыт иначе (свайп из «Недавних», системой), вернётся `RESULT_CANCELED` без данных.

### 3.5. Примеры (Kotlin, без библиотеки)

```kotlin
// 1. Выбор плеера пользователем (системный chooser, любой совместимый плеер).
val view = Intent(Intent.ACTION_VIEW)
    .setDataAndType(Uri.parse("https://example.com/movie.mkv"), "video/*")
    .putExtra("title", "Фильм")
startActivity(Intent.createChooser(view, "Открыть в…"))

// 2. Именно 2160 Player, с возвратом позиции.
val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
    if (r.resultCode == Activity.RESULT_OK) {
        val data = r.data ?: return@registerForActivityResult
        val positionMs = data.getIntExtra("position", 0)
        val durationMs = data.getIntExtra("duration", 0)
        val completed = data.getStringExtra("end_by") == "playback_completion"
    }
}

val play = Intent("tv.p2160.action.PLAY")
    .setDataAndType(Uri.parse("https://cdn.example.com/show/s01e02.m3u8"), "application/x-mpegURL")
    .setPackage("tv.p2160.player")                        // или setComponent(...) — см. ниже
    .putExtra("title", "Сериал — S01E02")
    .putExtra("position", 125_000)                        // мс
    .putExtra("headers", arrayOf("User-Agent", "MyApp/2.0", "Authorization", "Bearer …"))
    .putExtra("subs", arrayOf(Uri.parse("https://cdn.example.com/s01e02.ru.srt")))
    .putExtra("subs.name", arrayOf("Русские"))
    .putExtra("subs.enable", arrayOf(Uri.parse("https://cdn.example.com/s01e02.ru.srt")))
    .putExtra("tv.p2160.extra.SEGMENTS", "intro:30000-120000;credits:1290000-")
    .putExtra("return_result", true)
launcher.launch(play)

// 3. Явный компонент: работает для любой схемы, в т.ч. smb://, в обход intent-фильтров.
val smb = Intent(Intent.ACTION_VIEW)
    .setData(Uri.parse("smb://192.168.1.10/Movies/Film.mkv"))
    .setComponent(ComponentName("tv.p2160.player", "tv.p2160.core.Player2160Activity"))
startActivity(smb)

// 4. Плейлист.
val episodes = arrayOf(Uri.parse("https://…/e1.mp4"), Uri.parse("https://…/e2.mp4"))
startActivity(
    Intent(Intent.ACTION_VIEW)
        .setDataAndType(episodes[0], "video/*")
        .setPackage("tv.p2160.player")
        .putExtra("video_list", episodes)
        .putExtra("video_list.name", arrayOf("Серия 1", "Серия 2"))
)
```

**Chooser или явный пакет?**
`Intent.createChooser` показывает все подходящие плееры и не гарантирует возврат результата
(часть лаунчеров его теряет). Если нужна позиция остановки — задайте пакет (`setPackage`) или
компонент (`setComponent`) и запускайте через `startActivityForResult`/`ActivityResultLauncher`.
На Android 11+ для проверки, установлен ли плеер (`getPackageInfo`, `resolveActivity`), объявите
в манифесте `<queries><package android:name="tv.p2160.player"/></queries>`. Сам запуск
`startActivity` по пакету работает и без `<queries>`.

Для `content://`-URI добавляйте `Intent.FLAG_GRANT_READ_URI_PERMISSION`.

### 3.6. Примеры `adb shell am start`

```sh
# Ссылка с заголовком и позицией (Long через --el, Int через --ei — читаются оба)
adb shell am start -a android.intent.action.VIEW \
  -n tv.p2160.player/tv.p2160.core.Player2160Activity \
  -d "https://example.com/movie.mkv" -t "video/*" \
  --es title "Фильм" --el position 600000

# HLS с заголовками (--esa разделяет элементы запятыми) и с начала
adb shell am start -a tv.p2160.action.PLAY \
  -n tv.p2160.player.debug/tv.p2160.core.Player2160Activity \
  -d "https://cdn.example.com/live/index.m3u8" \
  --esa headers "User-Agent,MyApp/1.0,Referer,https://example.com" \
  --ez from_start true

# Отрезки и внешний файл субтитров (ключ VLC)
adb shell am start -a android.intent.action.VIEW \
  -n tv.p2160.player/tv.p2160.core.Player2160Activity \
  -d "smb://192.168.1.10/Series/Show.S01E02.mkv" \
  --es tv.p2160.extra.SEGMENTS "intro:0-90000;credits:1320000-" \
  --es subtitles_location "/sdcard/Download/Show.S01E02.ru.srt"

# Blu-ray образ
adb shell am start -a android.intent.action.VIEW \
  -n tv.p2160.player/tv.p2160.core.Player2160Activity \
  -d "smb://nas/Movies/Film.iso"
```

`am start` не умеет передавать массивы `Uri` — `subs`, `subs.enable` и `video_list` доступны только
из кода.

---

## 4. Подключение библиотеки

Подробные варианты (модуль, `includeBuild`, `mavenLocal`, GitHub Packages) — в
[EMBEDDING.md](EMBEDDING.md#1-подключение). Кратко:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories { google(); mavenCentral(); mavenLocal() }
}

// app/build.gradle.kts
android {
    compileSdk = 37            // библиотека собрана с compileSdk 37
    defaultConfig { minSdk = 24 }

    // ОБЯЗАТЕЛЬНО: nextlib-media3ext и nextlib-mediainfo несут одинаковые .so FFmpeg.
    packaging {
        jniLibs.pickFirsts += listOf(
            "**/libavcodec.so", "**/libavutil.so", "**/libswscale.so", "**/libswresample.so",
        )
    }
}

dependencies {
    implementation("tv.p2160:player-core:0.2.4")
    // Для своего Compose-UI (PlayerScreen, PlayerAction.icon) — Compose-плагин в вашем модуле
    // и при необходимости material-icons-extended.
}
```

### 4.1. Транзитивные зависимости

| Scope | Библиотеки |
|---|---|
| `api` (видны вашему коду) | `media3-exoplayer`, `activity-compose`, `kotlinx-coroutines-android`, Compose BOM, `compose-ui`, `material3` |
| `implementation` (только runtime) | Media3 HLS/DASH/SmoothStreaming/RTSP/UI, nextlib (FFmpeg + mediainfo), smbj, dcerpc, `core-ktx`, `lifecycle-runtime-compose`, `material-icons-extended` |

FFmpeg добавляет ~8 МБ на каждую ABI — используйте `splits { abi { … } }` или App Bundle.

### 4.2. Манифест

Библиотека добавляет в ваш манифест:

- разрешения `INTERNET`, `ACCESS_NETWORK_STATE` и `CHANGE_WIFI_MULTICAST_STATE` (поиск DLNA-серверов
  по SSDP, `WifiManager.MulticastLock`);
- `android:usesCleartextTraffic="true"` на `<application>` (IPTV и домашние серверы часто без HTTPS);
- Activity `tv.p2160.core.Player2160Activity` — `exported="false"`, `singleTop`,
  `supportsPictureInPicture="true"`, тема `@style/Theme.P2160.Player`;
- сервис `tv.p2160.core.engine.PlaybackService` (`MediaSessionService`, `foregroundServiceType="mediaPlayback"`)
  и разрешения `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` — уведомление с управлением
  и фоновое воспроизведение (см. [§15.4](#154-фоновое-воспроизведение-и-mediasession)).

Внутри вашего приложения Activity доступна без изменений. Чтобы **другие** приложения могли
открывать видео через ваш APK (как это делает 2160 Player), переопределите её в своём манифесте:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">
  <application>
    <activity
        android:name="tv.p2160.core.Player2160Activity"
        android:exported="true"
        tools:node="merge"
        tools:replace="android:exported">
      <intent-filter>
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <data android:scheme="content" />
        <data android:scheme="file" />
        <data android:scheme="http" />
        <data android:scheme="https" />
        <data android:mimeType="video/*" />
        <data android:mimeType="audio/*" />
      </intent-filter>
      <intent-filter>
        <action android:name="tv.p2160.action.PLAY" />
        <category android:name="android.intent.category.DEFAULT" />
        <data android:scheme="content" />
        <data android:scheme="file" />
        <data android:scheme="http" />
        <data android:scheme="https" />
      </intent-filter>
    </activity>
  </application>
</manifest>
```

Если вам не нужен cleartext-трафик, уберите его: `tools:replace="android:usesCleartextTraffic"`
и `android:usesCleartextTraffic="false"` на своём `<application>` (или `networkSecurityConfig`).

### 4.3. ProGuard / R8

`consumer-rules.pro` библиотеки подключается автоматически:

```proguard
-keep class io.github.anilbeesetti.nextlib.** { *; }   # JNI-биндинги FFmpeg
-keep class tv.p2160.core.api.** { *; }
```

Дополнительных правил обычно не требуется. Если R8 сообщит о недостающих классах из
зависимостей smbj (`org.slf4j`, `org.bouncycastle`, `javax.el`…), добавьте соответствующие
`-dontwarn` в правила своего приложения.

---

## 5. Запуск плеера из библиотеки

### 5.1. Модель запроса

```kotlin
data class PlaybackRequest(
    val items: List<MediaEntry>,
    val startIndex: Int = 0,
    val startPositionMs: Long? = null,
    val headers: Map<String, String> = emptyMap(),
    val returnResult: Boolean = false,
    val liveTv: Boolean = false,
)
```

| Параметр | Описание |
|---|---|
| `items` | Плейлист, минимум один элемент (иначе `IllegalArgumentException`). |
| `liveTv` | Элементы — телеканалы (IPTV). Позиции не сохраняются, вместо полосы перемотки — «Эфир», стрелки вверх/вниз при скрытой панели переключают канал, под названием — текущая передача из `LiveGuide` (`Player2160.setLiveGuide`; по умолчанию — EPG плейлистов `tv.p2160.core.iptv.IptvStore`). |
| `startIndex` | С какого элемента начать (приводится к допустимому диапазону). |
| `startPositionMs` | Позиция старта для `startIndex`. `null` — продолжить с сохранённой позиции (если включено `Settings.autoResume` и сохранено > 5 с); `0` — строго с начала. |
| `headers` | HTTP-заголовки для всех сетевых запросов (видео, субтитры, ISO по HTTP). |
| `returnResult` | Вернуть `PlaybackResult` вызывающей Activity при закрытии. |

`PlaybackRequest.single(uri: Uri, title: String? = null)` — короткая форма для одного файла.

```kotlin
data class MediaEntry(
    val uri: Uri,
    val title: String? = null,
    val subtitles: List<ExternalSubtitle> = emptyList(),
    val mimeType: String? = null,
    val segments: List<SkipSegment> = emptyList(),
)
```

| Параметр | Описание |
|---|---|
| `uri` | `http(s)`, `rtsp`, `rtmp`, `file`, `content`, `smb`; путь к `.iso` или папке `BDMV`. |
| `title` | Заголовок. По умолчанию — имя файла без расширения (или название диска Blu-ray). |
| `subtitles` | Внешние субтитры. |
| `mimeType` | Нужен для адаптивных потоков без расширения: значение, содержащее `mpegurl` → HLS, `dash` → DASH, `vnd.ms-sstr` → SmoothStreaming. Для остального игнорируется. URL с `.m3u8` распознаётся и без него. |
| `segments` | Известные пропускаемые отрезки (имеют приоритет над найденными по главам). |
| `artworkUri` | Обложка/постер (`http(s)`, `smb`, `file`, `content`) для «Продолжить просмотр» и истории (§10.1). Не задана — плеер ищет сам. |

```kotlin
data class ExternalSubtitle(
    val uri: Uri,
    val name: String? = null,
    val language: String? = null,   // ISO 639, например "ru"; по умолчанию — из имени файла (Movie.ru.srt)
    val select: Boolean = false,    // включить сразу после старта
)
```

### 5.2. `Player2160` — фасад

| Член | Описание |
|---|---|
| `fun play(context: Context, request: PlaybackRequest)` | Запускает `Player2160Activity`. Из не-Activity контекста добавляет `FLAG_ACTIVITY_NEW_TASK`. |
| `fun intent(context: Context, request: PlaybackRequest): Intent` | Готовый явный Intent (action `tv.p2160.action.PLAY`, `FLAG_GRANT_READ_URI_PERMISSION`) — например, для `PendingIntent` или своих флагов. |
| `class PlayContract : ActivityResultContract<PlaybackRequest, PlaybackResult?>` | Запуск с результатом; сам выставляет `returnResult = true`. |
| `val nowPlaying: StateFlow<NowPlaying?>` | Что играет сейчас (см. [§7](#7-наблюдение-за-воспроизведением)). |
| `fun registerAction(action: PlayerAction)` / `fun unregisterAction(id: String)` | Свои кнопки в плеере (см. [§8](#8-свои-кнопки-в-плеере-playeraction)). |
| `fun settings(context: Context): PlayerSettings` | Настройки (см. [§9](#9-настройки-и-темы)). |
| `fun resumeStore(context: Context): ResumeStore` | История (см. [§10](#10-история-и-позиции-остановки)). |
| `var config: PlayerConfig` | Настройка движка для новых плееров: буфер, тайм-ауты, фоновая работа (см. [§15.6](#156-настройка-движка-playerconfig)). |
| `fun pause()` | Пауза активного плеера с любого потока (например, после передачи просмотра на другое устройство). Без открытого плеера ничего не делает. |
| `fun setLiveGuide(guide: LiveGuide?)` | Свой телегид для `liveTv`-запросов; `null` — встроенный `IptvStore` (см. [§20](#20-iptv-m3u-xmltv-режим-эфира)). |

```kotlin
// Простой запуск
Player2160.play(context, PlaybackRequest.single(Uri.parse(url), "Фильм"))

// Плейлист с заголовками и субтитрами, старт со второй серии с начала
Player2160.play(
    context,
    PlaybackRequest(
        items = listOf(
            MediaEntry(Uri.parse("https://…/e1.mkv"), "Серия 1"),
            MediaEntry(
                Uri.parse("https://…/e2.mkv"), "Серия 2",
                subtitles = listOf(ExternalSubtitle(Uri.parse("https://…/e2.ru.srt"), "Русские", "ru", select = true)),
                segments = SkipSegment.parseList("intro:0-85000;credits:1310000-"),
            ),
        ),
        startIndex = 1,
        startPositionMs = 0,
        headers = mapOf("Authorization" to "Bearer $token"),
    ),
)

// С результатом
class DetailsActivity : ComponentActivity() {
    private val player = registerForActivityResult(Player2160.PlayContract()) { result: PlaybackResult? ->
        if (result != null) saveWatchProgress(result.uri, result.positionMs, result.durationMs, result.completed)
    }
    fun watch(uri: Uri) = player.launch(PlaybackRequest.single(uri, "Фильм"))
}
```

```kotlin
data class PlaybackResult(
    val uri: Uri?,          // элемент, на котором остановились
    val positionMs: Long,
    val durationMs: Long,
    val completed: Boolean, // досмотрено до конца (end_by = playback_completion)
)
```

`null` в колбэке `PlayContract` — плеер закрыт без `RESULT_OK` (см. 3.4).

### 5.3. `IntentApi` — разбор и сборка Intent'ов

Полезно, если вы сами принимаете Intent'ы от других приложений или вызываете внешний плеер.

| Член | Описание |
|---|---|
| `fun parse(intent: Intent): PlaybackRequest?` | Intent (форматы MX/VLC/`tv.p2160`) → запрос; `null`, если нет `data`. |
| `fun toIntent(request: PlaybackRequest, intent: Intent): Intent` | Заполняет переданный Intent: action `ACTION_PLAY`, data/type, extras. Позиция пишется как `Int`. |
| `fun buildResult(result: PlaybackResult): Intent` | Intent-результат в форматах MX Player + VLC. |
| `fun parseResult(resultCode: Int, intent: Intent?): PlaybackResult?` | Обратное преобразование; `null`, если не `RESULT_OK`. Понимает результат MX Player. |

Константы: `ACTION_PLAY`, `EXTRA_TITLE`, `EXTRA_POSITION`, `EXTRA_FROM_START`, `EXTRA_HEADERS`,
`EXTRA_RETURN_RESULT`, `EXTRA_SUBS`, `EXTRA_SUBS_NAME`, `EXTRA_SUBS_ENABLE`, `EXTRA_VLC_SUBTITLE`,
`EXTRA_VIDEO_LIST`, `EXTRA_VIDEO_LIST_NAME`, `EXTRA_TITLES`, `EXTRA_PLAYLIST`, `EXTRA_MIME_TYPES`,
`EXTRA_SEGMENTS`, `EXTRA_INTRO_START`, `EXTRA_INTRO_END`, `EXTRA_CREDITS_START`, `EXTRA_ARTWORK`, `EXTRA_ARTWORKS`, `EXTRA_LIVE`, `RESULT_ACTION`, `RESULT_POSITION`,
`RESULT_DURATION`, `RESULT_END_BY`, `END_BY_USER`, `END_BY_COMPLETION` — значения в таблицах §3.

### 5.4. `Player2160Activity`

Полноэкранная Activity библиотеки: скрывает системные панели, на телефоне поворачивает экран по
пропорциям видео (на ТВ — нет), уходит в PiP по кнопке «Домой» во время воспроизведения видео,
ставит на паузу и сохраняет позицию в `onStop`. `singleTop`: новый Intent в уже открытый плеер
заменяет текущее воспроизведение. Контроллер хранится в `PlayerViewModel` и переживает повороты.

---

## 6. Встраивание `PlayerScreen` в свой экран

```kotlin
@Composable
fun PlayerScreen(
    controller: PlayerController,
    settingsStore: PlayerSettings,
    onBack: () -> Unit,
    onPickSubtitle: () -> Unit,
    inPictureInPicture: Boolean = false,
)
```

| Параметр | Описание |
|---|---|
| `controller` | Движок воспроизведения. Создаёте и освобождаете вы. |
| `settingsStore` | Обычно `Player2160.settings(context)`. Экран читает тему, стиль субтитров, шаг перемотки, режим масштаба и пишет изменения, сделанные пользователем в панелях. |
| `onBack` | Кнопка «Назад» в панели и системный Back (если открыта боковая панель — сначала закрывается она). Здесь закрывают экран и/или возвращают результат. |
| `onPickSubtitle` | Пункт «Загрузить из файла…» в панели субтитров. Откройте выбор файла и передайте URI в `controller.addExternalSubtitle(uri)`. |
| `inPictureInPicture` | `true` — скрыть все элементы управления (режим PiP). |

Экран занимает всё доступное место (`fillMaxSize`), рисует свою тему (`P2160Theme`) и строки
(`LocalStrings`), обрабатывает жесты, клавиши пульта и `BackHandler`. Системные панели, ориентацию,
PiP и паузу при уходе в фон он **не** трогает — это задача Activity.

Жесты `PlayerScreen`: тап — показать/скрыть управление, двойной тап по краям — перемотка, удержание — 2×,
горизонтальный свайп — перемотка, щипок — масштаб. Свайп, начатый в зоне системного жеста у края экрана
(`WindowInsets.systemGestures`, при жестовой навигации), перемоткой не считается — это системный «Назад».

### 6.1. `PlayerController`

```kotlin
class PlayerController(
    context: Context,
    request: PlaybackRequest,
    config: PlayerConfig = Player2160.config,   // буфер, тайм-ауты, фоновая работа — §15.6
)
```

Создание сразу собирает ExoPlayer и **начинает воспроизведение** (`playWhenReady = true`),
восстанавливает позицию/дорожки/скорость, раз в ~5 с сохраняет прогресс и раз в секунду публикует
`Player2160.nowPlaying`. Создавайте и вызывайте все методы на главном потоке. Обязательно вызовите
`release()`, иначе останутся ExoPlayer, декодеры, открытые диски и медиасессия с уведомлением
(контроллер сам регистрирует `MediaSession` и сервис `PlaybackService`, см. [§15.4](#154-фоновое-воспроизведение-и-mediasession)).

| Член | Описание |
|---|---|
| `val state: StateFlow<PlayerUiState>` | Состояние для UI (обновляется ~2 раза в секунду и по событиям). |
| `val player: ExoPlayer` | Исходный ExoPlayer — для `pause()`, слушателей, `MediaSession` и т.п. Не вызывайте на нём `release()`. |
| `val secondaryCues: StateFlow<List<Cue>>` | Реплики вторых субтитров (их рисует `PlayerScreen`). |
| `fun playPause()` | Пауза/продолжить; после конца — с начала; после ошибки — `prepare()`. |
| `fun seekTo(positionMs: Long)` / `fun seekBy(deltaMs: Long)` | Перемотка (ограничивается длительностью). |
| `fun next()` / `fun previous()` | Следующий/предыдущий элемент плейлиста. `previous()` после 5 с — к началу текущего. Для `liveTv` — то же, что `switchChannel(±1)`. |
| `fun switchChannel(delta: Int)` | Переключить канал по кругу (для `liveTv`; при одном элементе ничего не делает). |
| `fun setSpeed(speed: Float)` | Скорость 0.25–4. |
| `fun setSpeedBoost(enabled: Boolean)` | Временное ×2 (удержание пальца); выключение возвращает прежнюю скорость. |
| `fun select(option: TrackOption)` | Выбрать аудио/видео/текстовую дорожку из `state.*Tracks`. |
| `fun autoVideo()` | Вернуть автоматический выбор качества (HLS/DASH). |
| `fun disableSubtitles()` | Выключить субтитры. |
| `fun setSecondarySubtitle(option: TrackOption?)` | Вторые субтитры (сверху); `null` — выключить. |
| `fun setSubtitleDelay(ms: Long)` | Задержка субтитров. |
| `fun addExternalSubtitle(uri: Uri, name: String? = null)` | Подключить файл субтитров к текущему элементу и сразу включить. |
| `fun nextChapter()` / `fun previousChapter()` / `fun seekToChapter(chapter: Chapter)` | Главы. `previousChapter()` в первые 3 с главы переходит к предыдущей. |
| `fun skip(segment: SkipSegment)` | Пропустить отрезок; титры до конца файла при наличии следующего элемента — переход к нему. |
| `fun markIntroStart()` / `fun markIntroEnd()` / `fun markCreditsStart()` / `fun clearMarks()` | Ручные отметки по текущей позиции (для сериала — на все серии). |
| `suspend fun frameAt(positionMs: Long): Bitmap?` | Кадр для превью; `null`, если источник не позволяет. |
| `fun setNightMode(enabled: Boolean)` | «Ночной звук» на лету для этого контроллера: включение переводит звук на декодирование, выключение возвращает passthrough на ресивер и многоканал (ручное переключение отменяет расписание до `onNightScheduleChanged()`); `Settings` не меняет. См. [§16](#16-ночной-звук). |
| `fun onNightScheduleChanged()` | Вызвать после изменения `nightAuto`/`nightStart/EndMinute`: сбрасывает ручное переключение и сразу применяет расписание. |
| `fun dismissSmartHint()` | Скрыть подсказку `state.smartHint` («дорожки выбраны по привычке»). |
| `fun report(): MediaReport` | Сводка по текущему файлу (см. [§18](#18-поиск-вступлений-по-звуку-и-сводка-по-файлу)). |
| `fun dismissWarnings()` | Очистить `state.warnings`. |
| `val stats: StateFlow<PlaybackStats?>` / `fun setStatsEnabled(enabled: Boolean)` | Живая статистика (§15.7): собирается, только пока включена (`null` — выключена). |
| `fun onDisplaySwitching()` | Сообщить, что сейчас меняется режим экрана: ближайшие 12 с сбои открытия звука на ресивер считаются временными. Смену режима плеер замечает и сам (`DisplayManager`). |
| `fun setInBackground(background: Boolean)` | Плеер ушёл с экрана, но звук продолжается: `true` отключает декодирование видео, `false` возвращает его (см. [§15.4](#154-фоновое-воспроизведение-и-mediasession)). |
| `fun setAudioDelay(ms: Int)` | Задержка звука относительно картинки, мс (> 0 — звук позже, < 0 — раньше; предел ±`AUDIO_DELAY_LIMIT_MS` = 2000). Сдвигает аудиочасы, по которым синхронизируется видео; работает и для PCM, и для passthrough. Сохраняется в `Settings.audioDelayMs` — общая для всех файлов. |
| `fun setSleepTimer(minutes: Int?)` | Таймер сна: через N минут звук за 8 с затихает и плеер встаёт на паузу; `null`/`0` — выключить. Остаток — в `state.sleepRemainingMs`. |
| `fun setSleepAtEndOfItem()` | Таймер «в конце серии»: пауза, когда закончится текущий файл (без автоперехода к следующему). |
| `fun cancelSleepTimer()` | Выключить таймер сна (громкость и автопереход возвращаются). |
| `fun retry()` | Повторить после ошибки. |
| `fun dismissResumeHint()` / `fun restartFromBeginning()` | Подсказка «Продолжено с …». |
| `fun result(): PlaybackResult` | Текущий результат (для возврата вызывающему). |
| `fun saveProgress()` | Сохранить позицию сейчас (ничего не делает, пока длительность неизвестна, и для live). |
| `fun release()` | Сохранить прогресс, опубликовать `nowPlaying` с `isPlaying = false`, освободить всё. |

### 6.2. Полный пример Activity

Тот же код — в `samples/embed-demo/.../EmbeddedPlayerActivity.kt`.

```kotlin
/** Контроллер живёт в ViewModel, чтобы пережить поворот экрана. */
class EmbeddedPlayerViewModel(app: Application) : AndroidViewModel(app) {
    var controller by mutableStateOf<PlayerController?>(null)
        private set

    fun open(request: PlaybackRequest) {
        controller?.release()
        controller = PlayerController(getApplication(), request)
    }

    override fun onCleared() {
        controller?.release()
        controller = null
    }
}

class EmbeddedPlayerActivity : ComponentActivity() {
    private val vm: EmbeddedPlayerViewModel by viewModels()
    private var inPip by mutableStateOf(false)

    private val pickSubtitle = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.controller?.addExternalSubtitle(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (vm.controller == null) {
            val uri = intent.data ?: run { finish(); return }
            vm.open(PlaybackRequest.single(uri, intent.getStringExtra("title")))
        }
        setContent {
            Column(Modifier.fillMaxSize().background(Color.Black)) {
                if (!inPip) Text("Мой заголовок", color = Color.White, modifier = Modifier.safeDrawingPadding().padding(12.dp))
                Box(Modifier.weight(1f)) {
                    vm.controller?.let { controller ->
                        PlayerScreen(
                            controller = controller,
                            settingsStore = Player2160.settings(this@EmbeddedPlayerActivity),
                            onBack = ::finish,
                            onPickSubtitle = { pickSubtitle.launch(arrayOf("*/*")) },
                            inPictureInPicture = inPip,
                        )
                    }
                }
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return
        val s = vm.controller?.state?.value ?: return
        if (!s.isPlaying || !s.hasVideo) return
        val aspect = s.videoAspect.takeIf { it > 0f }?.coerceIn(0.42f, 2.39f) ?: (16f / 9f)
        runCatching {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder().setAspectRatio(Rational((aspect * 1000).toInt(), 1000)).build()
            )
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
    }

    override fun onStart() {
        super.onStart()
        vm.controller?.setInBackground(false)   // снова декодируем видео
    }

    override fun onStop() {
        super.onStop()
        val controller = vm.controller ?: return
        controller.saveProgress()
        if (isFinishing) { controller.player.pause(); return }
        if (inPip && !isChangingConfigurations) return            // в PiP играем дальше
        // Аудио — если «Музыка и аудио в фоне», видео — если «Видео в фоне»; иначе пауза.
        val s = controller.state.value
        val settings = Player2160.settings(this).current
        val background = controller.player.playWhenReady &&
            (if (s.hasVideo) settings.backgroundPlayback else settings.backgroundAudio)
        if (background) controller.setInBackground(true) else controller.player.pause()
    }
}
```

Манифест для такой Activity:

```xml
<activity
    android:name=".EmbeddedPlayerActivity"
    android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboard|keyboardHidden|navigation|uiMode|density"
    android:launchMode="singleTop"
    android:resizeableActivity="true"
    android:supportsPictureInPicture="true"
    android:theme="@style/Theme.P2160.Player" />
```

`@style/Theme.P2160.Player` — тема библиотеки (чёрный фон, без ActionBar, прозрачные системные
панели). Готовый `tv.p2160.core.PlayerViewModel` (используется `Player2160Activity`) тоже можно
взять вместо своей ViewModel: `open(request)`, `controller`, `request`.

**Правила жизненного цикла**

- Один `PlayerController` — на один показ. Для нового видео создайте новый (`open()` выше
  освобождает старый). Повторно использовать контроллер после `release()` нельзя.
- `configChanges` избавляет от пересоздания при повороте; ViewModel страхует остальные случаи.
- Пауза в `onStop` — ваша ответственность (иначе звук продолжится в фоне).
- Если нужен возврат результата, в `onBack` вызовите `controller.saveProgress()`, затем
  `setResult(RESULT_OK, IntentApi.buildResult(controller.result()))` и `finish()`.
- В Navigation Compose можно создавать контроллер через `remember` + `DisposableEffect { onDispose { release() } }`,
  но тогда он не переживёт смену конфигурации без `configChanges`.

---

## 7. Наблюдение за воспроизведением

### 7.1. `Player2160.nowPlaying`

```kotlin
data class NowPlaying(
    val uri: Uri,                    // URI из запроса (для дисков — путь к ISO/папке)
    val title: String,
    val positionMs: Long,
    val durationMs: Long,
    val isPlaying: Boolean,
    val headers: Map<String, String> = emptyMap(),
    val updatedAt: Long = System.currentTimeMillis(),
)
```

Глобальный `StateFlow` процесса. Обновляется примерно раз в секунду любым живым `PlayerController`
(и `Player2160Activity`, и вашими экранами). До первого воспроизведения — `null`. После
`release()` значение **остаётся** последним снимком с `isPlaying = false` (в `null` не
сбрасывается). Воспроизведение в отдельном приложении 2160 Player (Intent API) сюда не попадает —
это другой процесс.

```kotlin
lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        Player2160.nowPlaying.collect { np -> miniPlayer.render(np) }
    }
}
// Compose
val np by Player2160.nowPlaying.collectAsStateWithLifecycle()
```

### 7.2. `PlayerController.state` — `PlayerUiState`

| Поле | Тип | Описание |
|---|---|---|
| `title` | `String` | Заголовок текущего элемента. |
| `isPlaying` | `Boolean` | Идёт воспроизведение. |
| `isBuffering` | `Boolean` | Буферизация (изначально `true`). |
| `ended` | `Boolean` | Достигнут конец. |
| `positionMs` / `durationMs` / `bufferedMs` | `Long` | Позиция, длительность (0, если неизвестна), буфер. |
| `speed` | `Float` | Текущая скорость. |
| `audioTracks` / `textTracks` / `videoTracks` | `List<TrackOption>` | Дорожки. |
| `textDisabled` | `Boolean` | Субтитры выключены. |
| `subtitleDelayMs` | `Long` | Задержка субтитров. |
| `hasVideo` | `Boolean` | Есть видео (иначе показывается обложка аудио). |
| `videoAspect` | `Float` | Пропорции кадра с учётом PAR; 0 — неизвестно. |
| `hasNext` / `hasPrevious` | `Boolean` | Есть соседние элементы плейлиста. |
| `playlistIndex` / `playlistSize` | `Int` | Позиция в плейлисте. |
| `resumedFromMs` | `Long?` | С какой позиции продолжено (для подсказки); `null` — нет. |
| `error` | `String?` | Локализованный текст ошибки воспроизведения. |
| `chapters` | `List<Chapter>` | Главы. |
| `chapterIndex` | `Int` | Текущая глава или -1. |
| `segments` | `List<SkipSegment>` | Все отрезки текущего файла (из запроса, глав и ручных отметок). |
| `activeSegment` | `SkipSegment?` | Отрезок под текущей позицией (если `skipMode != OFF`). |
| `seriesName` | `String?` | Ключ сериала для ручных отметок; `null` — отметки только для файла. |
| `marks` | `ManualMarks` | Ручные отметки. |
| `speedBoost` | `Boolean` | Включено временное ускорение. |
| `secondaryTextId` | `String?` | `Format.id` вторых субтитров. |
| `nightMode` | `Boolean` | Ночной звук сейчас включён. |
| `smartHint` | `String?` | Локализованная подсказка «Как обычно: …», если дорожки выбраны по привычке (§17). |
| `warnings` | `List<String>` | Важные предупреждения из `report()` (программное декодирование 4K, Dolby Vision без декодера, HDR на SDR-экране); заполняются через ~2 с воспроизведения. |
| `isLive` | `Boolean` | Прямой эфир: поток помечен как live (или `liveTv` и длительность неизвестна). |
| `liveTv` | `Boolean` | Запрос — телеканалы (`PlaybackRequest.liveTv`). |
| `subtitle` | `String?` | Подпись под названием; для каналов — текущая передача из `LiveGuide`. |
| `audioDelayMs` | `Int` | Текущая задержка звука, мс. |
| `sleepRemainingMs` | `Long?` | Таймер сна: сколько осталось до паузы; `null` — выключен (или стоит «в конце серии»). |
| `sleepAtEnd` | `Boolean` | Таймер сна «в конце серии». |
| `videoFrameRate` | `Float` | Частота кадров видео (из заголовка или по меткам кадров); `-1` — неизвестна. Для частоты экрана (§15.7). |

```kotlin
data class TrackOption(
    val type: Int,            // C.TRACK_TYPE_AUDIO / TEXT / VIDEO
    val groupIndex: Int,
    val trackIndex: Int,
    val label: String,        // «Русский · DTS-HD MA · 5.1»
    val language: String?,
    val rawLabel: String?,
    val selected: Boolean,
    val supported: Boolean,
    val external: Boolean,    // внешний файл субтитров
    val formatId: String? = null,
)
```

---

## 8. Свои кнопки в плеере: `PlayerAction`

```kotlin
class PlayerAction(
    val id: String,
    val icon: ImageVector,
    val label: String,   // ключ локализации или готовая строка (contentDescription)
    val onClick: (context: Context, nowPlaying: NowPlaying?) -> Unit,
)
```

Кнопки показываются в верхней панели плеера — справа от заголовка, перед стандартными кнопками
(главы, аудио, субтитры, скорость, видео) — во всех экранах плеера процесса, в порядке регистрации.
Повторная регистрация с тем же `id` заменяет кнопку. `context` — Activity плеера.

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Player2160.registerAction(
            PlayerAction("share", Icons.Default.Share, "Поделиться ссылкой") { context, np ->
                val uri = np?.uri ?: return@PlayerAction
                context.startActivity(
                    Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, uri.toString()), null)
                )
            }
        )
    }
}
// Убрать: Player2160.unregisterAction("share")
```

Список кнопок также доступен как `PlayerExtensions.actions: StateFlow<List<PlayerAction>>`
(`PlayerExtensions.register/unregister` — то же, что методы `Player2160`).

---

## 9. Настройки и темы

### 9.1. `PlayerSettings`

| Член | Описание |
|---|---|
| `companion fun get(context: Context): PlayerSettings` | Синглтон (то же, что `Player2160.settings`). |
| `val state: StateFlow<Settings>` | Наблюдаемые настройки. |
| `val current: Settings` | Текущее значение. |
| `fun update(transform: (Settings) -> Settings)` | Изменить и сохранить (SharedPreferences `p2160_settings`). Смена `language` сразу применяет пакет. |

```kotlin
Player2160.settings(context).update {
    it.copy(
        themeId = "ocean",
        skipMode = SkipMode.AUTO,
        preferredAudioLanguages = listOf("ru", "en"),
        subtitleStyle = it.subtitleStyle.copy(sizeScale = 1.2f, edge = SubtitleEdge.SHADOW),
    )
}
```

### 9.2. `Settings`

| Поле | Тип / по умолчанию | Когда применяется |
|---|---|---|
| `language` | `String` = `I18n.SYSTEM` (`"system"`) | Сразу |
| `themeId` | `String` = `"cinema"` | Сразу |
| `subtitleStyle` | `SubtitleStyle()` | Сразу |
| `defaultSpeed` | `Float` = `1f` | При старте элемента (если для файла не сохранена своя скорость) |
| `decoder` | `DecoderPreference` = `AUTO` | Для следующего `PlayerController` |
| `preferredAudioLanguages` | `List<String>` = `["ru"]` | Для следующего `PlayerController` |
| `preferredSubtitleLanguages` | `List<String>` = `["ru"]` | Для следующего `PlayerController` |
| `autoResume` | `Boolean` = `true` | При старте элемента |
| `seekStepSeconds` | `Int` = `10` | Сразу (UI); шаг кнопок ExoPlayer — для следующего контроллера |
| `resizeMode` | `ResizeMode` = `FIT` | При открытии `PlayerScreen` |
| `autoPlayNext` | `Boolean` = `true` | Переход к следующему элементу — для следующего контроллера; карточка «Следующая серия» — сразу |
| `skipMode` | `SkipMode` = `BUTTON` | Сразу |
| `nightMode` | `Boolean` = `false` | «Ночной звук» всегда (см. [§16](#16-ночной-звук)). При старте `PlayerController`; живой контроллер переключайте через `setNightMode()` |
| `nightAuto` | `Boolean` = `false` | Включать ночной звук по расписанию `[nightStartMinute, nightEndMinute)`. Проверяется при старте и раз в минуту |
| `nightStartMinute` | `Int` = `1380` (23:00) | Минуты от полуночи. Интервал может переходить через полночь |
| `nightEndMinute` | `Int` = `600` (10:00) | Минуты от полуночи (конец не включается) |
| `smartTracks` | `Boolean` = `true` | Запоминать ручной выбор дорожек и применять к похожим файлам (см. [§17](#17-умный-выбор-дорожек)). При старте элемента |
| `backgroundPlayback` | `Boolean` = `false` | Продолжать видео (звуком, без декодирования картинки), когда плеер свёрнут или экран выключен (см. [§15.4](#154-фоновое-воспроизведение-и-mediasession)) |
| `backgroundAudio` | `Boolean` = `true` | Продолжать аудио без видео (музыку) в фоне; `false` — пауза при сворачивании |
| `pictureInPicture` | `Boolean` = `true` | «Домой» во время видео — окно PiP (если устройство поддерживает); `false` — без PiP, дальше по `backgroundPlayback` |
| `audioDelayMs` | `Int` = `0` | Задержка звука, мс (> 0 — звук позже картинки, < 0 — раньше: для Bluetooth-наушников и саундбаров). Общая для всех файлов. Живой контроллер — `setAudioDelay()` |
| `frameRateMatching` | `Boolean` = `false` | Частота экрана под видео (§15.7): ТВ переключается в 23,976/24/25/50 Гц. Сразу. |
| `tunneling` | `Boolean` = `false` | Туннельный режим вывода видео (Android TV). При ошибке декодера выключается сам и сохраняется `false`. Для следующего `PlayerController`. |
| `audioOffload` | `Boolean` = `true` | Аудио offload для звука без видео (музыка, видео в фоне): декодирует аудиочип. Не действует, пока включён ночной звук. Для следующего `PlayerController`. |
| `statsOverlay` | `Boolean` = `false` | Слой «Статистика» поверх видео (§15.7); переключается и в панели «Сведения» плеера. Сразу. |

`fun Settings.nightModeAt(minuteOfDay: Int): Boolean` — нужен ли ночной звук в эту минуту суток:
`nightMode || (nightAuto && NightSchedule.contains(nightStartMinute, nightEndMinute, minuteOfDay))`.

`object NightSchedule` (`tv.p2160.core.settings`):

| Член | Описание |
|---|---|
| `fun contains(start: Int, end: Int, minute: Int): Boolean` | Попадает ли минута в интервал; `start == end` — пустой интервал, `start > end` — через полночь (23:00–10:00). |
| `fun nowMinute(): Int` | Текущая минута суток (`HOUR_OF_DAY * 60 + MINUTE`). |
| `fun format(minute: Int): String` | `"23:00"`. |

Перечисления:

- `DecoderPreference { AUTO, HARDWARE, FFMPEG }` — аппаратные + FFmpeg как запасной / только MediaCodec / всегда FFmpeg.
- `ResizeMode { FIT, FIT_WIDTH, FIT_HEIGHT, ZOOM, FILL }` — целиком / по ширине / по высоте / заполнить экран с обрезкой / растянуть. В плеере: панель «Видео» и щипок двумя пальцами (развести — `ZOOM`, свести — `FIT`).
- `SkipMode { OFF, BUTTON, AUTO }` — не показывать / кнопка «Пропустить» / автоматически (титры с переходом к следующей серии — всегда через обратный отсчёт).
- `SubtitleEdge { NONE, OUTLINE, SHADOW }`.

```kotlin
data class SubtitleStyle(
    val sizeScale: Float = 1f,                 // 0.5–2.5 в UI
    val textColor: Long = 0xFFFFFFFF,          // ARGB
    val backgroundColor: Long = 0x00000000,    // ARGB
    val edge: SubtitleEdge = SubtitleEdge.OUTLINE,
    val bottomPadding: Float = 0.08f,          // доля высоты
    val overrideEmbeddedStyles: Boolean = false, // игнорировать стили ASS/SSA/TTML
)
```

### 9.3. Темы

`PlayerThemes`: `Cinema` (`"cinema"`, по умолчанию), `Ocean` (`"ocean"`), `Ruby` (`"ruby"`),
`Mint` (`"mint"`), `Amoled` (`"amoled"`), `Light` (`"light"`); `PlayerThemes.all`,
`PlayerThemes.byId(id)` (неизвестный id → `Cinema`).

```kotlin
data class PlayerTheme(
    val id: String, val nameKey: String,
    val accent: Color, val onAccent: Color,
    val background: Color, val surface: Color, val onSurface: Color, val muted: Color,
    val scrim: Color, val isLight: Boolean = false,
) { val colorScheme: ColorScheme }

@Composable fun P2160Theme(theme: PlayerTheme, content: @Composable () -> Unit)
```

`P2160Theme` удобно использовать и для своих экранов, чтобы они совпадали с плеером:
`P2160Theme(PlayerThemes.byId(settings.themeId)) { … }`. Добавить свою тему в плеер сейчас
нельзя: `PlayerScreen` ищет тему только среди `PlayerThemes.all` (см. ограничения).

---

## 10. История и позиции остановки

`ResumeStore` — SQLite (`p2160_resume.db`), без Room. Записывает его `PlayerController`.

| Член | Описание |
|---|---|
| `companion fun get(context: Context): ResumeStore` | Синглтон (то же, что `Player2160.resumeStore`). |
| `fun get(key: String): ResumeEntry?` | Запись по ключу. |
| `fun recent(limit: Int = 50, includeFinished: Boolean = true): List<ResumeEntry>` | Последние по времени обновления. |
| `fun save(entry: ResumeEntry)` | Записать/заменить. |
| `fun delete(key: String)` / `fun clear()` | Удалить запись / всю историю (вместе с обложками). |
| `fun cover(key: String): File?` | Сохранённая обложка записи (§10.1) или `null`. |
| `val changes: StateFlow<Long>` | Счётчик изменений — для обновления списка в UI. |
| `companion fun keyFor(uri: Uri): String` | Ключ файла: для `http(s)` без query-строки (одноразовые токены не ломают продолжение), иначе `uri.toString()`. |
| `companion fun isFinished(positionMs: Long, durationMs: Long): Boolean` | Досмотрено: осталось < 30 с или пройдено > 97 %. |

```kotlin
data class ResumeEntry(
    val key: String, val uri: String, val title: String?,
    val positionMs: Long, val durationMs: Long, val finished: Boolean = false,
    val audioLanguage: String? = null, val audioLabel: String? = null,
    val textLanguage: String? = null, val textLabel: String? = null, val textDisabled: Boolean = false,
    val speed: Float = 1f, val subtitleDelayMs: Long = 0,
    val updatedAt: Long = System.currentTimeMillis(),
) { val progress: Float }   // 0..1
```

```kotlin
// «Продолжить просмотр» на главном экране
val store = Player2160.resumeStore(context)
val items = withContext(Dispatchers.IO) { store.recent(limit = 20, includeFinished = false) }
items.forEach { e -> println("${e.title}: ${(e.progress * 100).toInt()}%") }

// Прогресс конкретного файла
val entry = store.get(ResumeStore.keyFor(uri))
```

Методы обращаются к SQLite синхронно — вызывайте их с фонового потока (одиночный `get` на главном
допустим, но не рекомендуется). `ResumeStore` наследует `SQLiteOpenHelper`; его методы
(`readableDatabase`, `onCreate`…) публичны технически, но схема таблицы — не часть API.

### 10.1. Обложки (`tv.p2160.core.resume.Covers`)

Для «Продолжить просмотр» и истории плеер сохраняет обложку каждого файла — уменьшенный (~480 px) JPEG в
`filesDir/covers` по ключу записи. Через 10 с после старта (чтобы не мешать буферизации), один раз на
запись, ищется по порядку:

1. встроенная в файл — `MediaMetadata.artworkData` (обложка MP4, MP3, FLAC);
2. `MediaEntry.artworkUri` (DLNA-сервер передаёт свой `albumArtURI`, встраивающее приложение — постер);
3. картинка рядом с файлом (`file`, `smb`, `http(s)`): `<имя>.jpg`, `<имя>-poster.jpg`, `<имя>-thumb.jpg`
   (Sonarr/Jellyfin/Kodi), `<имя>.png`, `poster.jpg`, `folder.jpg`, `cover.jpg`; если файл лежит в папке сезона
   (`Season 1`, `Сезон 1`, `S01`), — ещё постер сериала папкой выше.

Не ищется для телеканалов и при `PlayerConfig.saveHistory = false`. `Covers.get(context, key)` — файл или
`null`; `Covers.changes` — `StateFlow` для перерисовки списков; `Covers.find(...)` — поиск вручную (блокирующий,
с фонового потока). Обложки удаляются вместе с записью (`ResumeStore.delete`/`clear`).

---

## 11. SMB

### 11.1. Формат URI

```
smb://<host>/<share>/<path/to/file.mkv>
```

- `host` — имя или IP (`192.168.1.10`, `nas`). Порт и `user:pass@` в URI **не поддерживаются**.
- Учётные данные берутся из сохранённых серверов `SmbServers` по `host` + `share`
  (точное совпадение share важнее совпадения только по хосту), иначе — гостевой вход.
- `SmbPath.toUri()` строит URI с корректным экранированием сегментов.

### 11.2. Сохранённые серверы

```kotlin
data class SmbServer(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val share: String,        // \\192.168.1.10\NAS → "NAS"
    val path: String = "",    // стартовая подпапка
    val username: String = "",
    val password: String = "",
    val domain: String = "",
) {
    val rootUri: Uri          // smb://host/share/path
    companion object {
        fun parseHost(raw: String): String?        // "\\nas", "smb://nas/", "192.168.1.10" → хост
        fun parseAddress(raw: String): SmbPath?   // "\\192.168.1.10\NAS\data", "//nas/NAS", "smb://nas/NAS/movies"
    }
}
```

| `SmbServers` | Описание |
|---|---|
| `companion fun get(context: Context): SmbServers` | Синглтон. |
| `val servers: StateFlow<List<SmbServer>>` | Список. |
| `fun save(server: SmbServer)` | Добавить или заменить по `id`. |
| `fun delete(id: String)` | Удалить. |
| `fun credentialsFor(host: String, share: String): SmbServer?` | Учётка для пары host/share. |

Пароли хранятся в приватных SharedPreferences приложения (`p2160_smb`) **без шифрования**.

### 11.3. Обзор папок — `SmbConnections`

Все методы **блокирующие** (сеть) — только `Dispatchers.IO`.

| Член | Описание |
|---|---|
| `fun list(context: Context, dir: SmbPath, credentials: SmbServer? = null): List<SmbEntry>` | Содержимое папки: сначала папки, затем файлы, по алфавиту; скрытые и начинающиеся с `.` пропускаются. |
| `fun listShares(host: String, credentials: SmbServer?): List<String>` | Общие дисковые папки сервера (без `C$`, `IPC$`…). |
| `fun test(context: Context, server: SmbServer)` | Проверка подключения; бросает исключение при ошибке. |
| `fun closeAll()` | Закрыть все соединения. |

`share(...)` и `openRead(...)` возвращают типы smbj (`DiskShare`, `File`), которые не входят в
API-зависимости библиотеки, — используйте их, только если сами подключили `com.hierynomus:smbj`.

```kotlin
data class SmbPath(val host: String, val share: String, val path: String) {
    val name: String; val parent: SmbPath?; val smbjPath: String
    fun child(name: String): SmbPath
    fun toUri(): Uri
    companion object { fun fromUri(uri: Uri): SmbPath? }
}
data class SmbEntry(val path: SmbPath, val isDirectory: Boolean, val size: Long, val modified: Long) { val name: String }
```

```kotlin
val servers = SmbServers.get(context)
val nas = SmbServer(name = "NAS", host = "192.168.1.10", share = "Movies", username = "user", password = "secret")
withContext(Dispatchers.IO) { SmbConnections.test(context, nas) }   // бросит исключение при ошибке
servers.save(nas)

val shares = withContext(Dispatchers.IO) { SmbConnections.listShares(nas.host, nas) }
val files = withContext(Dispatchers.IO) {
    SmbConnections.list(context, SmbPath(nas.host, nas.share, "Series/Show"))
}
val videos = files.filter { !it.isDirectory && it.name.endsWith(".mkv", ignoreCase = true) }
Player2160.play(
    context,
    PlaybackRequest(videos.map { MediaEntry(it.path.toUri(), it.name.substringBeforeLast('.')) }),
)
```

Субтитры рядом с видео на SMB (`Show.S01E02.srt`, `Show.S01E02.ru.ass`) подключаются автоматически.

---

## 12. Главы и пропускаемые отрезки

```kotlin
enum class SegmentType { INTRO, RECAP, CREDITS, PREVIEW }

data class SkipSegment(val type: SegmentType, val startMs: Long, val endMs: Long?) {   // endMs = null — до конца файла
    fun contains(positionMs: Long, durationMs: Long): Boolean
    companion object {
        fun parseList(raw: String?): List<SkipSegment>
        fun formatList(segments: List<SkipSegment>): String
    }
}

data class Chapter(val title: String?, val startMs: Long, val endMs: Long)
```

**Формат строки отрезков** (`SkipSegment.parseList`, extra `tv.p2160.extra.SEGMENTS`):

```
<тип>:<начало_мс>-[<конец_мс>]   через ';' или ','
intro:0-90000;recap:90000-150000;credits:1320000-
```

Тип — без учёта регистра (`intro`, `recap`, `credits`, `preview`). Неизвестные типы, нечисловые
значения и отрезки с концом ≤ начала молча отбрасываются. `formatList` выдаёт обратный формат
(`intro:0-90000;credits:1320000-`).

**Откуда берутся отрезки (по приоритету)**

1. Ручные отметки пользователя (`markIntroStart/End`, `markCreditsStart`) — заменяют отрезки того же типа. Для файлов вида `Show.S01E02`, `Show 1x02`, `Show - 05` сохраняются на весь сериал (`SegmentDetector.seriesKey`); титры хранятся «от конца».
2. `MediaEntry.segments` / extras Intent'а.
3. Главы с говорящими названиями (`Intro`, `Opening`, `OP`, `Credits`, `Ending`, `Recap`, `Preview`, `Вступление`, `Титры`, …) — `SegmentDetector.fromChapters`.

Через Intent: `tv.p2160.extra.SEGMENTS` (строка), либо по отдельности `INTRO_START` (по умолчанию 0)
+ `INTRO_END` и `CREDITS_START` (Int/Long, мс) — они добавляются к разобранным из строки.

**Главы** читаются из контейнера через FFmpeg (MKV, MP4 и др.; для SMB — через временный
дескриптор), для Blu-ray — из плейлиста диска. Доступны в `PlayerUiState.chapters`; управление —
`nextChapter()`, `previousChapter()`, `seekToChapter()`. Кнопки «следующий/предыдущий» пульта
при одном файле переключают главы.

Вспомогательные публичные объекты: `SegmentDetector` (`typeOfChapter`, `fromChapters`, `seriesKey`),
`ManualMarks` (`introStartMs`, `introEndMs`, `creditsFromEndMs`, `isEmpty`, `toSegments(durationMs)`),
`TimeInput.parse(raw: String): Long?` («1:23:45», «12:30», «90», «1230» → мс).

---

## 13. Локализация

### 13.1. Формат языкового пакета

```json
{
  "_meta": { "code": "uk", "name": "Українська", "author": "Ім'я" },
  "player.back": "Назад",
  "player.resumed": "Продовжено з %1$s"
}
```

- Плоский объект `ключ → строка`; плейсхолдеры — `String.format` (`%1$s`, `%2$d`, `%1$.1f`).
- `_meta.code` — код языка (`[a-z]{2,3}(-[a-z0-9]{2,8})?`), `name` — название в списке.
- Цепочка отката: выбранный пакет → английский (`en`) → сам ключ.

### 13.2. Встроенные пакеты: `assets/i18n/<модуль>/<код>.json`

Каждый модуль кладёт свои строки в свою подпапку; при загрузке все файлы одного языка объединяются.
Библиотека использует `assets/i18n/core/`, приложение 2160 Player — `assets/i18n/app/`.

Чтобы добавить строки или язык в своём приложении:

```
app/src/main/assets/i18n/myapp/en.json   ← ваши ключи (и подписи PlayerAction.label)
app/src/main/assets/i18n/myapp/ru.json
app/src/main/assets/i18n/myapp/de.json   ← новый язык: переведите здесь и ключи ядра
```

Не используйте имя папки `core` — при слиянии assets файл приложения заменит файл библиотеки
целиком. Полный список ключей ядра — `player-core/src/main/assets/i18n/core/en.json`
или `I18n.exportTemplate()`.

### 13.3. `I18n`

| Член | Описание |
|---|---|
| `companion fun get(context: Context): I18n` | Синглтон. |
| `companion const val SYSTEM = "system"`, `FALLBACK = "en"` | Язык системы / запасной. |
| `val strings: StateFlow<Strings>` / `val current: Strings` | Текущие строки. |
| `fun available(): List<LanguagePack>` | Встроенные и пользовательские пакеты. |
| `fun apply(code: String)` | Применить язык (обычно через `PlayerSettings.update { it.copy(language = code) }`). |
| `fun import(uri: Uri): LanguagePack` | Импорт пользовательского пакета в `filesDir/i18n/<code>.json` (перекрывает встроенный). Бросает `IllegalArgumentException` с описанием проблемы. |
| `fun removeUserPack(code: String)` | Удалить пользовательский пакет. |
| `fun exportTemplate(baseCode: String = FALLBACK): String` | JSON-шаблон для переводчика со всеми ключами. |

`Strings`: `operator fun get(key: String): String`, `fun format(key: String, vararg args: Any?): String`,
`val locale: Locale`. В Compose: `LocalStrings`, `@Composable fun tr(key: String, vararg args: Any?): String`.
`data class LanguagePack(val code: String, val name: String, val author: String?, val builtIn: Boolean)`.

```kotlin
val i18n = I18n.get(context)
val pack = i18n.import(uri)                                     // из ActivityResultContracts.OpenDocument
Player2160.settings(context).update { it.copy(language = pack.code) }
val template = i18n.exportTemplate()                            // записать через CreateDocument
```

Строки становятся доступны после первого обращения к `PlayerSettings.get()` (он применяет
сохранённый язык). Если используете `I18n` до плеера, вызовите `Player2160.settings(context)` при старте.

---

## 14. Blu-ray: ISO и BDMV

Ничего специального не нужно — передайте URI образа или папки:

```kotlin
Player2160.play(context, PlaybackRequest.single(Uri.parse("smb://nas/Movies/Film.iso")))
Player2160.play(context, PlaybackRequest.single(Uri.fromFile(File("/storage/emulated/0/Movies/Film/BDMV"))))
```

| Источник | ISO | Папка (корень диска или `BDMV`) |
|---|---|---|
| `file://` | да | да |
| `smb://` | да | да |
| `http(s)://` | да (Range-запросы, заголовки из запроса) | нет |
| `content://` | да, если путь заканчивается на `.iso` | нет |

Кандидатом на диск считается URI, оканчивающийся на `.iso` или `BDMV`, а также `file://`/`smb://`
без точки в последнем сегменте (папка). Выбирается основной фильм (самый длинный плейлист с
защитой от плейлистов-обманок), главы — из плейлиста, языки дорожек — из таблицы STN, название —
из метаданных диска (если `title` не задан). Меню диска, BD-J, 3D и шифрование AACS/BD+ не
поддерживаются — образ должен быть расшифрован.

Отдельные файлы `.m2ts`/`.mts` играются как обычное видео через собственный экстрактор
(TrueHD, LPCM, DTS-HD, PGS).

---

## 15. Потоки, жизненный цикл, ошибки, ограничения

### 15.1. Потоки

| API | Поток |
|---|---|
| `PlayerController` (создание и все методы), `PlayerScreen` | Главный |
| `Player2160.play/intent`, `registerAction`, `nowPlaying` | Любой (`play` — из UI-контекста) |
| `PlayerSettings.update` | Любой (запись в prefs асинхронная) |
| `ResumeStore.*` | Желательно фоновый (SQLite) |
| `SmbConnections.*` | **Только фоновый** (сеть) |
| `DiscSessions.open` | Только фоновый (внутреннее) |
| `I18n.import/exportTemplate/available/apply` | Желательно фоновый (файлы и assets) |
| `ContentDirectory.browse/browseAll`, `DlnaProbe.resolve`, `DeviceDescriptionParser.load` | **Только фоновый** (сеть, блокирующие) |
| `DlnaServers.refresh`, `addByAddress` (suspend), `DlnaDiscovery.events` | Любой (сами уходят на `Dispatchers.IO`) |
| `IptvStore.channels/guide` (suspend) | Любой (сами уходят на `Dispatchers.IO`) |
| `TorrentEngine.addMagnet/addFromUri/prepare/awaitFiles/remove` (suspend) | Любой (сами уходят на `Dispatchers.IO`) |
| `IntroDetector.detect` (suspend) | Любой (сам уходит на `Dispatchers.Default`/`IO`) |

### 15.2. Ошибки

- Ошибки воспроизведения не бросаются наружу: они приходят в `PlayerUiState.error` (локализованный
  текст: сеть, HTTP, файл не найден, нет доступа, контейнер, декодер, DRM, иначе код ошибки),
  `PlayerScreen` показывает их с кнопками «Повторить»/«Закрыть». Подробности — через
  `controller.player.addListener(object : Player.Listener { override fun onPlayerError(e: PlaybackException) … })`.
- Выпадение из live-окна (`BEHIND_LIVE_WINDOW`) обрабатывается автоматически.
- `PlaybackRequest(items = emptyList())` → `IllegalArgumentException`.
- `Player2160Activity` без `data` в Intent сразу закрывается.
- Если диск не удалось открыть, URI играется как обычный файл (и, скорее всего, даст ошибку контейнера).
- `SmbConnections.*` бросают исключения smbj (`SMBApiException`, `IOException`…) — оборачивайте в `runCatching`.
- `I18n.import` бросает `IllegalArgumentException` с понятным текстом.

### 15.3. Ограничения и известные особенности

- `nowPlaying` после закрытия плеера не становится `null` (остаётся снимок с `isPlaying = false`).
- Внешние субтитры из Intent, `Intent.type`, отрезки и автопоиск субтитров рядом с файлом относятся только к стартовому элементу плейлиста (через `PlaybackRequest` можно задать субтитры и отрезки для каждого `MediaEntry`, но автопоиск — всё равно только для стартового).
- Результат возвращается только при закрытии через «Назад»; позиция в результате MX-формата — `Int`.
- Свою тему в `PlayerScreen` не добавить: `PlayerThemes.all` — фиксированный список.
- `decoder`, предпочтительные языки, `autoPlayNext` применяются к следующему `PlayerController`.
- SMB: учётные данные только через `SmbServers` (не из URI), пароли не шифруются; в intent-фильтрах приложения 2160 Player нет схемы `smb`.
- Blu-ray: без меню, BD-J, 3D, AACS/BD+; папки BDMV — только `file://` и `smb://`.
- Синглтоны (`PlayerSettings`, `ResumeStore`, `SmbServers`, `I18n`) и `PlayerExtensions` — общие на процесс; их SharedPreferences/БД имеют префикс `p2160_` и живут в данных вашего приложения.
- `PlayerScreen` не принимает `Modifier` и всегда заполняет родителя — ограничивайте размер контейнером.
- Работа с несколькими `PlayerController` одновременно технически возможна, но `nowPlaying` будет показывать последний обновивший.
- Ночной звук работает только с PCM: пока он включён, passthrough на ресивер отключён (AC-3/DTS/TrueHD декодируются на устройстве).
- `TrackPreferences`, `IntroDetector`, `IptvStore`, `DlnaServers`, `TorrentEngine` — тоже синглтоны процесса (`p2160_track_prefs`, `filesDir/intro`, `p2160_iptv` + `filesDir/iptv`, `p2160_dlna`, `filesDir/torrents`).

### 15.4. Фоновое воспроизведение и MediaSession

Каждый `PlayerController` при создании регистрирует `MediaSession` (Media3) и запускает
`PlaybackService`. Это даёт:

- уведомление с управлением, карточку на экране блокировки, кнопки гарнитуры, Bluetooth и часов;
- фоновое воспроизведение: при уходе `Player2160Activity` с экрана (кнопка «Домой», выключение экрана,
  переход в другое приложение, закрытие окна PiP) аудио без видео продолжает играть, если
  `Settings.backgroundAudio = true` (по умолчанию), видео — если `Settings.backgroundPlayback = true`;
  иначе пауза. При «Домой» во время видео сначала открывается PiP, если `Settings.pictureInPicture = true`.
  В фоне видеодорожка отключается (`PlayerController.setInBackground(true)`), при возврате включается снова;
- нажатие на уведомление открывает `Player2160Activity`.

Одновременно активна одна сессия: новый контроллер заменяет предыдущую, `release()` её закрывает и
останавливает сервис. Встраивая `PlayerScreen` в свою Activity, вызывайте
`controller.setInBackground(true/false)` и `player.pause()` в `onStop`/`onStart` по тем же правилам.

### 15.5. Dolby Vision без декодера

Если устройство не умеет Dolby Vision файла (чаще всего профиль 7 — UHD Blu-ray-ремуксы; S21 и
большинство телефонов), Media3 отбросила бы видеодорожку. Плеер подменяет формат на совместимый
базовый кодек (HEVC/AVC/AV1) прямо в экстракторе — играет базовый слой HDR10, а `report()` показывает
«Dolby Vision (profile N)» с предупреждением. Профиль 5 (без совместимого слоя) не подменяется.

### 15.6. Настройка движка: `PlayerConfig`

То, что пользователь в настройках не меняет, а встраивающему приложению может понадобиться.
Задаётся глобально до создания плеера — `Player2160.config = PlayerConfig(...)` — или передаётся
в конструктор `PlayerController(context, request, config)`.

| Поле | По умолчанию | Что делает |
|---|---|---|
| `bufferTargetBytes` | `PlayerConfig.AUTO` | Предел буфера в байтах. Без него 4K-ремукс держит ~130 МБ — слабые ТВ падают от нехватки памяти. `AUTO` — по памяти устройства (`DeviceProfile.bufferTargetBytes`: четверть кучи Java, но не больше 1/24 всей памяти; 24–128 МБ, на low-RAM — до 32 МБ), `UNLIMITED` — правило Media3, число — свой предел. |
| `minBufferMs` / `maxBufferMs` | `50 000` / `50 000` | Сколько держать в буфере (пока позволяет предел в байтах). |
| `bufferForPlaybackMs` / `bufferForPlaybackAfterRebufferMs` | `1 000` / `2 000` | Сколько набрать перед стартом и после подгрузки. |
| `connectTimeoutMs` / `readTimeoutMs` | `30 000` / `60 000` | Тайм-ауты HTTP(S). У торрентов свой тайм-аут ожидания частей (`TorrentPrefs`). |
| `introDetection` | `true` | Поиск вступления/титров по звуку: читает начало соседних серий по сети. |
| `readChapters` | `true` | Читать главы файла через FFmpeg при старте (отдельное открытие файла). Главы дисков Blu-ray читаются всегда. |
| `restoreFromHistory` | `true` | Брать из истории позицию, дорожки, скорость, задержку субтитров и выбор дорожек «по привычке». `false` — плеер сам дорожки не подменяет; явные `startPositionMs` и `select` у субтитров работают всегда. |
| `saveHistory` | `true` | Записывать прогресс в `ResumeStore`. |

```kotlin
// Приложение со своим медиасервером, своей историей и слабыми ТВ-приставками
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Player2160.config = PlayerConfig(
            bufferTargetBytes = 32 * 1024 * 1024,
            readTimeoutMs = 90_000,
            introDetection = false,      // отрезки даёт сервер (PlaybackRequest.segments)
            readChapters = false,
            restoreFromHistory = false,  // позицию и дорожки выбирает приложение
            saveHistory = false,
        )
    }
}
```

### 15.7. Производительность: буфер, частота экрана, статистика

Что движок делает для меньшей нагрузки на процессор, память и батарею:

| Механизм | Как работает |
|---|---|
| Вывод видео | `SurfaceView` (`PlayerView`): кадры идут в аппаратный композитор без копий через GPU; HDR — сразу на дисплей. |
| Декодеры | Аппаратные `MediaCodec` в приоритете, FFmpeg (nextlib) — только для того, что устройство не умеет. Программное декодирование видео видно в сводке и предупреждении при старте. |
| Динамическое планирование | `ExoPlayer.Builder.experimentalSetDynamicSchedulingEnabled(true)`: цикл воспроизведения просыпается, только когда рендерерам есть работа. |
| Буфер по памяти | `PlayerConfig.AUTO` → `DeviceProfile.bufferTargetBytes(context)`. Приложение 2160 Player объявляет `android:largeHeap="true"`, поэтому на телефонах с большой памятью буфер — до 128 МБ. |
| Видео в фоне | Видеодорожка отключается (`setInBackground`), звук без видео может уйти в offload. |
| Аудио offload | `Settings.audioOffload`: `AudioOffloadPreferences` (`AUDIO_OFFLOAD_MODE_ENABLED`, без требования смены скорости — при смене скорости Media3 сам выходит из offload). Media3 включает offload только для звука без видео и для форматов, которые умеет отдавать на аудиочип (MP3, AAC, Opus, AC3/E-AC3/DTS; FLAC — нет, он декодируется аппаратным или программным декодером); ночной звук (обработка PCM) его выключает. |
| Туннельный режим | `Settings.tunneling` → `DefaultTrackSelector.Parameters.setTunnelingEnabled`. Устройства без поддержки остаются в обычном режиме; ошибка декодера в туннеле — режим выключается и сохраняется. |
| Передача звука на ресивер | Если выход не открылся (`AUDIO_TRACK_INIT_FAILED`, Realtek: `createTrack -38`), плеер повторяет до 3 раз через 2 с, а во время смены режима экрана — без счёта; потом декодирует сам. |

`object DeviceProfile` (`tv.p2160.core.api`): `lowMemory(context)` — low-RAM или < 2 ГБ памяти; `totalMemoryMb(context)`;
`heapLimitMb(context)` — предел кучи Java с учётом `android:largeHeap`; `isTv(context)`; `bufferTargetBytes(context)` —
min(куча/4, ОЗУ/24) в пределах 24–128 МБ, на слабых устройствах ≤ 32 МБ. Им пользуются плеер (`PlayerConfig.AUTO`) и
модуль торрентов (лимиты для слабых устройств).

**Частота экрана под видео** (`Settings.frameRateMatching`, `FrameRateMatcher`). Пока плеер на экране, окно
просит режим дисплея с тем же разрешением и частотой, кратной частоте кадров (точное совпадение важнее
кратности: для 23,976 — 23,976 или 47,952 Гц, иначе 24 Гц). На время переключения (HDMI пересинхронизируется
1–3 с) воспроизведение на паузе. Если частоты нет в заголовке (часто MKV), она вычисляется по меткам первых
48 кадров (`VideoFrameMetadataListener`) и приводится к стандартной. При выходе из плеера — режим по умолчанию.
`FrameRateMatcher.bestMode(modes, current, fps)` и `suspend apply(activity, player, fps, onSwitching = controller::onDisplaySwitching)` можно использовать и в своём
экране. Media3 дополнительно сообщает частоту поверхности (`Surface.setFrameRate`, только бесшовно) — некоторые
ТВ (например, Dune) по ней переключаются сами.

**Статистика** (`Settings.statsOverlay`, `PlayerController.stats`). `PlaybackStats`: видео- и аудиодекодер
(аппаратный/программный), разрешение, кодек, частота кадров, показано/пропущено кадров, буфер (секунды, байты и
предел), оценка скорости сети, passthrough/offload/туннель, загрузка процессора процессом (% одного ядра, из
`/proc/self/stat`), память (PSS, куча Java, нативная куча). Сбор — раз в секунду (память — раз в 2 с) и только
пока слой включён.

**Замеры (Dune TV, 32-битный ARM, release-сборки, 60 с после 40 с прогрева).** Ремукс 1080p H.264 + DTS-HD 5.1
на ресивер: процесс плеера ~22 % одного ядра (из 6), PSS ~140–160 МБ (у новой версии буфер больше — 96 МБ против
64 МБ). Тест 720p: ~20 %. Если передача на ресивер срывается и DTS-HD декодируется программно — ~50 % ядра и
пропуски кадров на 32-битных ТВ; поэтому повторы открытия выхода важны. Туннельный режим на этом ТВ не дал
выигрыша (декодер Realtek его не поддерживает). Отладочные сборки в 2–3 раза тяжелее релизных — мерить нужно release.

---

## 16. Ночной звук

«Ночной звук»: громкие сцены тише, диалоги громче. Реализован как `NightAudioProcessor`
(`tv.p2160.core.engine`, Media3 `AudioProcessor`), который всегда стоит в аудиоцепочке
`PlayerController` и включается на лету, без пересоздания плеера.

Обработка (PCM 16 бит):

1. Выделение диалогов: в 5.1/7.1 центральный канал ×1.6, LFE ×0.4, остальные ×0.75; в стерео
   усиливается «середина» (M/S: mid ×1.25, side ×0.7).
2. Компрессор по пику кадра: порог −30 dBFS, 4:1, атака 5 мс, спад 300 мс, компенсация +9 дБ.
3. Мягкий лимитер у ≈ −1 dBFS.

Пока ночной режим включён, многоканальный звук дополнительно сводится в стерео
(`NightAudioProcessor.downmixToStereo`): ночью звук обычно идёт в динамики ТВ или наушники, а многие
ТВ не принимают 6-канальный PCM. После выключения многоканал возвращается.

**Как включить**

| Способ | Что происходит |
|---|---|
| `Settings.nightMode = true` | Включён всегда, начиная со следующего `PlayerController`. |
| `Settings.nightAuto = true` + `nightStartMinute`/`nightEndMinute` | По расписанию (по умолчанию 23:00–10:00). Контроллер проверяет расписание при старте и раз в минуту и переключает режим на лету. |
| `controller.setNightMode(enabled)` | Ручное переключение текущего сеанса (так работает пункт в панели аудио `PlayerScreen`). Расписание больше не трогает этот контроллер, пока не вызван `onNightScheduleChanged()`. `Settings` не меняется. |

```kotlin
// Расписание 22:30–08:00 и немедленное применение в открытом плеере
Player2160.settings(context).update { it.copy(nightAuto = true, nightStartMinute = 22 * 60 + 30, nightEndMinute = 8 * 60) }
controller.onNightScheduleChanged()

// Состояние — в PlayerUiState
val night = controller.state.value.nightMode
```

**Passthrough.** Сжатый звук (AC-3/E-AC-3/DTS/TrueHD), уходящий на ресивер «как есть», обработать
нельзя. Поэтому `PlayerFactory` оборачивает `AudioSink` в `GuardedAudioSink` с флагом
`PassthroughGuard.disabled`: пока флаг поднят, сжатые форматы объявляются неподдерживаемыми и
рендерер выбирает декодер (системный или FFmpeg). Флаг поднимается:

- при старте, если ночной режим активен;
- при включении ночного режима на лету (вручную или по расписанию);
- автоматически при ошибке `PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED`: ТВ заявил
  поддержку AC-3/DTS «на выход», но открыть такой `AudioTrack` не смог. Первую такую ошибку контроллер
  считает кратковременной (Realtek сразу после переключения звука: прежний выход ещё не закрыт,
  `createTrack -38`) — через секунду повторяет `prepare()` с прежним `playWhenReady`; повтор — не чаще
  раза в 10 с, так что срабатывает при любом переключении, в том числе при смене дорожки напрямую через
  `player.trackSelectionParameters`. Если повтор тоже не удался, контроллер переключается на
  декодирование и вызывает `prepare()`/`play()` — пользователь ошибки не видит. Такой выход
  запоминается (`PassthroughGuard.failed`), и passthrough для этого контроллера больше не включается.
  Цена для ТВ, который совсем не умеет passthrough, — одна лишняя секунда тишины.

Выключение ночного режима опускает флаг — AC-3/E-AC-3/DTS снова уходят на ресивер многоканалом, в том числе если серия была запущена сразу с ночным звуком (звук с самого начала декодировался).
При любом переключении аудиорендерер переинициализируется (дорожка звука кратковременно выключается
и включается, меньше секунды); видео не трогается.

---

## 17. Умный выбор дорожек

Если у файла нет своей сохранённой записи в истории (`ResumeStore`) и `Settings.smartTracks = true`,
`PlayerController` выбирает аудио и субтитры «по привычке», а ручной выбор пользователя
(`select()` аудио/текста, `disableSubtitles()`) запоминает. Сохранённая позиция файла (§10) всегда
важнее привычки; явно запрошенные внешние субтитры (`ExternalSubtitle.select`) — тоже.

Привычка хранится под двумя ключами одновременно:

| Ключ | Пример | Откуда |
|---|---|---|
| Сериал | `series:<ключ>` | `SegmentDetector.seriesKey` от имени файла или заголовка (как у ручных отметок §12). Точнее всего. |
| Набор языков аудио файла | `ctx:en+ja+ru` | `TrackRules.contextKey` — отсортированные нормализованные языки аудиодорожек. «ja+ru → японская озвучка + русские субтитры». |

При выборе сначала ищется привычка сериала, затем точный набор языков, затем **похожий** набор:
коэффициент Жаккара `|A∩B| / |A∪B|` ≥ 0,5, и выбранный язык озвучки должен присутствовать в файле
(`en+ru` подходит к `en+ru+uk`). Применённый выбор показывается подсказкой `PlayerUiState.smartHint`.

```kotlin
/** Что пользователь выбрал. */
data class TrackChoice(
    val audioLanguage: String?,
    val audioHint: String?,       // "dub", "mvo", "dvo", "avo", "original", "commentary" — по названию дорожки
    val textLanguage: String?,    // null — субтитры выключены
    val textForced: Boolean = false,
)

/** Дорожка для правил (без Media3). */
data class TrackCandidate(val index: Int, val language: String?, val label: String?, val channels: Int = 0, val forced: Boolean = false)
```

`object TrackRules` — чистая логика (тестируется на JVM):

| Член | Описание |
|---|---|
| `fun contextKey(audio: List<TrackCandidate>): String?` | `"en+ja+ru"`; `null`, если языков нет. |
| `fun normalizeLang(code: String?): String?` | `"rus"` → `"ru"`, `"en-US"` → `"en"`; `und` и пустое → `null`. |
| `fun hintOf(label: String?): String?` | Тип озвучки по названию (`Дубляж`, `MVO`, `Оригинал`, `Комментарий`…). |
| `fun pickAudio(choice, audio): TrackCandidate?` | Тот же язык → тот же тип озвучки → не комментарий → больше каналов. |
| `fun pickText(choice, text): TrackCandidate?` | Тот же язык, предпочтительно с тем же флагом forced. |
| `fun bestContext(stored: Map<String, TrackChoice>, languages: Set<String>): TrackChoice?` | Самая похожая привычка (Жаккар ≥ 0,5). |

`class TrackPreferences` — хранилище (SharedPreferences `p2160_track_prefs`, JSON):

| Член | Описание |
|---|---|
| `companion fun get(context: Context): TrackPreferences` | Синглтон. |
| `fun forSeries(seriesKey: String?): TrackChoice?` | Привычка сериала. |
| `fun forContext(contextKey: String?): TrackChoice?` | Точная или похожая привычка для набора языков. |
| `fun remember(seriesKey: String?, contextKey: String?, choice: TrackChoice)` | Записать под обоими ключами. |
| `fun clear()` | Забыть все привычки (пункт «Забыть привычки выбора дорожек» в настройках приложения). |

```kotlin
TrackPreferences.get(context).clear()                         // сбросить
Player2160.settings(context).update { it.copy(smartTracks = false) }  // выключить
```

---

## 18. Поиск вступлений по звуку и сводка по файлу

### 18.1. Автоопределение вступления и титров

Для файлов сериала (есть `SegmentDetector.seriesKey`) `PlayerController` через ~15 с после старта
сравнивает звук начала текущей серии (10 мин) с началом 1–2 соседних элементов плейлиста того же
сериала, а конец (6 мин) — с их концами. Общий отрезок 15–150 с в начале — вступление, у конца —
титры. Найденные отрезки добавляются к `PlayerUiState.segments` с наименьшим приоритетом (после
ручных отметок, `MediaEntry.segments`/Intent и глав, §12); отрезки с уверенностью < 0,6 показываются
только кнопкой — в режиме `SkipMode.AUTO` они не пропускаются. Поиск не запускается для Blu-ray,
`liveTv` и если вступление и титры уже известны.

Отпечатки, результаты и шаблоны сериала кэшируются в `filesDir/intro`: для следующей серии обычно
достаточно звука её самой. Звук читается через `MediaExtractor`, который для сетевых источников тянет
весь поток за нужный отрезок, — на 4K-ремуксе по SMB/HTTP это гигабайты трафика.

Публичная точка входа (`tv.p2160.core.intro`), если нужно искать отрезки самостоятельно:

```kotlin
class IntroDetector {
    suspend fun detect(
        current: Uri,
        siblings: List<Uri>,                      // лучше всего предыдущая и следующая серии
        headers: Map<String, String> = emptyMap(),
        seriesKey: String? = null,                // SegmentDetector.seriesKey — для шаблонов сериала
        durationMs: Long = -1,
        preferredLanguage: String? = null,        // язык озвучки, чтобы сравнивать одну и ту же
    ): DetectionResult
    fun cached(current: Uri): DetectionResult?    // без декодирования; null — ещё не искали
    companion object {
        fun get(context: Context): IntroDetector
        fun keyFor(uri: Uri): String
        fun <T> pickSiblings(items: List<T>, index: Int, max: Int = 2): List<T>
    }
}

data class DetectionResult(
    val intro: SkipSegment? = null,
    val credits: SkipSegment? = null,             // endMs = null — титры до конца файла
    val introConfidence: Float = 0f,              // 0…1; для автопропуска разумно ≥ 0.6
    val creditsConfidence: Float = 0f,
) { val segments: List<SkipSegment> }
```

```kotlin
val result = IntroDetector.get(context).detect(episodes[1], listOf(episodes[0], episodes[2]), seriesKey = "show")
val segments = result.segments   // можно передать в MediaEntry.segments
```

Остальные классы пакета (`IntroAnalyzer`, `Fingerprinter`, `IntroMatcher`, `IntroStore`,
`MediaCodecPcmDecoder`) — детали реализации.

### 18.2. Сводка по файлу — `MediaReport`

`controller.report()` собирает «что внутри и как это играет»: контейнер, длительность, видео
(кодек и профиль, разрешение, fps, битрейт, HDR/Dolby Vision), выбранный декодер (аппаратный или
программный — по реально инициализированному), аудиодорожки с пометкой, как играет каждая
(passthrough / аппаратно / программно / FFmpeg / не поддерживается), субтитры (встроенные/внешние).
В `PlayerScreen` это боковая панель с информацией о файле.

```kotlin
data class MediaReport(val sections: List<ReportSection>, val warnings: List<String>)
data class ReportSection(val title: String, val rows: List<ReportRow>)
data class ReportRow(val label: String, val value: String, val level: Level = Level.INFO) {
    enum class Level { INFO, OK, WARN }
}
```

Строки локализованы. `warnings` — то же, что появляется в `PlayerUiState.warnings`: программное
декодирование видео (отдельно для 4K), Dolby Vision без аппаратного декодера, HDR на SDR-экране.
`MediaReporter`/`ActiveDecoders` — внутренние.

---

## 19. DLNA/UPnP

Пакет `tv.p2160.core.source.dlna`. Поиск медиасерверов (Jellyfin/Emby, Plex, MiniDLNA, Synology,
Kodi, UMS, Serviio…), обзор их каталогов через ContentDirectory и воспроизведение элементов
обычным HTTP-путём плеера. Своей схемы URI нет: играется прямая ссылка `<res>` сервера.

### 19.1. Серверы — `DlnaServers`

| Член | Описание |
|---|---|
| `companion fun get(context: Context): DlnaServers` | Синглтон. |
| `val servers: StateFlow<List<KnownDlnaServer>>` | Найденные (по алфавиту) + добавленные вручную. `KnownDlnaServer(server: DlnaServer, manual: Boolean)`. |
| `val searching: StateFlow<Boolean>` | Идёт поиск. |
| `fun refresh(timeoutMs: Long = 5000)` | Новый цикл SSDP-поиска (если уже идёт — ничего). Результаты поиска живут в памяти. |
| `fun find(udn: String): DlnaServer?` | Сервер по UDN. |
| `suspend fun addByAddress(input: String): DlnaServer` | Добавить вручную (сохраняется в `p2160_dlna`). Бросает `DlnaException`, если не найден. |
| `fun removeManual(udn: String)` | Удалить добавленный вручную. |

Поиск — M-SEARCH (`MediaServer:1`, `ContentDirectory:1`, `ssdp:all`) на `239.255.255.250:1900` по всем
IPv4-интерфейсам плюс приём NOTIFY alive/byebye; в результат попадают только устройства с сервисом
ContentDirectory. На время поиска берётся `WifiManager.MulticastLock` (разрешение
`CHANGE_WIFI_MULTICAST_STATE` добавляет библиотека). Низкоуровнево: `DlnaDiscovery.events(context, timeoutMs): Flow<DlnaDiscovery.Event>`
(`Found(server)` / `Lost(udn)`), `SsdpDiscovery`.

`addByAddress` (`DlnaProbe.resolve`) — для серверов, не видимых по SSDP (Docker в bridge-сети, другая
подсеть, VPN). Принимает URL описания (`http://nas:8200/rootDesc.xml`), `host:port` (перебираются
известные пути описаний) или только `host` (перебираются стандартные порты: 8200 MiniDLNA, 8096
Jellyfin/Emby, 32469 Plex, 5001 UMS, 50001 Synology, 8895 Serviio).

```kotlin
data class DlnaServer(
    val udn: String, val friendlyName: String, val manufacturer: String?, val modelName: String?,
    val deviceType: String, val location: String,
    val contentDirectoryControlUrl: String, val contentDirectoryType: String,
    val icons: List<DlnaIcon> = emptyList(),
) { val host: String; fun bestIcon(target: Int = 120): DlnaIcon? }
```

### 19.2. Обзор — `ContentDirectory`

Методы **блокирующие** (сеть) — только `Dispatchers.IO`. Ошибки — `DlnaException` (`IOException`, есть `code`).

| Член | Описание |
|---|---|
| `ContentDirectory(server: DlnaServer, http: DlnaHttp = UrlConnectionHttp())` | Клиент SOAP Browse (`BrowseDirectChildren`). |
| `fun browse(objectId: String, start: Int = 0, count: Int = PAGE_SIZE): BrowsePage` | Одна страница; `ROOT_ID = "0"` — корень, `PAGE_SIZE = 200`. |
| `fun browseAll(objectId: String, pageSize: Int = PAGE_SIZE, limit: Int = 10_000): List<DlnaObject>` | Все дети с постраничной загрузкой (терпит серверы с `TotalMatches=0` и игнорирующие `StartingIndex`). |

`DlnaObject` — `DlnaContainer` (папка: `id`, `title`, `childCount`, `albumArtUrl`) или `DlnaItem`
(`resources: List<DlnaResource>`, `subtitles: List<DlnaSubtitle>`, `kind: DlnaMediaKind` —
`VIDEO/AUDIO/IMAGE/OTHER`, `bestResource`, `durationMs`, `size`, `date`, `artist`, `album`).
`bestResource` — HTTP-ресурс нужного типа: сначала оригинал (без транскодирования `DLNA.ORG_CI=1`),
затем наибольшее разрешение, размер и битрейт. Внешние субтитры берутся из `<res>` с типом
субтитров и Samsung-расширения `sec:CaptionInfoEx`.

### 19.3. Воспроизведение — `DlnaPlayback`

| Член | Описание |
|---|---|
| `fun mediaEntry(item: DlnaItem): MediaEntry?` | URI = `bestResource.url`, заголовок, MIME (для HLS/DASH), внешние субтитры (имя `RU.srt` — по расширению плеер определяет формат). `null` — нет воспроизводимого ресурса. |
| `fun request(items: List<DlnaItem>, index: Int): PlaybackRequest?` | Плейлист из элементов папки (без ресурса — пропускаются), старт с `index`. |

```kotlin
val dlna = DlnaServers.get(context)
dlna.refresh()
val server = dlna.servers.first { it.isNotEmpty() }.first().server

val items = withContext(Dispatchers.IO) {
    ContentDirectory(server).browseAll(ContentDirectory.ROOT_ID)
}
val folder = items.filterIsInstance<DlnaContainer>().first()
val videos = withContext(Dispatchers.IO) { ContentDirectory(server).browseAll(folder.id) }
    .filterIsInstance<DlnaItem>().filter { it.kind == DlnaMediaKind.VIDEO }
DlnaPlayback.request(videos, 0)?.let { Player2160.play(context, it) }
```

---

## 20. IPTV: M3U, XMLTV, режим эфира

### 20.1. Режим эфира

`PlaybackRequest(liveTv = true)` (extra `tv.p2160.extra.LIVE`, §3.3) — элементы считаются
телеканалами: нет продолжения с места, истории, глав и поиска вступлений; вместо полосы перемотки —
«Эфир»; стрелки вверх/вниз при скрытой панели, CH+/CH−, `next()/previous()` переключают каналы по
кругу (`switchChannel`); под названием — `PlayerUiState.subtitle` из `LiveGuide` (обновляется раз в 30 с).

```kotlin
fun interface LiveGuide { fun describe(entry: MediaEntry, nowMs: Long): String? }   // tv.p2160.core.api

// Свой телегид; вызывается с главного потока — отвечайте из памяти
Player2160.setLiveGuide { entry, now -> myEpg.current(entry.uri, now)?.title }
Player2160.setLiveGuide(null)   // вернуть встроенный (IptvStore)
```

Тот же объект доступен как `PlayerExtensions.liveGuide`.

### 20.2. Разбор M3U — `M3uParser`

`M3uParser.parse(text: String, baseUrl: String? = null): M3uPlaylist` — терпим к реальным спискам: BOM,
CRLF, атрибуты без кавычек и с запятыми, `#EXTGRP`, `#EXTVLCOPT` (`http-user-agent`, `http-referrer`,
`http-origin`), `#KODIPROP` (`…stream_headers`/`…manifest_headers`), `#EXTHTTP` (JSON), заголовки после
`url|User-Agent=…&Referer=…`, название на следующей строке, относительные URL (относительно `baseUrl`),
повторы URL (id получают суффикс `#2`, `#3`…).

```kotlin
data class M3uPlaylist(
    val channels: List<IptvChannel>,
    val epgUrls: List<String> = emptyList(),          // url-tvg / x-tvg-url / tvg-url из #EXTM3U
    val headerAttributes: Map<String, String> = emptyMap(),
    val looksLikeHls: Boolean = false,                // это сам HLS-поток, а не список каналов
) { val groups: List<String> }

data class IptvChannel(
    val id: String, val name: String, val url: String,
    val groups: List<String> = emptyList(),           // group-title (через ';') или #EXTGRP
    val tvgId: String? = null, val tvgName: String? = null, val logo: String? = null,
    val number: Int? = null,                          // tvg-chno
    val userAgent: String? = null, val referrer: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val catchup: String? = null, val catchupDays: Int? = null, val catchupSource: String? = null,
    val attributes: Map<String, String> = emptyMap(), // все атрибуты #EXTINF (ключи в нижнем регистре)
) { val group: String?; fun httpHeaders(): Map<String, String> }
```

### 20.3. Телегид XMLTV

| Класс | Описание |
|---|---|
| `XmltvParser.parse(input: InputStream, parser: XmlPullParser, nowMs: Long, filter: EpgFilter? = null, pastMs = 6 ч, futureMs = 24 ч): EpgData` | Потоковый разбор XMLTV (gzip распознаётся автоматически). Хранит передачи в окне `[now − pastMs, now + futureMs]`, не более 300 на канал. `parser` — `android.util.Xml.newPullParser()`. |
| `EpgFilter(channels: Collection<IptvChannel>)` | Оставить только каналы плейлиста (по `tvg-id` и названиям) — экономит память на больших EPG. |
| `EpgData` | `programmes: Map<String, List<EpgProgramme>>`, `channelNames`, `isEmpty`, `plus` (слияние источников), `trimmed(fromMs)`. |
| `EpgGuide(data)` | Сопоставление каналу: `tvg-id` точно → без суффикса `@HD/@SD` → нормализованное название. `keyFor(channel)`, `programmes(channel)`, `nowNext(channel, nowMs): NowNext?`. |
| `EpgProgramme(startMs, stopMs, title, description)` | `isOn(nowMs)`, `progress(nowMs)`. `NowNext(now, next)`. |
| `EpgCodec.encode/decode` | Компактный текстовый формат кэша. |

### 20.4. Хранилище — `IptvStore`

Синглтон (`IptvStore.get(context)`): плейлисты (`p2160_iptv`), кэш каналов (`filesDir/iptv/<id>.m3u`,
исходный текст) и отфильтрованной EPG (`<id>.epg`), избранное, последний канал. Реализует
`LiveGuide`: подпись «Передача · 18:00–19:00» по URL канала (это встроенный телегид плеера).

```kotlin
data class IptvPlaylist(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val source: String,            // http(s)://…, content://…, file://…
    val epgUrl: String? = null,    // свой EPG вместо url-tvg (можно несколько через запятую)
    val userAgent: String? = null, // для плейлиста, EPG и потоков без своего UA
    val updatedAt: Long = 0, val epgUpdatedAt: Long = 0, val channelCount: Int = 0,
    val error: String? = null,     // последняя ошибка обновления; IptvStore.ERROR_EMPTY / ERROR_HLS — маркеры
) { val isLocal: Boolean }
```

| Член | Описание |
|---|---|
| `val playlists: StateFlow<List<IptvPlaylist>>` | Список; `get(id)`, `save(playlist)` (смена источника/EPG сбрасывает кэш), `delete(id)`. |
| `suspend fun channels(id, forceRefresh = false, maxAgeHours = 24): M3uPlaylist` | Из кэша, если свежий (локальный файл перечитывается только по `forceRefresh`), иначе загрузка. При ошибке сети — старый кэш и `error`; без кэша — исключение. |
| `suspend fun guide(id, forceRefresh = false): EpgGuide?` | EPG с диска, если моложе 12 ч и покрывает ≥ 3 ч вперёд, иначе загрузка всех источников. `null` — источников нет. |
| `fun cachedChannels(id)` / `fun cachedGuide(id)` / `val guideVersion: StateFlow<Int>` | Без загрузки; счётчик обновлений EPG. |
| `fun epgUrls(playlist, parsed): List<String>` | Свой `epgUrl` или адреса из `#EXTM3U`. |
| `favourites: StateFlow<Map<String, Set<String>>>`, `isFavourite`, `toggleFavourite(playlistId, channelId)` | Избранное. |
| `lastChannel(playlistId)`, `setLastChannel(...)`, `lastPlaylist()`, `lastChannelChanges` | Последний канал (обновляется и при переключении каналов в плеере). |
| `fun playbackRequest(playlistId: String, channels: List<IptvChannel>, index: Int): PlaybackRequest` | `liveTv = true`, элементы — каналы (не более `IptvPlayback.MAX_ITEMS = 1000` вокруг `index` — лимит Binder), заголовки — `IptvPlayback.headersFor(channel, playlist.userAgent)`; включает подпись EPG в плеере. |

```kotlin
val iptv = IptvStore.get(context)
val playlist = IptvPlaylist(name = "Дом", source = "https://example.com/tv.m3u")
iptv.save(playlist)
val parsed = iptv.channels(playlist.id)
val news = parsed.channels.filter { "Новости" in it.groups }
Player2160.play(context, iptv.playbackRequest(playlist.id, news, index = 0))
launch { iptv.guide(playlist.id) }   // подгрузить телегид для подписи «сейчас»
```

Без `IptvStore` достаточно `PlaybackRequest(items, liveTv = true, headers = …)` — подписи не будет
(или будет из вашего `LiveGuide`). Ограничение: заголовки запроса общие на весь плейлист — берутся у
стартового канала.

---

## 21. Торренты: модуль `source-torrent`

Отдельный Android-модуль `:source-torrent` (пакет `tv.p2160.torrent`) на libtorrent4j: нативные
библиотеки добавляют ~6–8 МБ на ABI, поэтому в `player-core` их нет. Плеер читает файлы торрента
по схеме `torrent://` — через точку расширения `RoutingDataSource.registerScheme`, так что
torrent-URI работают в `PlaybackRequest`, `PlayerScreen`, истории и «Продолжить просмотр».

**Подключение.** В Maven модуль не публикуется — только исходниками (как вариант 1a/1b в
[EMBEDDING.md](EMBEDDING.md#1-подключение)):

```kotlin
// settings.gradle.kts
include(":player-core", ":source-torrent")
// или includeBuild("../2160player") { dependencySubstitution {
//     substitute(module("tv.p2160:player-core")).using(project(":player-core"))
//     substitute(module("tv.p2160:source-torrent")).using(project(":source-torrent"))
// } }

// app/build.gradle.kts
dependencies {
    implementation(project(":player-core"))
    implementation(project(":source-torrent"))   // тянет org.libtorrent4j:libtorrent4j(-android-*) 2.1.0-39 из Maven Central
}
```

ProGuard-правила (`-keep class org.libtorrent4j.** { *; }`) подключаются автоматически.
Разрешений сверх `INTERNET` не нужно; данные хранятся в `getExternalFilesDir(null)/torrent-data`
(или `filesDir`), индекс — в `filesDir/torrents`.

**Формат URI**

```
torrent://<info-hash, 40 hex в нижнем регистре>/<индекс файла>/<имя файла>
```

Имя — только для отображения (заголовок, расширение для определения формата и субтитров).
`TorrentEngine.uriFor(id, file)` строит URI, `TorrentEngine.parseUri(uri): Pair<String, Int>?` — разбирает.

**Воспроизведение magnet**

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        TorrentEngine.install(this)   // регистрирует torrent:// в плеере; libtorrent не запускает
    }
}

lifecycleScope.launch {
    val engine = TorrentEngine.get(context)
    val id = engine.addMagnet("magnet:?xt=urn:btih:…")              // или голый info-hash
    val files = engine.awaitFiles(id, timeoutMs = 60_000) ?: return@launch   // null — нет метаданных
    val video = files.filter { it.isVideo }.maxBy { it.size }
    Player2160.play(context, engine.prepare(id, video.index))
}
```

`prepare` выбирает файл (остальные перестают качаться) и возвращает `PlaybackRequest`: все видео
торрента плейлистом в естественном порядке (`S01E2` < `S01E10`), старт — с выбранного; субтитры из
торрента подключаются к видео с тем же базовым именем. Чтение блокируется, пока нужные куски не
скачаны; приоритеты кусков выставляются по позициям всех открытых потоков (перемотка работает).

`class TorrentEngine` (синглтон, `get(context)`):

| Член | Описание |
|---|---|
| `companion fun install(context: Context)` | Зарегистрировать схему `torrent://` (вызывать в `Application.onCreate`). |
| `companion const val SCHEME = "torrent"`, `IDLE_PAUSE_MS` (10 мин) | Торрент без чтения дольше `IDLE_PAUSE_MS` ставится на паузу. |
| `suspend fun addMagnet(text: String): String` | magnet или голый хеш (40 hex / 32 base32) → id. `IllegalArgumentException`, если не magnet. |
| `suspend fun addTorrentBytes(bytes: ByteArray): String` / `suspend fun addFromUri(uri: Uri): String` | `.torrent` (`IllegalArgumentException`, если не bencode); `addFromUri` понимает `magnet:`, `content://`, `file://`, `http(s)://` (файл до 16 МБ). |
| `suspend fun awaitFiles(id: String, timeoutMs: Long): List<TorrentFile>?` | Ждать метаданные. |
| `fun files(id: String): List<TorrentFile>?` | Файлы из сессии или сохранённой записи. |
| `suspend fun prepare(id: String, fileIndex: Int): PlaybackRequest` | См. выше (ждёт метаданные до 120 с, иначе `IOException`). |
| `val torrents: StateFlow<List<TorrentItem>>` | Все торренты (`StoredTorrent` + живые `TorrentStats`: скорость, пиры, прогресс, буфер). |
| `val engineError: StateFlow<String?>` | Ошибка запуска (например, нет нативной библиотеки под ABI). |
| `val settings: TorrentSettings` | `state: StateFlow<TorrentPrefs>`, `update { }`: `cacheLimitGb = 20`, `keepFiles = false`, `maxAgeDays = 7`, `maxConnections = 200`, `uploadLimitKb = 0`, `network = NetworkMode.SEED_WIFI`, `seedPolicy = SeedPolicy.ALWAYS`, `seedOnlyCharging = false`, `mobileDownloadLimitKb = 0` (см. «Правила раздачи» ниже). |
| `val device: StateFlow<DeviceState>` | `DeviceState(metered, charging)`: сеть с оплатой трафика (мобильный интернет, точка доступа) и зарядка (устройство без батареи — ТВ, приставка — считается заряжающимся). |
| `fun needsMobileConsent(): Boolean` / `fun allowMobileData()` | Режим `WIFI_ONLY` и сеть с оплатой трафика: нужно согласие. `allowMobileData()` разрешает торренты до возврата на безлимитную сеть; без него `openStream` (чтение плеером) бросает `IOException`. |
| `suspend fun stop(id: String)` | Остановить раздачу: ни загрузки, ни отдачи; данные остаются. Начатый просмотр продолжит её сам. |
| `suspend fun start(id: String): Boolean` | Продолжить раздачу (вернуть «неактивную» после перезапуска в сессию): качает и раздаёт, автопауза через 10 мин без просмотра её не трогает. `false` — не удалось восстановить. |
| `suspend fun remove(id: String, deleteFiles: Boolean)` / `suspend fun deleteData(id: String)` | Удалить торрент / только скачанные данные. |
| `fun dhtNodes(): Long` | Узлов DHT (для экрана ожидания метаданных). |

Кэш чистится автоматически по `CleanupPolicy` (лимит размера, возраст, `keepFiles`).
`MagnetLink.parse(text)` / `MagnetLink.looksLikeMagnet(text)` проверяют ввод без запуска сессии.
`TorrentSession` (сессия libtorrent и блокирующие потоки чтения, без Android) и `TorrentDataSource`
публичны, но для встраивания достаточно `TorrentEngine`.

Приложение 2160 Player дополнительно открывает magnet-ссылки и `.torrent`-файлы извне
(`TorrentOpenActivity`: `VIEW` со схемой `magnet`, MIME `application/x-bittorrent`, расширение `.torrent`).

**Правила раздачи.** Раз в секунду и сразу при смене сети, зарядки или настроек движок применяет правила;
торренты, поставленные на паузу правилами, возвращаются сами, когда условие снято. Причина паузы — в
`TorrentItem.hold: TorrentHold?` (`WIFI` — ждёт безлимитную сеть, `CHARGING` — ждёт зарядку, `SEED_LIMIT` —
раздача завершена по правилу; продолжить вручную — `start`, после этого правило к торренту не применяется).

| Настройка | Значения и поведение |
|---|---|
| `network: NetworkMode` | `ANY` — по любой сети. `SEED_WIFI` (по умолчанию) — просматриваемый торрент работает везде, фоновые по сети с оплатой трафика ждут Wi-Fi, отдача урезается до `TorrentPrefs.METERED_UPLOAD_LIMIT` (16 КБ/с: совсем выключить её нельзя, а при 1 КБ/с не устанавливаются даже соединения с пирами). `WIFI_ONLY` — по сети с оплатой трафика всё на паузе до `allowMobileData()`; дальше как `SEED_WIFI`. |
| `seedPolicy: SeedPolicy` | Когда скачанный и не просматриваемый торрент перестаёт раздаваться: `ALWAYS`, `RATIO` (отдано не меньше размера выбранных файлов, `TorrentStats.uploadedTotal`), `DAY` (24 ч в состоянии «загружено», `TorrentStats.finishedSeconds`), `NEVER`. Торрент без выбранных файлов «скачанным» не считается. |
| `seedOnlyCharging` | Фоновые торренты ждут зарядку; просмотр работает всегда. |
| (слабое устройство) | `DeviceProfile.lowMemory` (low-RAM или < 2 ГБ): по умолчанию 80 соединений вместо 200 (`TorrentSettings.lowMemory`; выбор `TorrentPrefs.CONNECTIONS`: 50, 80, 100, 200, 400), меньше одновременных загрузок и короче списки пиров (`SessionConfig.lowMemory`). |
| `mobileDownloadLimitKb` | Ограничение загрузки по сети с оплатой трафика (`MOBILE_DOWNLOAD_LIMITS`: 0, 512, 1024, 2048, 5120 КБ/с). |

`TorrentPrefs.sessionConfig(metered)` строит настройки сессии с учётом сети. Приложение 2160 Player перед
добавлением и просмотром торрента в режиме «Только Wi-Fi» спрашивает «Смотреть через мобильный интернет?»
и на карточках показывает метки «Ждёт Wi-Fi», «Ждёт зарядку», «Раздача завершена».

При активации (выбор файла, `start`) торрент сразу заново анонсируется на трекерах и в DHT
(`forceReannounce` с `IGNORE_MIN_INTERVAL`, `forceDHTAnnounce`): восстановленный из данных возобновления
торрент иначе ждал бы интервал трекера (15–30 мин) с нулём пиров.

---

## 22. Функции приложения 2160 Player (не библиотеки)

Код ниже живёт в модуле `app` и в `player-core` **не входит**; описан как поведение и протокол.

### 22.1. «Продолжить на другом устройстве» (`tv.p2160.app.handoff`)

Пока приложение на экране (`ProcessLifecycleOwner` onStart/onStop) или идёт трансляция камеры/экрана (§22.7),
`Handoff` поднимает HTTP-сервер на
случайном порту и объявляет его по mDNS/DNS-SD как `_p2160._tcp` (имя сервиса — имя устройства,
TXT-атрибуты: `id` — постоянный id устройства, `auth` — `1`, если включена защита кодом, `cam` — `1`, если идёт
RTSP-трансляция камеры, §22.7). Другие
экземпляры находят его через `NsdManager`. Без облака — только одна локальная сеть (Wi-Fi или точка
доступа телефона): через мобильную сеть mDNS и прямые соединения не проходят (NAT оператора).

| Запрос | Ответ |
|---|---|
| `GET /now` | `200` + JSON текущего `Player2160.nowPlaying` или `204`, если ничего не играет / нечем поделиться. |
| `POST /play` (JSON, ≤ 64 КБ) | `200 {}`; на принимающем устройстве — диалог «… предлагает продолжить здесь» (`HandoffReceiveActivity`), при согласии — `Player2160.play` с той же позиции и заголовками. |
| `GET /camera` | Адрес трансляции камеры (`{"url": "rtsp://…", "viewers": N}`), 204 — не транслирует. Защита кодом — как у `/now`. |
| `GET /hello?id&name&port&auth&cam` | «Я тоже здесь»: найдя устройство по mDNS (и раз в минуту), плеер сообщает о себе напрямую, а получатель добавляет его в `peers`. Так обнаружение работает, даже если роутер пропускает multicast только в одну сторону; устройства, от которых нет ни mDNS, ни «привета» 3 минуты, удаляются; не ответившее на «привет» — сразу. |
| `GET /stream/<token>/<имя>` | Раздача локального файла (`content://`, `file://`) с поддержкой `Range` (`206 Partial Content`). Токен выдаётся при публикации `/now`/отправке. |
| `GET /pair/challenge?id=&name=` | `200 {"nonce": …}` — запрос подключения (живёт 3 мин). У хозяина появляется уведомление «… хочет подключиться» с кнопками «Разрешить» / «Отклонить» и кодом (на ТВ и без разрешения на уведомления — диалог). `403` — этот клиент отклонён меньше 10 мин назад (уведомления нет). |
| `GET /pair/status?nonce=` | `200 {"state": "pending"}`, `{"state": "approved", "token": …}` (после «Разрешить»; токен отдаётся один раз) или `{"state": "denied"}`; `404` — запрос истёк или закрыт. Клиент опрашивает раз в 1,5 с. |
| `POST /pair` `{"id","name","nonce","proof"}` | Подключение по коду вместо кнопки: `proof = hex(HMAC-SHA256(код, "<nonce>:<id>"))`. `200 {"token": …}`; `403` — неверный код (запрос остаётся открытым); `429` — после 5 ошибок подряд блокировка на минуту; `400` — nonce неизвестен, просрочен, использован, отклонён или от другого `id`. |

**Защита кодом** (`HandoffAuth`, Настройки → «Передача между устройствами»): режимы «Выключена»,
«Код на сутки» (по умолчанию: 6 цифр, новый код в полночь) и «Свой код» (4–8 цифр). При включённой
защите `GET /now` и `POST /play` без заголовка `X-P2160-Token` с действующим токеном отвечают `401`.
Подключение — один раз для нового устройства: хозяин нажимает «Разрешить» в уведомлении или гость
вводит код. Клиент (`PairCodeDialog`) сразу шлёт запрос, ждёт ответа и одновременно принимает код.
Полученный токен хранится на клиенте, выданные токены — на
сервере, пока пользователь не нажмёт «Забыть подключённые устройства»; смена кода на следующий день
сопряжения не сбрасывает. Защита рассчитана на другие плееры и людей в той же сети; трафик не
шифруется (HTTP), от перехвата в сети она не защищает.

```json
{ "uri": "smb://nas/Movies/Film.mkv", "title": "Фильм", "position": 764000, "duration": 7200000,
  "playing": true, "headers": {}, "from": "Galaxy S21" }
```

Сетевые URI (`smb`, `http(s)`, `rtsp`, `rtmp`) передаются как есть; локальные файлы — как
`http://<IPv4 устройства>:<порт>/stream/<token>/<имя>`; прочие схемы (`torrent://` и т.п.) не передаются.
В плеере это кнопка «Отправить на устройство» — обычный `PlayerAction` (`id = "handoff"`),
зарегистрированный приложением через `Player2160.registerAction`. После успешной отправки текущее
воспроизведение ставится на паузу (`Player2160.pause()`).

### 22.2. Автообновление (`tv.p2160.app.update.Updater`)

Только для APK из GitHub Releases (не для Google Play):

- запрос `https://api.github.com/repos/spacesarmat/2160player/releases/latest`; версия — `tag_name`
  без префикса `v` (теги вида `v<versionName>`), сравнивается с `BuildConfig.VERSION_NAME` по числам
  `1.2.3` (суффиксы `-beta` игнорируются);
- из ассетов `*.apk` выбирается первый, чьё имя содержит `-<abi>-` для ABI из `Build.SUPPORTED_ABIS`
  (по порядку), иначе содержащий `universal`;
- APK скачивается в `cacheDir/update` и ставится через `PackageInstaller` (сессия `MODE_FULL_INSTALL`,
  на Android 12+ `USER_ACTION_NOT_REQUIRED`); подтверждение системы показывает `UpdateReceiver`.
  Нужно разрешение `REQUEST_INSTALL_PACKAGES` (есть в манифесте приложения) и подпись тем же ключом;
- тихая проверка при запуске — не чаще раза в 12 ч, если включена настройка «Проверять обновления»
  (по умолчанию включена в release и выключена в debug-сборке); версию можно пропустить («Пропустить
  версию» — больше не предлагается при автопроверке). Ручная проверка — кнопкой в настройках.

Состояние — `Updater.state: StateFlow<UpdateState>` (`Idle`, `Checking`, `UpToDate`, `Available(info)`,
`Downloading(info, progress)`, `Installing`, `Failed(message)`); настройки — SharedPreferences `p2160_update`.

Выбор APK из релиза: ARM-устройствам — общий ARM-APK (в имени `-arm-`), если он есть; иначе APK под
архитектуру устройства (`-arm64-v8a-`, `-armeabi-v7a-`…), иначе `universal`.

Отладочная сборка (`tv.p2160.player.debug`) — другой пакет, Android не поставит релиз поверх неё:
там «Обновить» открывает страницу релиза в браузере (`Updater.RELEASE_PACKAGE`).

### 22.3. Обзор файлов на устройстве (`tv.p2160.app.LocalBrowserScreen`)

Встроенный файловый браузер для «Открыть файл» на Android TV-приставках, где системного выбора файлов
(`ACTION_OPEN_DOCUMENT`) нет или он неудобен с пультом.

**Когда открывается.** `MainActivity.pickMedia()` возвращает `false`, и вместо системного выбора
показывается экран `Screen.LOCAL`, если:

- устройство — телевизор: `UiModeManager.currentModeType == UI_MODE_TYPE_TELEVISION` или есть
  `FEATURE_LEANBACK`;
- системного выбора нет: `openMedia.launch(…)` бросает `ActivityNotFoundException`.

На телефонах и планшетах остаётся системный выбор файлов. «Назад» в корне обзора возвращает на главный экран.

**Корни** (`localRoots(context)`, блокирующий вызов — с фонового потока):

- внутренняя память — `Environment.getExternalStorageDirectory()`;
- съёмные накопители (флешки, карты памяти) — `StorageManager.storageVolumes` в состоянии
  `MEDIA_MOUNTED`/`MEDIA_MOUNTED_READ_ONLY`, название — `StorageVolume.getDescription(context)`;
  папка — `StorageVolume.directory` на Android 11+, на Android 7–10 — `/storage/<uuid>`;
- запасной путь для нестандартных приставок — все папки `/storage/*`, кроме `self` и `emulated`.

Повторы (по `canonicalPath`) убираются; у каждого корня показывается «Свободно X из Y» (`File.freeSpace`/`totalSpace`).

**Список папки** (`listLocalMedia(dir)`): сначала папки, затем файлы, по имени с «естественным» порядком
чисел («Серия 2» раньше «Серии 10»); скрытые (имя с точки) не показываются. Из файлов остаются видео и
аудио (`VIDEO_EXTENSIONS`, `AUDIO_EXTENSIONS` из `BrowserScreen.kt` — тот же фильтр, что для SMB),
образы `.iso`, субтитры (`SubtitleSupport.EXTENSIONS` без `xml`) и плейлисты `.m3u`/`.m3u8`; у файлов — размер.

**Воспроизведение.**

| Что выбрано | Что играется |
|---|---|
| видео или аудио | `PlaybackRequest` из всех видео/аудио папки (`MediaEntry(Uri.fromFile(file), имя без расширения)`) со `startIndex` выбранного — как `playSmb` для SMB, работает «Следующая серия»; субтитры рядом подхватываются плеером (`SubtitleSupport.findSidecars`) |
| `.iso` или папка с `BDMV` | `file://…/Film.iso` или `file://…/BDMV` — `DiscSessions` открывает диск (§14) |
| субтитры | видео той же папки, с имени которого начинается имя субтитров |
| `.m3u`/`.m3u8` | HLS-манифест (`M3uParser.looksLikeHls`) — как поток `application/x-mpegURL`; обычный список — его элементы (относительные пути — от папки плейлиста) |

**Разрешения** (в манифесте приложения; `MANAGE_EXTERNAL_STORAGE` не запрашивается — политика Google Play):

| Android | Разрешение | Что видно |
|---|---|---|
| 7–9 (API 24–28) | `READ_EXTERNAL_STORAGE` (runtime, `maxSdkVersion="32"`) | все файлы |
| 10 (API 29) | `READ_EXTERNAL_STORAGE` + `requestLegacyExternalStorage="true"` | все файлы |
| 11–12 (API 30–32) | `READ_EXTERNAL_STORAGE` | папки и медиафайлы; ISO, субтитры, плейлисты Android может скрывать |
| 13+ (API 33+) | `READ_MEDIA_VIDEO` + `READ_MEDIA_AUDIO` (достаточно одного) | папки и медиафайлы того же типа; прочие файлы скрыты |

Без разрешения экран показывает пояснение и кнопку «Разрешить доступ»; если пользователь отказал
насовсем — кнопку «Открыть настройки» (`ACTION_APPLICATION_DETAILS_SETTINGS`). При возврате на экран
(`ON_RESUME`) разрешение и список накопителей перечитываются — так появляется только что вставленная флешка.

### 22.4. Поделиться приложением (`tv.p2160.app.share.ShareApp`)

Настройки → О приложении → «Поделиться приложением». Три способа, первые два — без интернета:

| Способ | Как работает |
|---|---|
| Отправить файл | `ShareApp.sharedApk` (обычно копия установленного APK, `applicationInfo.sourceDir`) через `FileProvider` (`${applicationId}.share`) в системное «Поделиться»: Quick Share, Bluetooth, Telegram, почта. |
| Раздать по Wi-Fi | Сервер передачи между устройствами (§22.1) отдаёт тот же файл по `GET /app/<имя>.apk` (без защиты кодом, `Content-Type: application/vnd.android.package-archive`, поддерживает `Range`). На экране — адрес `http://<IPv4>:<порт>/app/…` и QR-код (ZXing). Работает в одной Wi-Fi или через точку доступа телефона, пока окно открыто. |
| Ссылка | Текст со ссылкой на `releases/latest` (нужен интернет у получателя). |

Основной APK релиза — общий ARM-APK (`-Pp2160.abi=arm`, `arm64-v8a` + `armeabi-v7a`), и автообновление на
ARM-устройствах берёт именно его (§22.2): установленный файл сразу подходит любому телефону и ТВ.

**Полный пакет при установке под одну архитектуру.** Если установлен APK под одну архитектуру (проверяются папки
`lib/` внутри APK, `ShareApp.needsFull`), плеер хранит рядом общий ARM-APK той же версии:

| Что | Как |
|---|---|
| Где | `filesDir/share-apk/2160player-<версия>-arm.apk` — одна копия; отдаётся в FileProvider как `files-path share-apk/`. |
| Когда качается | При запуске (`ShareApp.prepareFull(context, auto = true)`) — только по безлимитной сети, тихо; или кнопкой «Скачать пакет для всех устройств» в диалоге (`auto = false`, ошибка показывается). |
| Откуда | Релиз `v<VERSION_NAME>` (`GET /repos/<repo>/releases/tags/v…`, `Updater.sharedAsset`): ассет `-arm-`, иначе `universal`. Скачивается во временный `.part`, проверяется `getPackageArchiveInfo` (пакет `tv.p2160.player` и та же версия), затем переименовывается. |
| Очистка | При каждом запуске удаляется всё, кроме пакета текущей версии; после перехода на общий ARM-APK папка очищается целиком. `Updater.cleanup` при запуске удаляет и скачанный APK обновления из `cacheDir/update`. |
| Использование | `ShareApp.sharedApk(context)` — полный пакет, если он нужен и скачан, иначе установленный APK: его отдают «Отправить файл» и `GET /app/…`. |

Состояние — `ShareApp.full: StateFlow<FullState>` (`None`, `Downloading(progress)`, `Ready(file)`,
`Failed(message)`); диалог показывает предупреждение и кнопку, прогресс или «Отправляется пакет для всех устройств».

### 22.5. Справка и FAQ (`tv.p2160.app.FaqScreen`)

Настройки → О приложении → «Справка и FAQ»: вопросы по разделам (установка и обновления, картинка, звук,
субтитры, файлы и сеть, торренты, между устройствами, разное), ответ раскрывается по нажатию (палец и
пульт), внизу — контакт автора в Telegram (`Faq.TELEGRAM_URL`, тот же пункт «Автор» в Настройках → О приложении),
«Поддержать проект» на Boosty (`Faq.DONATE_URL`, есть и в Настройках)
и ссылка на GitHub Issues. Список вопросов — `Faq.sections` (id по разделам), тексты — в
языковом пакете `assets/i18n/faq/<код>.json` (ключи `faq.<id>.q` / `faq.<id>.a`, заголовки `faq.section.*`),
поэтому FAQ переводится вместе с остальным интерфейсом (экспорт/импорт шаблона перевода).

### 22.6. Список «Продолжить просмотр» (`tv.p2160.app.ContinueScreen`)

На главном экране заголовок «Продолжить просмотр» кликабелен (палец и пульт, рядом — «все · N»): открывается
полный список недосмотренных файлов — `store.recent(500)` без досмотренных и без нулевой позиции, сеткой
(`GridCells.Adaptive(300.dp)`, на телефоне — одна колонка) с обложками и прогрессом. Нажатие — продолжить,
долгое нажатие — убрать запись (`ResumeStore.delete`), как и на главном экране; когда список пустеет, экран
закрывается. «Назад» возвращает на главный экран.

### 22.7. Трансляция камеры (`tv.p2160.app.camera`)

Главный экран → «Камера» (плитка есть, только если система видит хотя бы одну камеру). Телефон становится RTSP-сервером
в своей сети (или сам отправляет поток по SRT/RTMP — см. «Куда»): видео H.264 с аппаратного кодировщика и звук AAC с микрофона. Смотреть — в OBS («Источник медиа»,
снять «Локальный файл», адрес `rtsp://<IP телефона>:8554/`), VLC («Открыть URL»), ffmpeg или в другом 2160 Player
(«Ссылка»). Работает и через точку доступа телефона.

| Что | Как |
|---|---|
| Что | `CameraStreamConfig.source` (`StreamSource`): `CAMERA` или `SCREEN` — экран телефона (`ScreenSource` RootEncoder): при старте системный запрос записи экрана (`MediaProjectionManager.createScreenCaptureIntent`), ответ передаётся сервису (`CameraStream.startScreen`), сервис стартует с типом `mediaProjection` и только потом берёт `MediaProjection` (требование Android 14). Последний кадр повторяется не реже 15 раз в секунду (`setForceRender`) — иначе статичный экран «застывает». Звук экрана (`screenAudio`): микрофон, звук телефона (`InternalAudioSource`, Android 10+; приложения могут запрещать запись своего звука) или оба (`MixAudioSource`). Режимы — 720p/1080p × 30/60, что потянет кодировщик (`CameraStream.screenModes()`). Остановка системной кнопкой записи экрана тоже останавливает трансляцию. |
| Куда | `CameraStreamConfig.protocol` (`StreamProtocol`): `RTSP` — сервер на телефоне (зрители подключаются сами, адрес и QR); `SRT` — телефон сам шлёт поток (`srtUrl`, например `srt://192.168.1.10:9000`; в OBS «Источник медиа»: `srt://0.0.0.0:9000?mode=listener`, формат `mpegts`); `RTMP` — на сервис (`rtmpUrl` + `rtmpKey`, заготовки YouTube `rtmp://a.rtmp.youtube.com/live2` и Twitch `rtmp://live.twitch.tv/app`; `CameraStream.rtmpEndpoint` склеивает адрес, ключ уже в адресе своего сервера тоже понимается). SRT/RTMP: до 10 повторов подключения через 5 с (`reTry`), статус «Подключение…/Подключено» (`CameraStreamState.Streaming.connected`), ключ на экран и в уведомление не выводится; неверный адрес — `camera.err_srt_url` / `camera.err_rtmp_url`, отказ по ключу — `camera.err_auth`. Настройки (камера, режим, звук, куда, адрес, ключ) сохраняются на устройстве (`CameraStream.load`). Камеры в сети (`/camera`, `cam=1`) — только для RTSP (`CameraStream.isServing`). |
| Камера | `CameraStream.cameras(context)`: все камеры Camera2 с подписью («Задняя», «Широкоугольная», «Телевик» — по фокусному расстоянию относительно основной, «Фронтальная», «Внешняя»), режимами и наличием вспышки. Переключается и во время трансляции (`Camera2Source.openCameraId`). |
| Режимы | `StreamMode(width, height, fps)` строятся из возможностей устройства (`CameraInfo.modes`): кадры 16:9 из ряда 720p/1080p/1440p/4K, которые камера отдаёт на поверхность (`SCALER_STREAM_CONFIGURATION_MAP`), × частоты автоэкспозиции 24–120 fps, которые она держит при этом размере (`getOutputMinFrameDuration`), — и только то, что потянет аппаратный кодировщик H.264 (`VideoCapabilities.areSizeAndRateSupported`, в любой ориентации). Битрейт — ~0,08 бит на пиксель (1080p·30 — 5 Мбит/с, 4K·30 — 20). По умолчанию 1080p·30 (`CameraStreamConfig.quality`); если у камеры его нет — лучший до 30 fps (запасной — 720p·30, `StreamMode.DEFAULT`); при смене камеры, если её режимы другие, берётся ближайший не больше прежнего. Меняется только без трансляции. |
| Звук | Микрофон, AAC 44,1 кГц стерео, 128 кбит/с (`CameraStreamConfig.audio`); без разрешения — видео без звука. Микрофон — `micId` из `CameraStream.microphones(context)` (встроенный, гарнитура, USB, Bluetooth; `null` — выбирает система), меняется и во время трансляции (`MicrophoneSource.setPreferredDevice`). Усиление `micGain` 0,5–4× и индикатор уровня — один эффект `GainMeterEffect` (`CameraStream.gainMeter.level`: пик 0..1 раз в ~0,1 с; красный — звук обрезается); усиление меняется на лету. Для звука экрана «Звук телефона» / «Телефон + микрофон» усиление и индикатор не действуют. |
| Фонарик | `CameraStream.setTorch(on)` — у камеры со вспышкой (обычно задней), в том числе во время трансляции. |
| Фокус и зум | Касание предпросмотра — фокус и экспозиция в точке (`CameraStream.tapToFocus`), «Автофокус» — снова непрерывный (`autoFocus`). Зум — щипком на предпросмотре (`onPreviewTouch`) и ползунком (`setZoom`, диапазон `zoomRange()` у самой камеры, `zoom: StateFlow`). Работают и во время трансляции; при смене камеры зум сбрасывается. |
| Ориентация | Кадр всегда горизонтальный 16:9 (для ТВ и OBS): держите телефон горизонтально — картинка займёт весь кадр; вертикально — с полями по бокам. Поворот во время трансляции ничего не ломает. |
| Фон | Трансляция идёт в сервисе переднего плана `CameraStreamService` (`foregroundServiceType="camera\|microphone\|mediaProjection"`): экран можно закрыть, приложение свернуть. Уведомление показывает адрес и число зрителей, кнопка «Остановить»; нажатие открывает экран трансляции (`MainActivity.EXTRA_OPEN_CAMERA`). |
| Задержка | Камера: ключевой кадр раз в секунду (новый зритель быстро получает картинку). Плеер для живых источников (`rtsp(s)`, `rtmp(s)`, `udp`, `rtp`; внутренний список схем) включает низкую задержку: старт после 0,1 с буфера, не больше 2 с в буфере (обычно ~0,5 с), без чтения глав и поиска вступления через FFmpeg; RTSP — RTP поверх TCP (по Wi-Fi UDP теряется). |
| Защита | Как у «Передачи между устройствами» (§22.1): если защита включена, RTSP-поток требует Basic-авторизацию — логин `CameraStream.RTSP_USER` = `2160`, пароль — текущий код (`HandoffAuth.currentCode()`: суточный или свой); раз в минуту код сверяется (суточный меняется в полночь — подключённые зрители остаются, новым нужен новый). На экране — адрес с паролем и QR (`CameraStreamState.Streaming.urlWithAuth`) для OBS/VLC; сопряжённые 2160 Player получают его по `/camera` и подключаются сами; в уведомлении адрес без пароля. Защита выключена — поток открыт для всей сети (экран предупреждает). |
| Сервер | RTSP на порту `CameraStream.DEFAULT_PORT` = 8554, адрес в ответах — IPv4 (по нему клиенты делают SETUP), адрес для зрителей — IPv4 Wi-Fi, как у передачи между устройствами (§22.1). Число зрителей и битрейт — в `CameraStream.state` (`CameraStreamState.Streaming`). |

**Приём потоков.** Любой плеер 2160 Player открывает `rtsp://`, `rtmp://` (модуль `media3-datasource-rtmp`), HLS/DASH и `udp://адрес:порт`
(MPEG-TS по UDP — ffmpeg, OBS, IPTV-мультикаст; встроенный источник `UdpDataSource` в `RoutingDataSource`) через
«Ссылку» или Intent (`udp://` — только с явным компонентом: фильтров `VIEW` для этой схемы нет). Приём SRT плеер
не умеет — SRT здесь только для отправки в OBS. Для живых источников включается низкая задержка (см. «Задержка»).

**Камеры в сети.** Транслирующее устройство объявляет в mDNS-записи `cam=1` (`Handoff.reannounce()` при старте и
остановке) и отдаёт адрес по `GET /camera` (с той же защитой кодом, что `/now`). На главном экране других плееров —
раздел «Камеры в сети» (`Handoff.fetchCameras()`, опрос раз в 5 с): у сопряжённых устройств карточка сразу открывает
поток, у остальных — «Нажмите, чтобы подключиться»: обычное сопряжение (уведомление «Разрешить» на телефоне или код,
§22.1), затем поток. Обнаружение работает и в сетях, где multicast проходит только в одну сторону (Wi-Fi ↔ провод):
см. `GET /hello` в §22.1.

Разрешения: `CAMERA`, `RECORD_AUDIO`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CAMERA`, `FOREGROUND_SERVICE_MICROPHONE`,
`FOREGROUND_SERVICE_MEDIA_PROJECTION`, `POST_NOTIFICATIONS` (уведомление трансляции)
(камера и микрофон запрашиваются при первом открытии экрана). Захват и кодирование — RootEncoder 2.8.1,
сервер — RTSP-Server 1.4.3 (pedroSG94, Apache-2.0) исходниками в модуле `third-party/rtsp-server` с правкой: ответ на `PLAY` без `RTP-Info: seq=1;rtptime=0` — иначе Media3, подключившись не сразу после старта, не начинал воспроизведение (см. `NOTICE.md`). RootEncoder — с JitPack, модуль WHIP исключён. Без защиты (выключена в настройках) любой в той же сети, кто знает адрес, может смотреть;
Basic-авторизация RTSP идёт открытым текстом — защищает от случайных зрителей в домашней сети, а не в чужих сетях.

## 23. Справочник классов

### 23.1. Стабильный публичный API

| Пакет | Класс | Раздел |
|---|---|---|
| `tv.p2160.core.api` | `Player2160`, `Player2160.PlayContract` | §5.2 |
| | `PlayerConfig` | §15.6 |
| | `DeviceProfile` | §15.7 |
| | `PlaybackRequest`, `MediaEntry`, `ExternalSubtitle`, `PlaybackResult` | §5.1 |
| | `IntentApi` | §3, §5.3 |
| | `NowPlaying`, `PlayerAction`, `PlayerExtensions` | §7, §8 |
| | `LiveGuide` | §20.1 |
| | `SkipSegment`, `SegmentType`, `Chapter` | §12 |
| `tv.p2160.core` | `Player2160Activity`, `PlayerViewModel` | §5.4, §6.2 |
| `tv.p2160.core.engine` | `PlayerController`, `PlayerUiState`, `TrackOption` | §6.1, §7.2 |
| | `PlaybackStats`, `FrameRateMatcher` | §15.7 |
| | `ManualMarks`, `SegmentDetector`, `TimeInput` | §12 |
| | `NightAudioProcessor`, `PassthroughGuard` | §16 |
| | `TrackPreferences`, `TrackRules`, `TrackChoice`, `TrackCandidate` | §17 |
| | `MediaReport`, `ReportSection`, `ReportRow` | §18.2 |
| | `PlaybackService` (объявлен в манифесте библиотеки) | §15.4 |
| | `DolbyVisionFallback` (`Marker`, `ExtractorsFactoryWrapper`) | §15.5 |
| `tv.p2160.core.intro` | `IntroDetector`, `DetectionResult` | §18.1 |
| `tv.p2160.core.ui` | `PlayerScreen`, `PlayerTheme`, `PlayerThemes`, `P2160Theme` | §6, §9.3 |
| `tv.p2160.core.settings` | `PlayerSettings`, `Settings`, `NightSchedule`, `SubtitleStyle`, `DecoderPreference`, `ResizeMode`, `SkipMode`, `SubtitleEdge` | §9 |
| `tv.p2160.core.resume` | `ResumeStore`, `ResumeEntry`, `Covers` | §10 |
| `tv.p2160.core.source.smb` | `SmbServers`, `SmbServer`, `SmbPath`, `SmbEntry`, `SmbConnections` | §11 |
| `tv.p2160.core.source.dlna` | `DlnaServers`, `KnownDlnaServer`, `DlnaServer`, `DlnaIcon`, `DlnaDiscovery`, `DlnaProbe`, `ContentDirectory`, `BrowsePage`, `DlnaObject`, `DlnaContainer`, `DlnaItem`, `DlnaResource`, `DlnaSubtitle`, `DlnaMediaKind`, `DlnaPlayback`, `DlnaException` | §19 |
| `tv.p2160.core.iptv` | `IptvStore`, `IptvPlaylist`, `IptvPlayback`, `M3uParser`, `M3uPlaylist`, `IptvChannel`, `XmltvParser`, `EpgFilter`, `EpgData`, `EpgGuide`, `EpgProgramme`, `NowNext`, `EpgCodec` | §20 |
| `tv.p2160.core.i18n` | `I18n`, `Strings`, `LanguagePack`, `LocalStrings`, `tr` | §13 |
| `tv.p2160.torrent` (модуль `source-torrent`) | `TorrentEngine`, `TorrentItem`, `TorrentFile`, `TorrentStats`, `StoredTorrent`, `TorrentSettings`, `TorrentPrefs`, `NetworkMode`, `SeedPolicy`, `TorrentHold`, `DeviceState`, `MagnetLink` | §21 |

Модуль `app` (не библиотека, §22): `tv.p2160.app.handoff.Handoff`, `HandoffAuth`, `PairRequests`, `PairRequest`, `Peer` (`locked`, `camera`), `RemoteSession`, `RemoteCamera`, `PushResult`, `tv.p2160.app.share.ShareApp`, `ShareAppDialog` (§22.4), `tv.p2160.app.FaqScreen` (§22.5), `ContinueScreen` (§22.6),
`tv.p2160.app.camera.CameraStream`, `CameraStreamService`, `CameraStreamScreen`, `CameraStreamConfig`, `CameraStreamState`,
`StreamMode`, `StreamProtocol`, `StreamSource`, `ScreenAudio`, `CameraInfo`, `MicInfo`, `GainMeterEffect` (§22.7);
`tv.p2160.app.update.Updater`, `UpdateInfo`, `UpdateState`;
`tv.p2160.app.LocalBrowserScreen`, `LocalRoot`, `LocalKind`, `localRoots`, `listLocalMedia`, `storagePermissions` (§22.3).

### 23.2. Публичные, но внутренние

Следующие классы объявлены `public` (библиотека не использует explicit API mode), но являются
деталями реализации; их сигнатуры могут меняться:

- `tv.p2160.core.ui`: `ControlButton`, `SeekBar`, `PanelRow`, `SidePanel`, `Panel`, `PanelActions`,
  `SPEED_PRESETS`, `TapSeekLayer`, `ScrubPreview`, `SpeedBoostBadge`, `DigitEntryOverlay`,
  `SkipButton`, `NextEpisodeCard`, `GoToTimeDialog`, `formatTime`, `formatSpeed`, `formatDelta`;
- `tv.p2160.core.engine`: `SecondarySubtitles`, `MediaReporter`, `ActiveDecoders`;
- `tv.p2160.core.intro`: `IntroAnalyzer`, `IntroMatcher`, `Fingerprinter`, `Fingerprint`, `IntroStore`,
  `MediaCodecPcmDecoder`, `PcmSource`;
- `tv.p2160.core.source.dlna`: `SsdpDiscovery`, `SsdpMessage`, `DlnaDiscoveryListener`,
  `MulticastLockProvider`, `AndroidMulticastLock`, `DeviceDescriptionParser`, `DidlParser`, `XmlLite`,
  `XmlNode`, `DlnaHttp`, `UrlConnectionHttp`, `HttpResult`, `DlnaPlaybackSpec`, `DlnaSubtitleSpec`;
- `tv.p2160.torrent`: `TorrentSession`, `SessionConfig`, `TorrentDataSource`, `PiecePlanner`,
  `CleanupPolicy`, `NaturalOrder`;
- `tv.p2160.core.bluray.*` (`DiscSessions`, `DiscSession`, `BlurayDisc`, `UdfFileSystem`, парсеры MPLS/CLPI,
  `RandomAccessSource` и реализации, `DiscDataSource`, `DiscMediaSourceFactory`…);
- `tv.p2160.core.m2ts.*` (`M2tsExtractor`, `M2tsExtractorsFactory`, `BlurayTsPayloadReaderFactory`,
  `TrueHdReader`, `BdLpcmReader`, `PgsReader`, `PidTrackHints`);
- `tv.p2160.core.source.*` (`RoutingDataSource`, `RandomAccessSources`, `SeekableFiles`,
  `SmbDataSource`, `*RandomAccessSource`);
- `tv.p2160.core.subtitle.SubtitleSupport`.

Если вы собираете свой ExoPlayer и хотите `smb://`, можно использовать
`RoutingDataSource.Factory(context, DefaultDataSource.Factory(context))` и
`M2tsExtractorsFactory` — но это вне гарантий совместимости.
