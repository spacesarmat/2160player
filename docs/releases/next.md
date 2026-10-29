# Следующая версия

Черновик. Номер версии не повышался.

**Плеер**
- Внешние субтитры из «Загрузить из файла…» снова появляются в списке и на экране. Видео с тем же URI плеер обновлял на месте и не создавал источник для нового файла субтитров, поэтому после выбора оставались только «Выключены».
- Подходят `.srt`, `.ass`/`.ssa`, `.vtt`, `.ttml`. Источник может быть `content://` (системный выбор файла; тип `application/octet-stream` не мешает), `file://`, HTTP или SMB, а также extras `subs` и `subtitles_location`.
- Выбранный файл включается сразу, запоминается для этого видео и подключается снова при следующем открытии. Явный выбор важнее сохранённого «выключены».
- Текст приводится к UTF-8, включая BOM и UTF-16.
- Субтитры рядом с видео ищутся и для `content://` (открытие через «Открыть файл» или чужой просмотр). Если каталог читается по пути документа, вопрос не нужен. Иначе хватает уже сохранённого доступа к папке. Если доступа нет, системный выбор папки показывается один раз на эту папку (на Android 8+ сразу в ней) и запоминается. Отказ тоже запоминается, при каждом открытии плеер больше не спрашивает. На ТВ это обычный экран выбора, его ведёт пульт.

**English:** Picking an external subtitle in the player adds the track and shows it. A same-URI media item update was not creating a subtitle source, so the panel stayed on Off. SRT, ASS, VTT and TTML work from `content://` (including `application/octet-stream`), `file://`, HTTP, SMB and Intent extras. The file is selected immediately and restored for that video. Text is stored as UTF-8. Sidecars next to a `content://` video are found from a readable path or a saved folder grant. The folder picker appears at most once per folder, including on Android TV.

- Внешняя озвучка: файл рядом с видео (`Movie.ru.mka`, `Movie.rus.ac3` и другие, в том числе в папке `Audio` или `RUS Sound`) появляется в панели звука вместе со встроенными дорожками. То же для `file://`, SMB, HTTP без query и `content://` (тот же путь и то же дерево папки, что у субтитров). Ссылка с query (TorrServer) соседей сама не угадывает: дорожки передают в Intent.
- «Загрузить из файла…» в панели звука и extras `tv.p2160.extra.AUDIO_TRACKS` / `TEXT_TRACKS` подключают свои файлы на каждый пункт плейлиста. Выбор `id:`, `index:` или `embedded:` (для субтитров ещё `off`) включается сразу. Та же студия или язык переносится на следующую серию, если у неё есть такая дорожка. Переключение не начинает видео заново. Перемотка, пауза и скорость общие.
- Если внешний файл не открылся или кодек не поддерживается, плеер пишет предупреждение и продолжает видео. Режим Авто/FFmpeg и отдача на ресивер работают и для внешней дорожки, когда формат это позволяет. Сдвиг `delayMs` прибавляется к задержке пользователя.
- В результате к позиции добавлены id, имя, язык и признак внешняя/встроенная для звука и субтитров. Старые ключи MX и VLC на месте.

**English:** External dubs next to a video show up in the audio panel, including `Audio` and `RUS Sound` folders, for `file://`, SMB, HTTP without a query, and `content://` through the same path and folder grant as subtitles. TorrServer links with a query are not guessed. `tv.p2160.extra.AUDIO_TRACKS` and `TEXT_TRACKS` pass per-episode files. `AUDIO_SELECT` / `TEXT_SELECT` (`id:`, `index:`, `embedded:`, and `off` for subtitles) pick the start track, and the same studio or language follows the next episode when it has a match. Seek, pause and speed stay in sync. A missing or unsupported external file shows a warning and leaves the video playing. The result adds the selected audio and subtitle id, name, language and external flag next to the existing position fields.
