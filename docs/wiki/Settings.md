# Настройки

Экран приложения: **Настройки**. Строки — пакет `app` (`settings.*` в `assets/i18n/app/{ru,en}.json`).
Хранилище — `SharedPreferences` `p2160_settings`, класс `tv.p2160.core.settings.PlayerSettings`.

```kotlin
PlayerSettings.get(context).update { it.copy(keepScreenOn = false) }
```

Смена `language` применяется сразу. Часть полей (декодер, туннель, offload, языки дорожек по умолчанию)
читается при создании `PlayerController`, остальное — на лету.

## Не выключать экран во время просмотра

Пункт: **Настройки → Воспроизведение → «Не выключать экран во время просмотра»**.
Поле: `Settings.keepScreenOn`, по умолчанию `true`, ключ `keep_screen_on`.

Пока переключатель включён, `PlayerScreen` вызывает `View.setKeepScreenOn(true)` у `PlayerView`
(это `WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON`). Флаг стоит только если одновременно:

- идёт воспроизведение: `isPlaying`, либо буферизация при `playWhenReady == true`;
- это видео (`hasVideo`) или прямой эфир (`isLive` или плейлист `liveTv`).

Флаг снимается, когда:

- пауза (в том числе буферизация уже на паузе);
- конец файла;
- ошибка воспроизведения;
- экран плеера закрыт (`AndroidView.onRelease` и отсоединение view от окна).

Музыка без картинки экран не держит. Отдельный `WakeLock` не берётся: погашенный или свёрнутый
экран флаг не включает. В окне «картинка в картинке» флаг действует, пока ролик играет и окно видно.

Подсказка в приложении: Справка → «Экран гаснет во время просмотра».

## Остальные пункты экрана

| Раздел | Поле `Settings` | По умолчанию |
|---|---|---|
| Язык | `language` | язык системы |
| Тема | `themeId` | `cinema` |
| Размер, цвет, фон, обводка субтитров, игнор стилей ASS | `subtitleStyle` | размер 1, белый текст, контур |
| Языки субтитров | `preferredSubtitleLanguages` | `ru` |
| Не выключать экран | `keepScreenOn` | `true` |
| Продолжать с места остановки | `autoResume` | `true` |
| Запоминать выбор дорожек | `smartTracks` | `true` |
| Следующий файл | `autoPlayNext` | `true` |
| Ночной звук / по расписанию | `nightMode`, `nightAuto`, `nightStartMinute`, `nightEndMinute` | выкл, 23:00–10:00 |
| Вступления и титры | `skipMode` | кнопка |
| Скорость, шаг перемотки | `defaultSpeed`, `seekStepSeconds` | 1×, 10 с |
| Языки озвучки | `preferredAudioLanguages` | `ru` |
| Декодер | `decoder` | авто |
| Частота экрана под видео | `frameRateMatching` | выкл |
| Туннельный режим | `tunneling` | выкл |
| Экономия батареи для звука | `audioOffload` | вкл |
| Статистика поверх видео | `statsOverlay` | выкл |
| Картинка в картинке | `pictureInPicture` | вкл |
| Видео в фоне | `backgroundPlayback` | выкл |
| Музыка в фоне | `backgroundAudio` | вкл |
| Задержка звука | `audioDelayMs` | 0 |

Защита передачи между устройствами и автопроверка обновлений лежат в других preference-файлах
(`p2160_handoff`, `p2160_update`), не в `Settings`. См. [HTTP API](Http-API.md).

Полные таблицы и момент применения каждого поля — [API.md §9](../API.md#9-настройки-и-темы).
