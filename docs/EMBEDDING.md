# Встраивание 2160 Player за 5 минут

Пошаговое руководство для разработчика стороннего приложения. Полный справочник — [API.md](API.md),
рабочий пример — модуль [`samples/embed-demo`](../samples/embed-demo).

> **Лицензия.** 2160 Player распространяется под [GPL v3](../LICENSE): приложение, в которое
> встроена библиотека `player-core`, тоже должно быть открытым под GPL v3. Intent API (шаг 0) этого
> не требует — вы лишь вызываете установленное отдельно приложение.

> Не хотите тянуть библиотеку в APK? Используйте Intent API — пользователь ставит приложение
> 2160 Player, а вы отправляете ему Intent (совместимо с MX Player/VLC). См. [шаг 0](#шаг-0-вариант-без-библиотеки-intent-api).

## Требования

- Android Gradle Plugin с поддержкой `compileSdk = 37` (проект собирается AGP 9.4, Kotlin 2.4, JDK 21 для Gradle, toolchain 17).
- `minSdk` вашего приложения ≥ 24.
- Jetpack Compose в модуле, если вы встраиваете `PlayerScreen` или создаёте `PlayerAction` (иконка — `ImageVector`).

---

## Шаг 0. Вариант без библиотеки: Intent API

```kotlin
val intent = Intent(Intent.ACTION_VIEW)
    .setDataAndType(Uri.parse("https://example.com/movie.mkv"), "video/*")
    .setPackage("tv.p2160.player")                 // debug-сборка: tv.p2160.player.debug
    .putExtra("title", "Фильм")
    .putExtra("return_result", true)

val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
    val positionMs = r.data?.getIntExtra("position", 0)
}
launcher.launch(intent)
```

Все ключи и форматы — [API.md §3](API.md#3-intent-api-без-библиотеки). Дальше — путь с библиотекой.

---

## Шаг 1. Подключение

Выберите один из вариантов.

### 1a. Модуль в том же проекте (как `app` и `samples/embed-demo` здесь)

```kotlin
// settings.gradle.kts
include(":player-core")
// project(":player-core").projectDir = file("../2160player/player-core")   // если исходники лежат рядом

// app/build.gradle.kts
dependencies { implementation(project(":player-core")) }
```

Модулю `player-core` нужен тот же version catalog (`gradle/libs.versions.toml`) с алиасами
`libs.plugins.android.library`, `libs.plugins.kotlin.compose` и библиотеками из его `build.gradle.kts`.
Если у вас свой каталог — проще вариант 1b.

### 1b. Composite build (`includeBuild`) — исходники 2160 Player отдельным репозиторием

```sh
git clone https://github.com/spacesarmat/2160player.git ../2160player
```

```kotlin
// settings.gradle.kts вашего проекта
includeBuild("../2160player") {
    dependencySubstitution {
        substitute(module("tv.p2160:player-core")).using(project(":player-core"))
    }
}

// app/build.gradle.kts
dependencies { implementation("tv.p2160:player-core:0.1.5") }
```

Gradle соберёт библиотеку из исходников со своим каталогом версий; правки в `../2160player`
сразу видны вашему приложению. Для сборки 2160 Player нужен `local.properties` с `sdk.dir`
(или переменная `ANDROID_HOME`).

### 1c. mavenLocal

В репозитории 2160 Player:

```sh
./gradlew :player-core:publishReleasePublicationToMavenLocal
# другая версия: ./gradlew :player-core:publishReleasePublicationToMavenLocal -PplayerCoreVersion=0.2.0-SNAPSHOT
```

Артефакт появится в `~/.m2/repository/tv/p2160/player-core/0.1.5/` (AAR, POM, Gradle module metadata, sources).

```kotlin
// settings.gradle.kts вашего проекта
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        mavenLocal()
    }
}

// app/build.gradle.kts
dependencies { implementation("tv.p2160:player-core:0.1.5") }
```

### 1d. GitHub Packages

**Публикация** (мейнтейнеры; токен с правом `write:packages`):

```sh
export GITHUB_ACTOR=<логин>
export GITHUB_TOKEN=<токен>
./gradlew :player-core:publishReleasePublicationToGitHubPackagesRepository -PplayerCoreVersion=0.1.5
```

Вместо переменных окружения можно задать `gpr.user` / `gpr.key` в `~/.gradle/gradle.properties`.
В GitHub Actions достаточно `GITHUB_TOKEN` с `permissions: packages: write`. Никогда не
коммитьте токены в репозиторий.

**Подключение** (токен с правом `read:packages` — GitHub Packages требует авторизацию даже для публичных пакетов):

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            name = "GitHubPackages2160"
            url = uri("https://maven.pkg.github.com/spacesarmat/2160player")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
                password = providers.gradleProperty("gpr.key").orNull ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}

// app/build.gradle.kts
dependencies { implementation("tv.p2160:player-core:0.1.5") }
```

---

## Шаг 2. Настройка модуля приложения

```kotlin
// app/build.gradle.kts
android {
    compileSdk = 37
    defaultConfig { minSdk = 24 }

    // ОБЯЗАТЕЛЬНО: две зависимости nextlib несут одинаковые .so FFmpeg.
    // Без этого сборка падает на mergeDebugNativeLibs ("2 files found with path 'lib/arm64-v8a/libavcodec.so'").
    packaging {
        jniLibs.pickFirsts += listOf(
            "**/libavcodec.so", "**/libavutil.so", "**/libswscale.so", "**/libswresample.so",
        )
    }

    // Рекомендуется: FFmpeg добавляет ~8 МБ на ABI.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = true
        }
    }
}
```

Манифест трогать не нужно: библиотека сама добавит `Player2160Activity`, сервис медиасессии
`PlaybackService` (уведомление с управлением, экран блокировки, гарнитура, фоновое воспроизведение),
разрешения `INTERNET`, `ACCESS_NETWORK_STATE`, `CHANGE_WIFI_MULTICAST_STATE` (поиск DLNA),
`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` и `usesCleartextTraffic`.
Торренты — отдельный модуль `:source-torrent` ([API.md §21](API.md#21-торренты-модуль-source-torrent)). ProGuard-правила подключаются автоматически (`consumer-rules.pro`).

---

## Шаг 3. Запуск плеера

```kotlin
import tv.p2160.core.api.PlaybackRequest
import tv.p2160.core.api.Player2160

