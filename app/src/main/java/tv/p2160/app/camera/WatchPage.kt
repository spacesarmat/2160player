package tv.p2160.app.camera

import android.net.Uri
import tv.p2160.core.i18n.Strings

/**
 * Страница «Смотреть трансляцию» (`GET /watch` сервера устройства, [tv.p2160.app.handoff.Handoff]).
 * В QR-коде экрана трансляции — ссылка на неё: любая камера телефона откроет http-ссылку в браузере,
 * а кнопка на странице — 2160 Player (`intent://…;scheme=rtsp;package=…`) или, если его нет, скачивание APK
 * прямо с этого устройства (`S.browser_fallback_url`).
 */
internal object WatchPage {
    const val PACKAGE = "tv.p2160.player"

    fun html(t: Strings, device: String, rtsp: String, apkUrl: String?): String {
        val title = t.format("camera.title_remote", device)
        val hostPart = rtsp.removePrefix("rtsp://")
        val fallback = apkUrl?.let { ";S.browser_fallback_url=" + Uri.encode(it) }.orEmpty()
        val open2160 = "intent://$hostPart#Intent;scheme=rtsp;package=$PACKAGE;S.title=${Uri.encode(title)}$fallback;end"
        val openOther = "intent://$hostPart#Intent;scheme=rtsp;end"
        val install = apkUrl?.let { "<a class=\"btn ghost\" href=\"${esc(it)}\">${esc(t["camera.watch_install"])}</a>" }.orEmpty()
        return """<!doctype html><html lang="ru"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>${esc(title)}</title>
<style>
:root{color-scheme:dark}body{margin:0;font-family:system-ui,sans-serif;background:#0e0e10;color:#eee;display:flex;justify-content:center}
main{max-width:520px;width:100%;padding:24px 16px}h1{font-size:22px;margin:8px 0 4px}.live{display:inline-block;background:#e53935;color:#fff;
border-radius:99px;padding:2px 10px;font-size:13px}p{color:#aaa;line-height:1.45}.btn{display:block;text-align:center;text-decoration:none;
font-size:17px;padding:16px;border-radius:14px;margin:12px 0;background:#ffb300;color:#111;font-weight:600}.ghost{background:#24242a;color:#eee;font-weight:500}
code{display:block;word-break:break-all;background:#1b1b20;padding:12px;border-radius:10px;color:#ddd;font-size:14px}
</style></head><body><main>
<span class="live">● ${esc(t["camera.live"])}</span>
<h1>${esc(title)}</h1>
<p>${esc(t["camera.watch_text"])}</p>
<a class="btn" href="${esc(open2160)}">${esc(t["camera.watch_open"])}</a>
<a class="btn ghost" href="${esc(openOther)}">${esc(t["camera.watch_other"])}</a>
$install
<p>${esc(t["camera.watch_obs"])}</p>
<code>${esc(rtsp)}</code>
</main></body></html>"""
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
