# Desktop player implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a separate desktop module of 2160 Player that plays local files and the Android app's network sources on macOS, Windows and Linux, with CI installers and no GitHub release.

**Architecture:** `desktop/` is its own Gradle build so the Android project, its plugins and its CI stay untouched. Compose Multiplatform draws the window. libmpv decodes and renders a software frame (`MPV_RENDER_API_TYPE_SW`) into that window, and keeps audio inside libmpv. SMB and torrents are not given to libmpv as custom protocols: a loopback HTTP server answers Range requests. SMB uses smbj. Torrents use the existing JVM class `TorrentSession` (compiled from `source-torrent`, not forked). DLNA and M3U use the existing JVM parsers in `player-core`.

**Tech Stack:** Kotlin 2.4.20, Compose Multiplatform 1.12.2, JDK 21, JNA 5.17.0, libmpv 0.37 or newer, smbj 0.15.0, libtorrent4j 2.1.0-39, jpackage.

**Spec:** Owner request for a desktop MVP plus network sources. There is no separate spec file. The requirements that bind every task are the Global Constraints below.

## Global Constraints

- Do not change Android source, Android version numbers, or `.github/workflows/android.yml`.
- UI strings are Russian. Theme is the cinema theme: background `#0E0E10`, surface `#1A1A1E`, accent `#FFB020`.
- Never use an em dash or an en dash in code, UI, docs, commits or the PR. Use a hyphen.
- Desktop version is `0.2.26`, the next number after Android `0.2.25` (`versionCode` 37). Do not bump the Android `versionName`.
- Do not create a GitHub release or tag.
- Bundle libmpv. Users do not install mpv or VLC. The desktop OpenGL, Vulkan and audio libraries stay on the OS.
- NDI is out of this PR: the repo only has Android headers, `libndi` is not committed, and the desktop SDK is a click-through download. Say that in the docs.
- Network in scope: SMB browse, DLNA browse, IPTV M3U, direct URLs (HTTP, HLS, RTSP, RTMP, SRT when the bundled FFmpeg has them), torrents with playback while pieces download.
- Update README, `docs/` (including `docs/API.md`), `docs/wiki/`, and `docs/releases/next.md`.
- Open a PR into `main`. Do not merge it.

## Review Focus

These are the inputs most likely to break a real session. Each one has a test on the task that owns it.

- A second dimension written with `||` never updates, so a 640x360 surface stays 640x0 and no frame is drawn. Task 2.
- `SessionManager` is constructed before `libtorrent4j.jni.path` is set, so the window dies during startup with `UnsatisfiedLinkError: no torrent4j`. Task 4.
- An M3U file that is really an HLS manifest (`looksLikeHls`, or no channels) must open as one stream, not as an empty channel list. Task 3.
- A Range request that starts at or past the file length must be 416, not a 200 with a negative length. Task 1.
- Cycling past the last audio or subtitle track must select "off" (`null`), not stick on the last id. Task 2.

---

## File map

| File | Responsibility |
|---|---|
| `desktop/build.gradle.kts` | Compose application, jpackage, copy of shared JVM sources, stage libmpv before `run` and package tasks |
| `desktop/scripts/stage-libmpv.py` | Vendor libmpv plus codec libraries for linux-x64, macos-arm64 or windows-x64 |
| `desktop/src/main/kotlin/tv/p2160/desktop/Main.kt` | Window, keys, file dialog, drag and drop, route switch |
| `desktop/src/main/kotlin/tv/p2160/desktop/DesktopController.kt` | Navigation, open file/url/SMB/DLNA/IPTV/torrent, resume key |
| `desktop/src/main/kotlin/tv/p2160/desktop/player/MpvPlayer.kt` | libmpv lifecycle, commands, software frame |
| `desktop/src/main/kotlin/tv/p2160/desktop/player/NativeLibs.kt` | Find and load the bundled libmpv |
| `desktop/src/main/kotlin/tv/p2160/desktop/media/LibraryStore.kt` | Recent files, resume positions, SMB passwords, volume |
| `desktop/src/main/kotlin/tv/p2160/desktop/media/RangeServer.kt` | Loopback HTTP Range server |
| `desktop/src/main/kotlin/tv/p2160/desktop/media/TimeText.kt` | Clock text and resume policy |
| `desktop/src/main/kotlin/tv/p2160/desktop/media/SmbBrowser.kt` | smbj listing and reads |
| `desktop/src/main/kotlin/tv/p2160/desktop/media/SmbPaths.kt` | Pure SMB path join |
| `desktop/src/main/kotlin/tv/p2160/desktop/media/TrackPicker.kt` | Pure next-track choice |
| `desktop/src/main/kotlin/tv/p2160/desktop/media/SurfaceSize.kt` | Pure width and height assignment |
| `desktop/src/main/kotlin/tv/p2160/desktop/media/TorrentNatives.kt` | Extract libtorrent4j JNI before `SessionManager` |
| `desktop/src/main/kotlin/tv/p2160/desktop/media/TorrentClient.kt` | `TorrentSession` plus a blocking `ByteSource` |
| `desktop/src/main/kotlin/tv/p2160/desktop/ui/` | Russian Compose screens |
| `.github/workflows/desktop.yml` | Matrix: ubuntu, windows, macos. Upload installers. Do not release. |
| `docs/wiki/Desktop.md` | Install and use, per OS |
| `docs/releases/next.md` | Unreleased 0.2.26 notes |

