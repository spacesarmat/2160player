# Следующая версия

Черновик. Номер версии не повышался.

**Плеер**
- Внешние субтитры из «Загрузить из файла…» снова появляются в списке и на экране. Видео с тем же URI плеер обновлял на месте и не создавал источник для нового файла субтитров, поэтому после выбора оставались только «Выключены».
- Подходят `.srt`, `.ass`/`.ssa`, `.vtt`, `.ttml`. Источник может быть `content://` (системный выбор файла; тип `application/octet-stream` не мешает), `file://`, HTTP или SMB, а также extras `subs` и `subtitles_location`.
- Выбранный файл включается сразу, запоминается для этого видео и подключается снова при следующем открытии. Явный выбор важнее сохранённого «выключены».
- Текст приводится к UTF-8, включая BOM и UTF-16. Рядом с `content://` субтитры по-прежнему сами не ищутся: выбора одного файла недостаточно, чтобы прочитать каталог.

**English:** Picking an external subtitle in the player adds the track and shows it. A same-URI media item update was not creating a subtitle source, so the panel stayed on Off. SRT, ASS, VTT and TTML work from `content://` (including `application/octet-stream`), `file://`, HTTP, SMB and Intent extras. The file is selected immediately and restored for that video. Text is stored as UTF-8. A `content://` video still does not auto-pick sidecars, because the document grant is not a directory listing.
