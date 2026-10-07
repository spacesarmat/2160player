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
15. [Потоки, жизненный цикл, ошибки, ограничения](#15-потоки-жизненный-цикл-ошибки-ограничения)
16. [Справочник классов](#16-справочник-классов)

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
| Субтитры | Внешние SRT/ASS/SSA/VTT/TTML (автоопределение кодировки), автопоиск файлов рядом с видео (file:// и smb://), задержка, размер/цвет/обводка, **двойные субтитры** (вторая дорожка сверху). |
| Главы и отрезки | Главы из контейнера (FFmpeg) и с Blu-ray; пропуск вступления/пересказа/титров/анонса: из Intent, из названий глав, из ручных отметок (на весь сериал). Режимы «кнопка» и «авто». |
| Продолжение просмотра | SQLite-история: позиция, аудио- и текстовая дорожка, скорость, задержка субтитров. Возврат позиции вызывающему приложению (MX Player/VLC-совместимо). |
| UI | Телефон и Android TV (D-pad, пульт, цифровой ввод времени), жесты, PiP, превью кадров при перемотке, 6 тем оформления. |
| Локализация | JSON-пакеты в assets, импорт/экспорт пользовательских переводов. Встроены `en`, `ru`. |

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
    implementation("tv.p2160:player-core:0.1.0")
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

- разрешения `INTERNET` и `ACCESS_NETWORK_STATE`;
- `android:usesCleartextTraffic="true"` на `<application>` (IPTV и домашние серверы часто без HTTPS);
- Activity `tv.p2160.core.Player2160Activity` — `exported="false"`, `singleTop`,
  `supportsPictureInPicture="true"`, тема `@style/Theme.P2160.Player`.

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
`EXTRA_VIDEO_LIST`, `EXTRA_VIDEO_LIST_NAME`, `EXTRA_TITLES`, `EXTRA_MIME_TYPES`, `EXTRA_SEGMENTS`,
`EXTRA_INTRO_START`, `EXTRA_INTRO_END`, `EXTRA_CREDITS_START`, `RESULT_ACTION`, `RESULT_POSITION`,
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

### 6.1. `PlayerController`

```kotlin
class PlayerController(context: Context, request: PlaybackRequest)
```

Создание сразу собирает ExoPlayer и **начинает воспроизведение** (`playWhenReady = true`),
восстанавливает позицию/дорожки/скорость, раз в ~5 с сохраняет прогресс и раз в секунду публикует
`Player2160.nowPlaying`. Создавайте и вызывайте все методы на главном потоке. Обязательно вызовите
`release()`, иначе останутся ExoPlayer, декодеры и открытые диски.

| Член | Описание |
|---|---|
| `val state: StateFlow<PlayerUiState>` | Состояние для UI (обновляется ~2 раза в секунду и по событиям). |
| `val player: ExoPlayer` | Исходный ExoPlayer — для `pause()`, слушателей, `MediaSession` и т.п. Не вызывайте на нём `release()`. |
| `val secondaryCues: StateFlow<List<Cue>>` | Реплики вторых субтитров (их рисует `PlayerScreen`). |
| `fun playPause()` | Пауза/продолжить; после конца — с начала; после ошибки — `prepare()`. |
| `fun seekTo(positionMs: Long)` / `fun seekBy(deltaMs: Long)` | Перемотка (ограничивается длительностью). |
| `fun next()` / `fun previous()` | Следующий/предыдущий элемент плейлиста. `previous()` после 5 с — к началу текущего. |
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

    override fun onStop() {
        super.onStop()
        val controller = vm.controller ?: return
        if (!inPip || isFinishing) controller.player.pause()   // в PiP продолжаем играть
        controller.saveProgress()
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

Перечисления:

- `DecoderPreference { AUTO, HARDWARE, FFMPEG }` — аппаратные + FFmpeg как запасной / только MediaCodec / всегда FFmpeg.
- `ResizeMode { FIT, FILL, ZOOM }` — вписать / растянуть / заполнить с обрезкой.
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
| `fun delete(key: String)` / `fun clear()` | Удалить запись / всю историю. |
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

---

## 16. Справочник классов

### 16.1. Стабильный публичный API

| Пакет | Класс | Раздел |
|---|---|---|
| `tv.p2160.core.api` | `Player2160`, `Player2160.PlayContract` | §5.2 |
| | `PlaybackRequest`, `MediaEntry`, `ExternalSubtitle`, `PlaybackResult` | §5.1 |
| | `IntentApi` | §3, §5.3 |
| | `NowPlaying`, `PlayerAction`, `PlayerExtensions` | §7, §8 |
| | `SkipSegment`, `SegmentType`, `Chapter` | §12 |
| `tv.p2160.core` | `Player2160Activity`, `PlayerViewModel` | §5.4, §6.2 |
| `tv.p2160.core.engine` | `PlayerController`, `PlayerUiState`, `TrackOption` | §6.1, §7.2 |
| | `ManualMarks`, `SegmentDetector`, `TimeInput` | §12 |
| `tv.p2160.core.ui` | `PlayerScreen`, `PlayerTheme`, `PlayerThemes`, `P2160Theme` | §6, §9.3 |
| `tv.p2160.core.settings` | `PlayerSettings`, `Settings`, `SubtitleStyle`, `DecoderPreference`, `ResizeMode`, `SkipMode`, `SubtitleEdge` | §9 |
| `tv.p2160.core.resume` | `ResumeStore`, `ResumeEntry` | §10 |
| `tv.p2160.core.source.smb` | `SmbServers`, `SmbServer`, `SmbPath`, `SmbEntry`, `SmbConnections` | §11 |
| `tv.p2160.core.i18n` | `I18n`, `Strings`, `LanguagePack`, `LocalStrings`, `tr` | §13 |

### 16.2. Публичные, но внутренние

Следующие классы объявлены `public` (библиотека не использует explicit API mode), но являются
деталями реализации; их сигнатуры могут меняться:

- `tv.p2160.core.ui`: `ControlButton`, `SeekBar`, `PanelRow`, `SidePanel`, `Panel`, `PanelActions`,
  `SPEED_PRESETS`, `TapSeekLayer`, `ScrubPreview`, `SpeedBoostBadge`, `DigitEntryOverlay`,
  `SkipButton`, `NextEpisodeCard`, `GoToTimeDialog`, `formatTime`, `formatSpeed`, `formatDelta`;
- `tv.p2160.core.engine.SecondarySubtitles`;
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
