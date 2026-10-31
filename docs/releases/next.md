Версия не менялась: это правки к 0.2.17, выпуск будет отдельно.

**Конец файла**
- Медленный поток (например TorrServer по HTTP) больше не считается досмотренным, если плеер сообщил конец далеко от длительности. Позиция вроде 1:20 из 24:01 не закрывает серию, не ставит `playback_completion` и не записывает файл как досмотренный.
- Конец принимается, только когда длительность известна и позиция в том же допуске, что и «досмотрено» (больше 97 %, или у ролика от 10 минут хвост короче 30 с).
- Иначе источник открывается снова с текущей позиции. После нескольких попыток показывается обычная ошибка с «Повторить» и «Закрыть».
- Короткое видео с более длинной внешней дорожкой по-прежнему заканчивается на конце видео. Плейлист после настоящего конца переходит к следующему, как раньше.

**Пульт**
- Индикатор буферизации рисуется внутри центральной кнопки. Кнопка одна и та же, фокус с неё не сходит, когда после закрытия панели звука дорожка переключается и поток снова буферизуется.
- После закрытия панели фокус запрашивается на следующем кадре и повторяется, если первый запрос не прошёл.
- Если кнопки видны, а фокуса нигде нет, OK ставит паузу, а не перематывает и не открывает следующую главу.

**Заставка при паузе**
- Настройка «Заставка при паузе» на месте. Она считает только паузу: воспроизведение не запрошено и плеер уже готов. Долгая буферизация заставку не включает.
- Через 3 минуты кадр затемняется, по центру «Пауза» и название. Любая клавиша или касание возвращает картинку. Назад ещё и делает своё обычное действие. Стрелки и OK могут только разбудить.
- «Остаться на кадре» по-прежнему гаснет через 3 минуты и при выключенной заставке.

**Обрыв после старта**
- Если кадры уже шли и потом остановились, или позиция ушла дальше загруженного куска, это не «Воспроизведение не началось». Плеер открывает источник снова с последней позиции, где ещё были кадры (три попытки, затем «Поток оборвался»). Счётчик обнуляется, только когда кадры ушли на 10 с дальше точки обрыва. Идущие часы при замершей картинке попытки не сбрасывают. Когда попытки кончились, плеер встаёт на паузу. В результат и в историю пишется последняя позиция с кадрами, не ушедшие часы. «Повторить» продолжает с неё. Закрытие плеера останавливает сторож и отменяет уже запланированный повтор. Файл не помечается досмотренным и сам не закрывается.
- Так бывает у обрезанного MKV и у HTTP, который отдаёт меньше байт, чем обещает заголовок: `STATE_ENDED` не приходит, картинка замирает, а часы идут дальше.

**Результат с file://**
- В ответ больше не кладётся `file://` в `data`. Иначе закрытие с `return_result` падает с `FileUriExposedException`. Позиция, длительность и остальные extras остаются. `http` и `content` в `data` по-прежнему есть.

**Короткие ролики**
- Хвост короче 30 с считается досмотром только у ролика от 10 минут. Пауза на 18.4 с из 40 с и Назад дают `end_by=user`, не `playback_completion`. У коротких остаётся порог 97 %.

**Субтитры и смена звука**
- Явные и внешние субтитры (в том числе из `TEXT_TRACKS` с `select`, без `TEXT_OFF`) остаются выбранными, когда пользователь переключает звук, включая переход на встроенную дорожку. Селектор больше не снимает их из-за нового языка озвучки.

**English:** A slow HTTP stream that reports the end far from the duration is no longer treated as watched: playback resumes from that position, with a bounded retry and then the usual error dialog. The same retry runs when playback has already shown frames and then stalls, or when the clock runs past the buffered data: the message is "The stream stopped", not "Playback did not start". The retry counter resets only after rendered frames move 10 seconds past the break. A frozen picture with a running clock does not reset it, so the same break ends in the error after 3 retries. That error pauses playback. The result and the resume point keep the last rendered position, and Retry starts there again. Closing the player stops the watchdog and cancels a pending reopen. Other fatal error dialogs pause as well. `playback_completion` is sent only after a verified end. The 30-second tail counts as finished only for titles of at least 10 minutes; a 40-second clip paused at 18 seconds is not watched. A `file://` URI is omitted from the result Intent data so closing with `return_result` does not throw `FileUriExposedException`. An explicit or external subtitle stays selected when the audio track changes. A short video with a longer external audio track still ends at the video, and a real end still advances a playlist. The buffering spinner is drawn inside the same centre button, so D-pad focus survives closing a side panel and rebuffering. If the controls are visible and nothing is focused, OK pauses. The pause screensaver counts a real pause only (`playWhenReady` false and ready), not buffering. It dims the frame and shows Pause with the title. Any key wakes it; Back also does its usual action.
