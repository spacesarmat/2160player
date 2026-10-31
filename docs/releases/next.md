Версия не менялась: это правки к 0.2.17, выпуск будет отдельно.

**Конец файла**
- Медленный поток (например TorrServer по HTTP) больше не считается досмотренным, если плеер сообщил конец далеко от длительности. Позиция вроде 1:20 из 24:01 не закрывает серию, не ставит `playback_completion` и не записывает файл как досмотренный.
- Конец принимается, только когда длительность известна и позиция в том же допуске, что и «досмотрено» (больше 97 % или хвост короче 30 с у ролика длиннее 30 с).
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

**English:** A slow HTTP stream that reports the end far from the duration is no longer treated as watched: playback resumes from that position, with a bounded retry and then the usual error dialog. `playback_completion` is sent only after a verified end (known duration, inside the existing finish tolerance). A short video with a longer external audio track still ends at the video, and a real end still advances a playlist. The buffering spinner is drawn inside the same centre button, so D-pad focus survives closing a side panel and rebuffering. If the controls are visible and nothing is focused, OK pauses. The pause screensaver counts a real pause only (`playWhenReady` false and ready), not buffering. It dims the frame and shows Pause with the title. Any key wakes it; Back also does its usual action.