Shared sources compiled into the desktop jar, not copied into git: `TorrentSession.kt`, `PiecePlanner.kt`, `Magnet.kt`, `M3uParser.kt`, and the DLNA files that do not import Android (`Ssdp.kt`, `DlnaHttp.kt`, `DlnaServer.kt`, `DlnaProbe.kt`, `ContentDirectory.kt`, `Didl.kt`, `XmlLite.kt`).

Playback facts already measured on this machine, do not rediscover them:

- Call `setlocale(LC_NUMERIC, "C")` before `mpv_create`, or it returns null.
- Create the render context on the thread that will call `mpv_render_context_render`. On the desktop app that is the UI thread.
- `mpv_render_context_update` on a second thread never returned here. Render from the UI thread on a frame clock instead.
- Pixel format is `bgr0`. Force the fourth byte to `0xFF` before giving the buffer to Skia as `BGRA_8888`.
- Cap the frame at 1920x1080.

## Task 1: Resume, clock, Range

**Files:**
- Modify: `desktop/src/main/kotlin/tv/p2160/desktop/media/TimeText.kt`
- Modify: `desktop/src/main/kotlin/tv/p2160/desktop/media/RangeServer.kt`
- Modify: `desktop/src/main/kotlin/tv/p2160/desktop/media/LibraryStore.kt`
- Test: `desktop/src/test/kotlin/tv/p2160/desktop/DesktopLogicTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces: `formatClock(seconds: Double): String`, `formatRemaining(positionSec: Double, durationSec: Double): String`, `shouldOfferResume(positionSec: Double, durationSec: Double): Boolean`, `parseRange(header: String?, length: Long): Pair<Long, Long>?`, `LibraryStore`

- [ ] **Step 1: Write the failing test**

Assert `parseRange("bytes=100-200", 100)` is null, `parseRange("bytes=10-20", 100)` is `10L to 21L`, `shouldOfferResume(580.0, 600.0)` is false, `shouldOfferResume(400.0, 600.0)` is true, and a `LibraryStore` round-trip keeps position `12.5` and volume `40`.

- [ ] **Step 2: Run `./gradlew test --tests tv.p2160.desktop.DesktopLogicTest --offline`**

Expected: FAIL on the missing function, not on a typo.

- [ ] **Step 3: Implement the signatures above**

`shouldOfferResume` is false below 10 seconds, above 97 percent, and when a title of at least 600 seconds has under 30 seconds left.

- [ ] **Step 4: Re-run the same test**

Expected: PASS.

## Task 2: Surface size and track choice

**Files:**
- Create: `desktop/src/main/kotlin/tv/p2160/desktop/media/SurfaceSize.kt`
- Create: `desktop/src/main/kotlin/tv/p2160/desktop/media/TrackPicker.kt`
- Modify: `desktop/src/main/kotlin/tv/p2160/desktop/player/MpvPlayer.kt` (`setSurfaceSize`, `cycle`)
- Test: `desktop/src/test/kotlin/tv/p2160/desktop/TrackAndSurfaceTest.kt`

**Interfaces:**
- Consumes: `MediaTrack(id, type, title, lang, selected)` from `MpvPlayer.kt`
- Produces: `fun assignSurface(width: Int, height: Int, maxW: Int = 1920, maxH: Int = 1080): Pair<Int, Int>` and `fun nextTrackId(tracks: List<MediaTrack>): Int?`

- [ ] **Step 1: Write the failing test**

`assignSurface(640, 360)` is `640 to 360` even when called as one expression (both components present). `assignSurface(-1, 5000)` is `0 to 1080`. `nextTrackId` of an empty list is null. Of one selected track, the result is null (off). Of track 1 selected and track 2 present, the result is `2`.

- [ ] **Step 2: Run `./gradlew test --tests tv.p2160.desktop.TrackAndSurfaceTest --offline`**

Expected: FAIL, unresolved `assignSurface` or `nextTrackId`.

- [ ] **Step 3: Implement both functions and call them from `MpvPlayer`**

`setSurfaceSize` writes both atomics from the pair. Do not join the writes with `||`. `cycle` asks `nextTrackId` and then `selectAudio` or `selectSub`.

- [ ] **Step 4: Re-run the test**

Expected: PASS.

## Task 3: M3U and SMB path adapter

**Files:**
- Create: `desktop/src/main/kotlin/tv/p2160/desktop/media/SmbPaths.kt`
- Modify: `desktop/src/main/kotlin/tv/p2160/desktop/media/SmbBrowser.kt` (use `SmbPaths.child`)
- Modify: `desktop/src/main/kotlin/tv/p2160/desktop/DesktopController.kt` (`applyPlaylist`)
- Test: `desktop/src/test/kotlin/tv/p2160/desktop/SourceAdapterTest.kt`

**Interfaces:**
- Consumes: `tv.p2160.core.iptv.M3uParser.parse(text: String, baseUrl: String?): M3uPlaylist`
- Produces: `object SmbPaths { fun child(parent: String, name: String): String }` and `fun playlistOpensAsStream(text: String, baseUrl: String): Boolean`

- [ ] **Step 1: Write the failing test**

`SmbPaths.child("", "movie.mkv")` is `movie.mkv`. `SmbPaths.child("films", "a.mkv")` is `films\a.mkv`. `SmbPaths.child("films/", "a.mkv")` does not produce a double separator. `playlistOpensAsStream` is true for an HLS manifest (`#EXTM3U` plus `#EXT-X-STREAM-INF` or `M3uParser.looksLikeHls`) and false for one `#EXTINF` channel. The channel test also asserts the shared parser: name `Первый`, url `http://example/1.m3u8`, group `Новости`.

