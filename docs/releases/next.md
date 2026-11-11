# 0.2.26 (ещё не выпущено)

Десктопное приложение `desktop/`, версия `0.2.26`. Номер совпадает со следующим релизом Android.
`versionName` Android в этом изменении не двигается: он остаётся `0.2.25` (`versionCode` 37), пока владелец не соберёт релиз.

- Окно Compose для macOS, Windows и Linux. Воспроизведение через libmpv, библиотеки внутри установщика.
- Локальный файл (диалог, перетаскивание, аргумент), пауза, полоса с прошедшим и оставшимся временем, громкость, полный экран, дорожки звука и субтитров, внешние субтитры, продолжение с места, недавние файлы. Интерфейс на русском, тема «Кино».
- Сеть: SMB, DLNA, плейлисты M3U, прямые ссылки (HTTP, HLS, RTSP, RTMP, SRT, если их умеет FFmpeg сборки), торренты с просмотром во время загрузки (`TorrentSession` и libtorrent4j).
- NDI на десктоп не перенесён: в дереве нет бинарников NDI SDK для этих ОС.
- CI: `.github/workflows/desktop.yml` собирает `.dmg` (файл `2160player-2.26.dmg`, потому что jpackage на macOS не принимает версию с нуля впереди), `.msi`, `.deb` (`p2160player`, потому что имя Debian не может начинаться с цифры) и `.tar.gz` и кладёт их в артефакты. Релиз и тег не создаются.

**English:** Desktop app `desktop/` at version 0.2.26, the next number after the Android release 0.2.25. The Android `versionName` is unchanged. Compose window, libmpv bundled, local playback, SMB, DLNA, M3U, direct URLs and torrents while downloading. No NDI on desktop. CI uploads installers and does not publish a release.
