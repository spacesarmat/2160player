# Сборка и запуск

## Что нужно

| Компонент | Версия |
|---|---|
| JDK | 21 (Gradle). Байткод модулей — toolchain 17. |
| Android SDK | `compileSdk` 37 (`platforms;android-37` или `android-37.0`), Build-Tools 37. |
| minSdk | 24. `targetSdk` приложения — 36. |

`local.properties` (в git не входит):

```properties
sdk.dir=/path/to/Android/Sdk
```

NDI необязателен. Если задан `ndi.sdk.dir` или `NDI_SDK_DIR` и в каталоге есть `Lib/arm64-v8a/libndi.so`,
сборка включает NDI. Без SDK приложение собирается, пункт NDI скрыт.

## Команды

```sh
./gradlew :app:assembleDebug                         # APK по ABI + universal
./gradlew :player-core:testDebugUnitTest             # тесты библиотеки
./gradlew :app:testDebugUnitTest                     # тесты приложения
./gradlew :source-torrent:testDebugUnitTest          # тесты торрентов
./gradlew :samples:embed-demo:assembleDebug          # пример встраивания
./gradlew assembleDebug testDebugUnitTest lintDebug  # то, что просят перед PR
./gradlew :player-core:publishReleasePublicationToMavenLocal
```

APK: `app/build/outputs/apk/debug/`.

| Сборка | applicationId | Имя |
|---|---|---|
| release | `tv.p2160.player` | 2160 Player |
| debug | `tv.p2160.player.debug` | «2160 Тест» (красная полоса «ТЕСТ») |

Установка на устройство:

```sh
adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

Универсальный APK — `app-universal-debug.apk`. На ТВ без `adb` APK кладут на флешку или раздают
с телефона: Настройки → О приложении → Поделиться приложением → «Раздать по Wi-Fi».

## Релиз

Подпись — `keystore.properties` или переменные `P2160_KEYSTORE`, `P2160_KEYSTORE_PASSWORD`,
`P2160_KEY_ALIAS`, `P2160_KEY_PASSWORD`. Без ключа release собирается неподписанным.

```sh
./gradlew :app:assembleRelease -Pp2160.versionName=0.2.0 -Pp2160.versionCode=3
./gradlew :app:assembleRelease -Pp2160.versionName=0.2.0 -Pp2160.versionCode=3 -Pp2160.abi=arm
```

`-Pp2160.abi=arm` — один APK с `arm64-v8a` и `armeabi-v7a`. Тег релиза — `v<versionName>`.
`versionCode` каждого релиза больше предыдущего. Автообновление берёт с GitHub Releases APK
с `-arm-` в имени на ARM, иначе APK под ABI устройства, иначе `universal`.

## Конфигурация

Два независимых слоя:

1. **Пользователь** — экран «Настройки», `PlayerSettings` / `Settings`, файл `p2160_settings`.
   См. [Настройки](Settings.md).
2. **Движок для встраивания** — `Player2160.config = PlayerConfig(...)` до создания плеера:
   предел буфера (`AUTO` — 24–128 МБ по памяти устройства), тайм-ауты HTTP, чтение глав,
   поиск вступлений, запись и чтение истории. Пользователь это не меняет.

Запуск из кода библиотеки: `Player2160.play(context, PlaybackRequest.single(uri, title))`.
Без библиотеки — [Intent API](Intent-API.md).