Player2160.play(context, PlaybackRequest.single(Uri.parse("https://example.com/movie.mkv"), "Фильм"))
```

Подходят `http(s)`, HLS/DASH, `rtsp`, `file://`, `content://`, `smb://host/share/file.mkv`,
а также `.iso`/папка `BDMV` Blu-ray — без дополнительного кода.

С возвратом позиции:

```kotlin
class MainActivity : ComponentActivity() {
    private val player = registerForActivityResult(Player2160.PlayContract()) { result ->
        result?.let { Log.d("Demo", "Остановились на ${it.positionMs} из ${it.durationMs}, конец: ${it.completed}") }
    }

    fun watch(uri: Uri) = player.launch(PlaybackRequest.single(uri, "Фильм"))
}
```

---

## Шаг 4. «Сейчас играет» и своя кнопка

```kotlin
// Application.onCreate — кнопка в верхней панели плеера
Player2160.registerAction(
    PlayerAction("share", Icons.Default.Share, "Поделиться ссылкой") { context, nowPlaying ->
        val uri = nowPlaying?.uri ?: return@PlayerAction
        context.startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, uri.toString()), null,
        ))
    }
)

// Любой Compose-экран — мини-плеер
val np by Player2160.nowPlaying.collectAsStateWithLifecycle()
np?.let { Text("${it.title}: ${it.positionMs / 1000} c, ${if (it.isPlaying) "играет" else "пауза"}") }
```

---

## Шаг 5 (по желанию). Плеер внутри своего экрана

```kotlin
val controller = PlayerController(applicationContext, PlaybackRequest.single(uri))   // главный поток; сразу играет
setContent {
    PlayerScreen(
        controller = controller,
        settingsStore = Player2160.settings(this),
        onBack = ::finish,
        onPickSubtitle = { pickSubtitle.launch(arrayOf("*/*")) },
    )
}
// onStart: controller.setInBackground(false)
// onStop:  controller.saveProgress(); затем либо controller.player.pause(),
//          либо (фоновое воспроизведение) controller.setInBackground(true) — см. API.md §15.4
// когда экран закрывается навсегда: controller.release()
```