- [ ] **Step 2: Run `./gradlew test --tests tv.p2160.desktop.SourceAdapterTest --offline`**

Expected: FAIL, unresolved symbol.

- [ ] **Step 3: Implement `SmbPaths.child` and `playlistOpensAsStream`**

`playlistOpensAsStream` is `playlist.looksLikeHls || playlist.channels.isEmpty()`. `applyPlaylist` calls it and either `openUrl(..., live = true)` or stores `playlist.channels`.

- [ ] **Step 4: Re-run the test**

Expected: PASS.

## Task 4: Torrent native load and piece adapter

**Files:**
- Create: `desktop/src/main/kotlin/tv/p2160/desktop/media/TorrentNatives.kt`
- Modify: `desktop/src/main/kotlin/tv/p2160/desktop/media/TorrentClient.kt` (call `ensureLoaded` before the first `TorrentSession`)
- Modify: `desktop/src/main/kotlin/tv/p2160/desktop/DesktopController.kt` (do not construct `TorrentSession` while the window is opening)
- Test: `desktop/src/test/kotlin/tv/p2160/desktop/TorrentNativeTest.kt`

**Interfaces:**
- Consumes: `TorrentSession`, `PiecePlanner`, `FileSpan` from `tv.p2160.torrent`
- Produces: `object TorrentNatives { fun resourcePath(osName: String, osArch: String): String; fun ensureLoaded() }`

Resource paths, copied from the 2.1.0-39 jars:

- Linux x86_64: `lib/x86_64/libtorrent4j.so`
- Linux arm: `lib/arm/libtorrent4j.so`
- macOS (os.arch `aarch64` or `arm64`): `lib/arm64/libtorrent4j.dylib`
- Windows: `lib/x86_64/libtorrent4j.dll`

- [ ] **Step 1: Write the failing test**

