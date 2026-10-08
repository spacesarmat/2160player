# Дополнительное разрешение (GNU GPL v3, раздел 7) для NDI®

Этот файл дополняет лицензию [GNU GPL v3](LICENSE) проекта 2160 Player.

**Additional permission under GNU GPL version 3 section 7**

If you modify this Program, or any covered work, by linking or combining it with the NDI® SDK runtime
library (`libndi`) by Vizrt NDI AB (or a modified version of that library), containing parts covered by the
terms of the NDI SDK License Agreement, the licensors of this Program grant you additional permission to
convey the resulting work. Corresponding Source for a non-source form of such a combination shall not include
the source code for the parts of the NDI SDK used as well as that of the covered work.

**По-русски (пояснение, юридическую силу имеет английский текст выше).** Приложение можно распространять
вместе с библиотекой NDI SDK (`libndi.so`, закрытая лицензия Vizrt NDI AB), загружаемой во время работы;
исходный код этой библиотеки при этом не требуется. Остальной код 2160 Player остаётся под GPL v3.

- Заголовки NDI (`third-party/ndi/include`) распространяются по лицензии MIT, указанной в каждом файле.
- Сама `libndi.so` в репозиторий не входит: её берёт сборка из установленного NDI SDK (см. `third-party/ndi/NOTICE.md`).
- NDI® is a registered trademark of Vizrt NDI AB. https://ndi.video/
