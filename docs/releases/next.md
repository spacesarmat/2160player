Версия не менялась: это правки к 0.2.18, выпуск будет отдельно.

**Пульт во время буферизации**
- Пока идёт буферизация, фокус D-pad больше не прыгает по кнопкам. Он остаётся там, куда его поставили, поэтому «Аудио» и «Субтитры» открываются без предварительной паузы.
- На центральную кнопку фокус ставится при первом показе управления и после закрытия боковой панели, если внутри кнопок его ещё нет. Переход buffering/ready сам по себе фокус не двигает.
- Касания на телефоне не менялись. Индикатор загрузки по-прежнему внутри той же центральной кнопки.

**English:** While the player is buffering, D-pad focus stays on the control the user selected, so the audio and subtitle buttons can be opened without pausing first. Focus moves to the centre button only when the controls first appear or a side panel has just closed and nothing in the controls is focused. A buffering/ready change does not move it. Touch behaviour is unchanged.