Assert those four paths. Assert `PiecePlanner.deadlines` on a 10_000_000 byte `FileSpan` with `pieceLength` 1_048_576, `wantHead` true and `wantTail` true, is not empty. Assert `ensureLoaded()` sets `libtorrent4j.jni.path` to an existing file whose name ends with `libtorrent4j.so` on Linux, and that `TorrentSession(SessionConfig()).use { it.start(); it.isRunning }` is true.

- [ ] **Step 2: Run `./gradlew test --tests tv.p2160.desktop.TorrentNativeTest --offline`**

Expected: FAIL because `TorrentNatives` does not exist. The piece assertion can already pass via the shared source; that is fine, it locks the shared math.

- [ ] **Step 3: Implement `ensureLoaded`**

Read the resource with the current class loader. Copy it once to the temp directory. Set `libtorrent4j.jni.path` to that absolute path before any reference that initializes `org.libtorrent4j.swig.libtorrent_jni`. `TorrentClient` calls `ensureLoaded()` inside a lazy `TorrentSession`, not in a field initializer that runs from the Compose UI thread before the property is set.

Root cause already traced: `libtorrent_jni.<clinit>` reads `libtorrent4j.jni.path` and otherwise calls `System.loadLibrary("torrent4j")`, which does not see the jar entry. Symptom on the UI thread: `UnsatisfiedLinkError: no torrent4j`.

- [ ] **Step 4: Re-run the test**

Expected: PASS, including `session.isRunning`.

## Task 5: Docs, wiki, CI installers

**Files:**
- Create: `docs/wiki/Desktop.md`
- Create: `docs/releases/next.md`
- Create: `.github/workflows/desktop.yml`
- Modify: `README.md`, `docs/API.md`, `docs/wiki/Home.md`, `docs/wiki/Architecture.md`, `docs/wiki/Setup.md`, `docs/wiki/Sources.md`, `docs/wiki/Playback.md`

**Interfaces:**
- Consumes: package names from `desktop/build.gradle.kts`: `2160player` version `0.2.26`
- Produces: workflow artifacts, no git tag

- [ ] **Step 1: Write `docs/wiki/Desktop.md` and `docs/releases/next.md`**

Per OS: what file to download, that the macOS dmg is unsigned, that Linux needs the desktop GL and audio libraries listed in the deb depends, keyboard list, and that NDI is absent and why. `next.md` is the unreleased 0.2.26 note. Android `versionName` stays 0.2.25 until the owner cuts the release.

- [ ] **Step 2: Link the page from README, API.md and the wiki pages listed above**

One short section each. No em dash or en dash.

- [ ] **Step 3: Add `.github/workflows/desktop.yml`**

Matrix `ubuntu-latest`, `windows-latest`, `macos-latest`. Install libmpv (apt or brew) or download the pinned shinchiro x64 build `20261110` (`mpv-dev-x86_64-20261110-git-a61f6c3abd.7z` and `mpv-x86_64-20261110-git-a61f6c3abd.7z`). Windows needs WiX 3, not WiX 4. Upload `.deb`, `.tar.gz`, `.msi`, `.dmg`. `permissions: contents: read`. No `softprops/action-gh-release` and no tag.

- [ ] **Step 4: Run `./gradlew test --offline` in `desktop/`**

Expected: all tests PASS. Then `./gradlew run --args="--dump-frame /tmp/p2160-frame.png /tmp/p2160-play.mp4" --offline` exits 0 and the PNG is 640x360 with more than one color.

## Task 6: Verify on the machine and open the PR

**Files:** none new, unless a verification bug forces a test back in Tasks 1-4.

- [ ] **Step 1: Play `/tmp/p2160-play.mp4` in the window on `DISPLAY=:1` and save a screenshot**

The window title is `2160 Player`. The frame is the color-bar sample, not a blank surface.

- [ ] **Step 2: Exercise network as far as this VM allows**

HLS URL via `--dump-frame`. SMB against a local `smbserver` or Samba. DLNA against MiniDLNA on `127.0.0.1:8200` or `DlnaProbe`. Torrent: the Task 4 session start, plus a legal swarm or a local seed if the network allows. Record the exact command and result. Do not claim a path that was not run.

- [ ] **Step 3: Request a code review of the branch against this plan**

Fix Critical and Important findings.

- [ ] **Step 4: Open the PR into `main` with a Russian body**

Do not merge. Do not tag.
