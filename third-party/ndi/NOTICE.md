# NDI® SDK (сторонний код)

NDI® is a registered trademark of Vizrt NDI AB. https://ndi.video/

- `include/` — заголовки NDI SDK 6 для Android, без изменений. Каждый файл распространяется по лицензии MIT,
  указанной в его начале (так разрешает документация NDI SDK, раздел «Header files»).
- Библиотека `libndi.so` (закрытая лицензия NDI SDK License Agreement) **в репозиторий не входит**. Сборка берёт её
  из установленного SDK — путь `ndi.sdk.dir` в `local.properties`, переменная `NDI_SDK_DIR` или
  `C:/Program Files/NDI/NDI 6 SDK (Android)` — и кладёт в APK вместе с лицензиями её компонентов
  (`libndi_licenses.txt`, `libndi_bonjour_license.txt` → `assets/ndi/`). Без SDK приложение собирается без NDI.
- `app/src/main/cpp/ndi_bridge.cpp` загружает `libndi.so` динамически (`dlopen` + `NDIlib_v6_load`), как
  рекомендует документация NDI SDK для открытых проектов.
- Разрешение распространять 2160 Player вместе с `libndi` — [LICENSE-NDI-EXCEPTION.md](../../LICENSE-NDI-EXCEPTION.md).

Требования NDI к приложению: ссылка на https://ndi.video/ рядом с выбором NDI и в документации; знак «NDI®» и
фраза «NDI® is a registered trademark of Vizrt NDI AB» при первом упоминании и в разделе «О приложении».
