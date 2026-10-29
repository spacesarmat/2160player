# Intent API

Отдельное приложение, без библиотеки. Пакет release - `tv.p2160.player`, debug - `tv.p2160.player.debug`.
Activity: `tv.p2160.core.Player2160Activity`.

Полные таблицы и примеры `adb` - [API.md §3](../API.md#3-intent-api-без-библиотеки).

## Как открыть

| Action | Данные | MIME |
|---|---|---|
| `tv.p2160.action.PLAY` | `content`, `file`, `http`, `https`, `rtsp` | любой или без типа |
| `ACTION_VIEW` | `content`, `file`, `http`, `https` | `video/*`, `audio/*`, HLS, DASH, SmoothStreaming, mp4, matroska, ogg |
| `ACTION_VIEW` | `rtsp` | без MIME |

`ACTION_VIEW` на `http(s)` **без MIME** фильтр не ловит. `smb://`, `torrent://`, `udp://` - только
явный компонент `ComponentName("tv.p2160.player", "tv.p2160.core.Player2160Activity")`.

Обязательное поле одно: `Intent.data`. Нет `data` - плеер не запускает воспроизведение.

## Вход

Совместимо с MX Player: `title` (String), `position` (Int, Long или строка, мс), `from_start` (Boolean),
`headers` (String[] парами имя/значение), `return_result`, `subs` и `subs.enable` (Uri[]),
`subs.name` (String[]), `video_list` (Uri[]), `video_list.name`.

VLC: `subtitles_location` (путь или URI; без `://` считается файлом), `from_start`.

Свои ключи:

| Extra | Тип | Смысл |
|---|---|---|
| `tv.p2160.extra.PLAYLIST` | String[] | URI плейлиста |
| `tv.p2160.extra.TITLES` | String[] | названия |
| `tv.p2160.extra.MIME_TYPES` | String[] | MIME по элементам |
| `tv.p2160.extra.SEGMENTS` | String | `intro:0-90000;credits:1320000-` |
| `tv.p2160.extra.INTRO_START` / `INTRO_END` | мс | вступление, оба нужны, `end > start` |
| `tv.p2160.extra.CREDITS_START` | мс | титры до конца |
| `tv.p2160.extra.ARTWORK` / `ARTWORKS` | String / String[] | обложки |
| `tv.p2160.extra.LIVE` | Boolean | режим телеканалов |

Субтитры, MIME из `Intent.type` и отрезки относятся только к стартовому элементу.
Нечисловой `position` читается как 0. Неизвестные extras игнорируются.
`subs` и `subtitles_location` подключаются при старте и запоминаются для этого URI: следующий запуск того же файла без extras снова их включает. `content://` сохраняет право чтения, если его выдали с правом сохранить.

## Ответ

`RESULT_OK` и action `com.mxtech.intent.result.VIEW`, если передан `return_result` или вызов был
`startActivityForResult` / Activity Result API, и пользователь закрыл плеер «Назад».

| Extra | Тип | Смысл |
|---|---|---|
| `data` | Uri | на каком элементе остановились |
| `position`, `duration` | Int, мс | как у MX Player |
| `end_by` | String | `user` или `playback_completion` |
| `tv.p2160.extra.WATCHED` | Boolean | досмотрено до конца. `end_by` остаётся |
| `tv.p2160.extra.POSITION`, `tv.p2160.extra.DURATION` | Long, мс | полная позиция и длительность. Если досмотрено, позиция равна длительности |
| `extra_position`, `extra_duration` | Long | как у VLC |

Свайп из «Недавних» и убийство процесса - `RESULT_CANCELED` без extras.
Позиция длиннее `Int` (~24,8 суток) в MX-полях обрезается до Int; в VLC-полях остаётся Long.

```kotlin
val play = Intent("tv.p2160.action.PLAY")
    .setDataAndType(Uri.parse("https://cdn.example/movie.mkv"), "video/*")
    .setPackage("tv.p2160.player")
    .putExtra("title", "Фильм")
    .putExtra("position", 125_000)
    .putExtra("return_result", true)
```
