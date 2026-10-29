# Публичный API библиотеки

Модуль `player-core`, пакеты `tv.p2160.core.*`. Подключение и ProGuard - [API.md §4](../API.md#4-подключение-библиотеки).
Ниже - методы, параметры и ошибки. Длинные примеры - в [API.md](../API.md).

Всё, что ниже не перечислено в [§23.1](../API.md#231-стабильный-публичный-api) справочника, но объявлено `public`, считается внутренним и может измениться.

## `Player2160`

| Метод | Параметры | Результат / ошибки |
|---|---|---|
| `intent(context, request)` | `PlaybackRequest` | `Intent` на `Player2160Activity` |
| `play(context, request)` | то же | `startActivity`. Пустой плейлист в запросе не проверяется здесь - активность закроется, если Intent без `data` |
| `resumeStore(context)` | | синглтон `ResumeStore` |
| `settings(context)` | | синглтон `PlayerSettings` |
| `nowPlaying` | | `StateFlow<NowPlaying?>`, снимок ~раз в секунду; после `release` - `isPlaying = false` |
| `registerAction` / `unregisterAction(id)` | `PlayerAction` | кнопка в панели плеера |
| `pause()` | | пауза текущего сеанса, если он есть |
| `config` | `PlayerConfig` | действует на плееры, созданные после присваивания |
| `setLiveGuide(guide)` | `LiveGuide?` | подпись передачи под названием канала в режиме эфира; `null` - встроенный IPTV |
| `PlayContract` | `ActivityResultContract<PlaybackRequest, PlaybackResult?>` | `null`, если результат отменён |

## Запрос и результат

`PlaybackRequest(items, startIndex = 0, startPositionMs = null, headers = empty, returnResult = false, liveTv = false)`

- `single(uri, title, positionMs, mimeType, headers)` - один элемент.
- `startPositionMs = null` - продолжить с истории, если `autoResume` и `PlayerConfig.restoreFromHistory`.
- `0` - с начала. `liveTv = true` - без истории и без перемотки, каналы по кругу.

`MediaEntry(uri, title, subtitles, mimeType, segments, artworkUri)`.

`ExternalSubtitle(uri, name, language, select)`.

`PlaybackResult(uri, positionMs, durationMs, completed)`.

`PlaybackRequest` с пустым `items` до плеера не доходит: `IntentApi.parse` без `data` возвращает `null`.

## `PlayerController` (главный поток)

Конструктор: `(context, request, config = Player2160.config)`.

| Метод | Поведение |
|---|---|
| `playPause()` | конец → с начала и play; idle → prepare+play; иначе пауза/play |
| `seekTo` / `seekBy` | позиция в `[0, duration]` |
| `next` / `previous` | в эфире - канал по кругу; иначе следующий файл или в начало, если прошло > 5 с |
| `switchChannel(delta)` | только `liveTv` и если элементов ≥ 2 |
| `setSpeed(speed)` | 0,25…4. Это скорость, которая пишется в историю |
| `select` / `disableSubtitles` / `autoVideo` | дорожки; ручной выбор запоминается, если `smartTracks` |
| `setNightMode` / `setAudioDelay` / `setSubtitleDelay` | сразу и в настройки (задержка звука - глобально, ±2000 мс) |
| `setSleepTimer(minutes)` | `null` или `≤ 0` - выкл. Иначе затихание 8 с и пауза |
| `setSleepAtEndOfItem` / `cancelSleepTimer` | пауза в конце текущего файла |
| `skip(segment)` | если длительность ещё неизвестна - ничего не делает и не помечает отрезок пропущенным |
| `addExternalSubtitle` | пересобирает текущий элемент, позицию сохраняет |
| `release()` | сохранить прогресс, снять слушатели, отпустить плеер. Повторный вызов не предусмотрен |

`state: StateFlow<PlayerUiState>` - положение, дорожки, ошибка (`error` - уже локализованная строка),
`isLive`, `liveTv`, главы, отрезки. Ошибки ExoPlayer, которые плеер глотает сам: сбой passthrough
(несколько повторов, затем декодирование), сбой туннеля (настройка выключается), `BEHIND_LIVE_WINDOW`
(переход к живому краю). Остальное попадает в `PlayerUiState.error`.

Скорость догона живого края (×1,1 / ×1,25) и удержание 2× в `ResumeEntry.speed` не пишутся.

## `PlayerScreen`

```kotlin
PlayerScreen(controller, settingsStore, onBack, onPickSubtitle, inPictureInPicture = false)
```

Сама читает `settings.keepScreenOn` и состояние плеера. Хост отвечает за жизненный цикл контроллера
и за PiP (`enterPictureInPictureMode`, когда `state.isPlaying && state.hasVideo`).

## История

`ResumeStore.get/recent/save/delete/clear/cover/keyFor/isFinished`.

`keyFor`: у `http`/`https` отбрасываются query и фрагмент; порт сохраняется, кроме 80 и 443; IPv6 в скобках.
Два сервера на одном хосте и разных портах больше не делят одну позицию.

`isFinished(position, duration)`: `false`, если длительность ≤ 0 или позиция ≤ 0. Иначе досмотрено при
прогрессе > 97 % или (длительность > 30 с и осталось < 30 с). Ролик короче 30 с с начала не считается
досмотренным.

## Настройки и локализация

`PlayerSettings.update`, поля `Settings` - [Настройки](Settings.md).

`I18n.get(context)`, `Strings.get(key)`, `Strings.format(key, args)`, `tr(key, args)`.
Нет ключа - возвращается сам ключ, исключения нет. Плейсхолдеры `%1$s` / `%2$d`. Чужой пакет импортируется
JSON-файлом; битый JSON - ошибка импорта, встроенные пакеты не портятся.

## Ошибки, которые видит пользователь

Ключи `error.network`, `error.http`, `error.not_found`, `error.no_permission`, `error.container`,
`error.decoder`, `error.drm`, `error.generic` (с именем кода ExoPlayer). Тексты в языковом пакете ядра.