Полный пример с ViewModel, PiP и выбором субтитров — [API.md §6.2](API.md#62-полный-пример-activity)
и `samples/embed-demo/src/main/java/tv/p2160/sample/embed/EmbeddedPlayerActivity.kt`.

---

## Шаг 6 (по желанию). Настройки и тема

```kotlin
Player2160.settings(context).update {
    it.copy(
        themeId = "amoled",
        skipMode = SkipMode.AUTO,
        preferredAudioLanguages = listOf("en", "ru"),
        resizeMode = ResizeMode.FIT_WIDTH,   // FIT, FIT_WIDTH, FIT_HEIGHT, ZOOM, FILL
        backgroundPlayback = true,           // видео звучит, когда плеер свёрнут
    )
}
```

Движок (буфер, тайм-ауты, фоновая работа) настраивается отдельно — до создания плеера:

```kotlin
Player2160.config = PlayerConfig(
    bufferTargetBytes = 32 * 1024 * 1024,   // слабые ТВ: меньше памяти на буфер (по умолчанию 64 МБ)
    readTimeoutMs = 90_000,                 // медленные источники
    introDetection = false,                 // отрезки даёт ваш сервер
    readChapters = false,
    restoreFromHistory = false,             // позицию и дорожки выбирает ваше приложение
    saveHistory = false,
)
```

Все поля — [API.md §15.6](API.md#156-настройка-движка-playerconfig).

Масштаб пользователь меняет и сам: панель «Видео» в плеере или щипок двумя пальцами
(развести — заполнить экран, свести — целиком). Выбор сохраняется в `Settings.resizeMode`.

Темы: `cinema`, `ocean`, `ruby`, `mint`, `amoled`, `light`. Свои строки и переводы —
`app/src/main/assets/i18n/<ваш_модуль>/<код>.json` ([API.md §13](API.md#13-локализация)).

---

## Пример `samples/embed-demo`

```sh
./gradlew :samples:embed-demo:assembleDebug
adb install -r samples/embed-demo/build/outputs/apk/debug/embed-demo-debug.apk
```

Демонстрирует: `Player2160.play`, `PlayContract` с показом позиции, `PlayerScreen` в своей Activity
(с панелью приложения сверху, PiP и выбором субтитров), кнопку «Поделиться ссылкой» через
`registerAction`, полосу «Сейчас играет» на `nowPlaying` и вызов отдельного приложения 2160 Player
через Intent API (`tv.p2160.player` / `tv.p2160.player.debug`, с результатом).

---

## Частые проблемы

| Симптом | Причина и решение |
|---|---|
| `2 files found with path 'lib/…/libavcodec.so'` | Не добавлен блок `packaging { jniLibs.pickFirsts … }` (шаг 2). |
| `Dependency requires compileSdk 37` / ошибки AAR metadata | Поднимите `compileSdk` до 37. |
| `Unresolved reference: Icons` при создании `PlayerAction` | Добавьте `androidx.compose.material:material-icons-extended` (или `material-icons-core`). |
| Плеер не открывается из других приложений | `Player2160Activity` в библиотеке `exported=false`; переопределите в своём манифесте ([API.md §4.2](API.md#42-манифест)). |
| `ACTION_VIEW` по http-ссылке открывает браузер | Укажите MIME (`video/*`) или явный пакет/компонент. |
| SMB: «access denied» | Сохраните сервер с логином через `SmbServers.get(context).save(SmbServer(...))`; логин в URI не поддерживается. |
| Звук продолжает играть после ухода с вашего экрана | Во встроенном `PlayerScreen` пауза в `onStop` — ваша задача (шаг 5). |
| В шторке появилось уведомление плеера | Это медиасессия `PlaybackService`: управление с экрана блокировки, гарнитуры и Bluetooth. Исчезает после `controller.release()`. |
| Нужно остановить воспроизведение из своего кода (с любого потока) | `Player2160.pause()`. |
| 4K падает с нехваткой памяти на слабом ТВ | Уменьшите `PlayerConfig.bufferTargetBytes` (например, до 16–32 МБ). |
| Медленный источник отваливается по тайм-ауту | Увеличьте `PlayerConfig.connectTimeoutMs` / `readTimeoutMs`. |
| Плеер сам выбирает дорожки или продолжает «не с того места» | `PlayerConfig(restoreFromHistory = false)` — дорожки и позицию задаёт ваше приложение. |
| Лишние сетевые чтения при старте серии | `PlayerConfig(introDetection = false, readChapters = false)`. |
| Фильм с Dolby Vision играет без DV | Устройство не умеет этот профиль — плеер показывает совместимый слой HDR10 и пишет об этом в «Сведениях о файле» ([API.md §15.5](API.md#155-dolby-vision-без-декодера)). |
