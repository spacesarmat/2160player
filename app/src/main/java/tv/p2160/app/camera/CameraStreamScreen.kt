package tv.p2160.app.camera

import android.Manifest
import android.content.pm.PackageManager
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import tv.p2160.app.FocusCard
import tv.p2160.app.SectionTitle
import tv.p2160.app.share.ShareApp
import tv.p2160.core.i18n.tr

/** «Трансляция камеры»: предпросмотр, выбор камеры и качества, адрес и QR-код для OBS/VLC/2160 Player. */
@Composable
fun CameraStreamScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    CameraStream.load(context) // один раз за процесс (внутри проверка)
    val state by CameraStream.state.collectAsState()
    val config by CameraStream.config.collectAsState()
    val cameras = remember { CameraStream.cameras(context) }
    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    var cameraAllowed by remember { mutableStateOf(granted(Manifest.permission.CAMERA)) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        cameraAllowed = result[Manifest.permission.CAMERA] ?: granted(Manifest.permission.CAMERA)
    }
    LaunchedEffect(Unit) {
        val need = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).filterNot(::granted)
        if (need.isNotEmpty()) permissions.launch(need.toTypedArray())
    }
    val streaming = state as? CameraStreamState.Streaming
    val currentCam = cameras.firstOrNull { it.id == config.cameraId } ?: cameras.firstOrNull()
    val torch by CameraStream.torch.collectAsState()
    // У каждого устройства свои режимы: если выбранного у этой камеры нет — 1080p·30, иначе лучший до 30 fps.
    LaunchedEffect(currentCam?.id) {
        val modes = currentCam?.modes.orEmpty()
        if (streaming == null && modes.isNotEmpty() && config.quality !in modes) {
            val pick = modes.firstOrNull { it.height == 1080 && it.fps == 30 } ?: modes.lastOrNull { it.fps <= 30 } ?: modes.first()
            CameraStream.update(context) { it.copy(quality = pick) }
        }
    }
    val wide = LocalConfiguration.current.screenWidthDp >= 600

    Column(Modifier.fillMaxSize().background(colors.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            FocusCard(onClick = onBack, modifier = Modifier.size(48.dp), background = Color.Transparent) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("player.back"), tint = colors.onBackground, modifier = Modifier.align(Alignment.Center))
            }
            Spacer(Modifier.size(12.dp))
            Text(tr("camera.title"), style = MaterialTheme.typography.headlineSmall, color = colors.onBackground, modifier = Modifier.weight(1f))
            if (streaming != null) LiveBadge()
        }
        LazyColumn(
            Modifier.fillMaxWidth().widthIn(max = 900.dp).align(Alignment.CenterHorizontally),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = if (wide) 32.dp else 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "preview") {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(if (wide) 16f / 9f else 3f / 4f).clip(RoundedCornerShape(16.dp)).background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    if (cameraAllowed) CameraPreview()
                    else Text(tr("camera.no_permission"), color = Color.White, modifier = Modifier.padding(24.dp))
                }
            }
            (state as? CameraStreamState.Error)?.let { err ->
                item(key = "err") {
                    Text(
                        when {
                            err.message == "prepare" -> tr("camera.error")
                            err.message.startsWith("camera.") -> tr(err.message)
                            else -> tr("camera.err_connection", err.message)
                        },
                        color = colors.error,
                    )
                }
            }
            if (cameraAllowed) item(key = "zoom") { ZoomAndFocus() }

            item(key = "start") {
                val label = if (streaming != null) tr("camera.stop") else tr("camera.start")
                FocusCard(
                    onClick = { if (streaming != null) CameraStream.stop(context) else if (cameraAllowed) CameraStream.start(context) },
                    modifier = Modifier.fillMaxWidth(),
                    background = if (streaming != null) colors.errorContainer else colors.primaryContainer,
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (streaming != null) Icons.Default.Stop else Icons.Default.FiberManualRecord, null,
                            tint = if (streaming != null) colors.onErrorContainer else Color(0xFFE53935))
                        Spacer(Modifier.size(12.dp))
                        Text(label, style = MaterialTheme.typography.titleMedium,
                            color = if (streaming != null) colors.onErrorContainer else colors.onPrimaryContainer)
                    }
                }
            }

            if (streaming != null) {
                item(key = "url") {
                    if (streaming.protocol == StreamProtocol.RTSP) StreamAddress(streaming) else PushStatus(streaming)
                }
            }

            item(key = "dest-title") { SectionTitle(tr("camera.destination")) }
            item(key = "dest") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(StreamProtocol.entries, key = { it.name }) { p ->
                        Choice(tr("camera.dest_" + p.name.lowercase()), config.protocol == p, enabled = streaming == null) {
                            CameraStream.update(context) { it.copy(protocol = p) }
                        }
                    }
                }
            }
            if (config.protocol != StreamProtocol.RTSP) item(key = "dest-fields") { DestinationFields(config, enabled = streaming == null) }

            item(key = "cam-title") { SectionTitle(tr("camera.camera")) }
            item(key = "cams") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(cameras, key = { it.id }) { cam ->
                        val selected = (config.cameraId ?: cameras.firstOrNull()?.id) == cam.id
                        Choice(tr(cam.labelKey), selected) {
                            // Новая камера может не уметь текущее качество — берём лучшее из того, что она умеет.
                            CameraStream.update(context) { c ->
                                val q = if (c.quality in cam.modes || streaming != null) c.quality
                                else cam.modes.lastOrNull { e -> e.height <= c.quality.height && e.fps <= c.quality.fps } ?: cam.modes.first()
                                c.copy(cameraId = cam.id, quality = q)
                            }
                        }
                    }
                }
            }
            item(key = "q-title") { SectionTitle(tr("camera.quality")) }
            item(key = "quality") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Режимы из возможностей устройства: камера × кодировщик.
                    items(currentCam?.modes ?: listOf(StreamMode.DEFAULT), key = { it.label }) { q ->
                        Choice(q.label, config.quality == q, enabled = streaming == null) {
                            CameraStream.update(context) { it.copy(quality = q) }
                        }
                    }
                }
            }
            if (currentCam?.hasFlash == true) item(key = "torch") {
                FocusCard(onClick = { CameraStream.setTorch(!torch) }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (torch) Icons.Default.FlashlightOn else Icons.Default.FlashlightOff, null, tint = colors.primary)
                        Spacer(Modifier.size(12.dp))
                        Text(tr("camera.torch"), color = colors.onSurface, modifier = Modifier.weight(1f))
                        Text(tr(if (torch) "torrent.on" else "torrent.off"), color = colors.primary)
                    }
                }
            }
            item(key = "audio") {
                FocusCard(
                    onClick = { if (streaming == null) CameraStream.update(context) { it.copy(audio = !it.audio) } },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(tr("camera.audio"), color = colors.onSurface)
                            Text(tr("camera.audio_hint"), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(tr(if (config.audio) "torrent.on" else "torrent.off"), color = colors.primary)
                    }
                }
            }
            item(key = "hint") {
                Text(tr("camera.hint"), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
            }
        }
    }
}

/** Предпросмотр камеры: живёт, пока поверхность на экране; трансляция от него не зависит. */
@Composable
private fun CameraPreview() {
    val context = LocalContext.current
    var view by remember { mutableStateOf<SurfaceView?>(null) }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            SurfaceView(ctx).apply {
                // Касание — фокус в точке, два пальца — зум щипком.
                val taps = android.view.GestureDetector(ctx, object : android.view.GestureDetector.SimpleOnGestureListener() {
                    override fun onSingleTapUp(e: android.view.MotionEvent): Boolean = CameraStream.tapToFocus(this@apply, e)
                })
                @Suppress("ClickableViewAccessibility")
                setOnTouchListener { v, event ->
                    if (event.pointerCount >= 2) CameraStream.onPreviewTouch(v, event) else taps.onTouchEvent(event)
                    true
                }
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) = CameraStream.startPreview(context, this@apply)
                    // Размер окна предпросмотра (поворот, раскладка) — иначе картинка рисуется в части окна.
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) =
                        CameraStream.setPreviewSize(width, height)
                    override fun surfaceDestroyed(holder: SurfaceHolder) = CameraStream.stopPreview(this@apply)
                })
                view = this
            }
        },
    )
    DisposableEffect(Unit) { onDispose { view?.let(CameraStream::stopPreview) } }
}

/** Зум (ползунок, работает и с пульта) и возврат автофокуса. Фокус касанием и щипок — на самом предпросмотре. */
@Composable
private fun ZoomAndFocus() {
    val colors = MaterialTheme.colorScheme
    val zoom by CameraStream.zoom.collectAsState()
    val config by CameraStream.config.collectAsState()
    // Диапазон известен, когда камера открыта; перечитываем при смене камеры.
    var range by remember { mutableStateOf(1f..1f) }
    LaunchedEffect(config.cameraId) {
        repeat(10) {
            range = CameraStream.zoomRange()
            if (range.endInclusive > range.start) return@LaunchedEffect
            kotlinx.coroutines.delay(300)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.ZoomIn, null, tint = colors.primary)
        Spacer(Modifier.size(8.dp))
        if (range.endInclusive > range.start) {
            androidx.compose.material3.Slider(
                value = zoom.coerceIn(range.start, range.endInclusive),
                onValueChange = CameraStream::setZoom,
                valueRange = range,
                modifier = Modifier.weight(1f),
            )
        } else {
            Text(tr("camera.zoom_none"), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.size(8.dp))
        Text("%.1f×".format(zoom), color = colors.onSurface, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.size(8.dp))
        FocusCard(onClick = CameraStream::autoFocus, background = colors.surfaceVariant) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CenterFocusStrong, null, tint = colors.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text(tr("camera.autofocus"), color = colors.onSurface, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
    Text(tr("camera.focus_hint"), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
}

/** Адрес SRT или сервер и ключ RTMP (с заготовками сервисов). */
@Composable
private fun DestinationFields(config: CameraStreamConfig, enabled: Boolean) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (config.protocol) {
            StreamProtocol.SRT -> {
                androidx.compose.material3.OutlinedTextField(
                    value = config.srtUrl,
                    onValueChange = { v -> CameraStream.update(context) { it.copy(srtUrl = v.trim()) } },
                    enabled = enabled,
                    singleLine = true,
                    label = { Text(tr("camera.srt_url")) },
                    placeholder = { Text("srt://192.168.1.10:9000") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(tr("camera.srt_hint"), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            StreamProtocol.RTMP -> {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(RTMP_PRESETS, key = { it.first }) { (name, url) ->
                        Choice(name, config.rtmpUrl == url, enabled = enabled) { CameraStream.update(context) { it.copy(rtmpUrl = url) } }
                    }
                }
                androidx.compose.material3.OutlinedTextField(
                    value = config.rtmpUrl,
                    onValueChange = { v -> CameraStream.update(context) { it.copy(rtmpUrl = v.trim()) } },
                    enabled = enabled,
                    singleLine = true,
                    label = { Text(tr("camera.rtmp_server")) },
                    placeholder = { Text("rtmp://a.rtmp.youtube.com/live2") },
                    modifier = Modifier.fillMaxWidth(),
                )
                androidx.compose.material3.OutlinedTextField(
                    value = config.rtmpKey,
                    onValueChange = { v -> CameraStream.update(context) { it.copy(rtmpKey = v.trim()) } },
                    enabled = enabled,
                    singleLine = true,
                    label = { Text(tr("camera.rtmp_key")) },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(tr("camera.rtmp_hint"), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            StreamProtocol.RTSP -> Unit
        }
    }
}

/** Заготовки серверов RTMP: адреса приёма сервисов (ключ — из кабинета сервиса). */
private val RTMP_PRESETS = listOf(
    "YouTube" to "rtmp://a.rtmp.youtube.com/live2",
    "Twitch" to "rtmp://live.twitch.tv/app",
)

/** SRT/RTMP: куда отправляем и есть ли соединение. */
@Composable
private fun PushStatus(s: CameraStreamState.Streaming) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.surfaceVariant).padding(16.dp),
    ) {
        Text(
            if (s.connected) tr("camera.push_connected") else tr("camera.push_connecting"),
            color = if (s.connected) Color(0xFF4CAF50) else Color(0xFFFFA726),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(s.url, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
        if (s.bitrateKbps > 0) Text("${s.bitrateKbps} kbit/s", color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun StreamAddress(s: CameraStreamState.Streaming) {
    val colors = MaterialTheme.colorScheme
    val full = s.urlWithAuth
    val qr = remember(full) { ShareApp.qr(full, 512).asImageBitmap() }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.surfaceVariant).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(qr, contentDescription = s.url, modifier = Modifier.size(140.dp).clip(RoundedCornerShape(8.dp)).background(Color.White).padding(6.dp))
        Spacer(Modifier.size(16.dp))
        Column(Modifier.weight(1f)) {
            Text(full, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
            Text(
                if (s.password != null) tr("camera.protected", CameraStream.RTSP_USER, s.password) else tr("camera.unprotected"),
                color = if (s.password != null) colors.onSurfaceVariant else colors.error,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(4.dp))
            Text(tr("camera.viewers", s.clients) + if (s.bitrateKbps > 0) " · ${s.bitrateKbps} kbit/s" else "",
                color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            Text(tr("camera.obs_hint"), color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Choice(text: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    FocusCard(
        onClick = { if (enabled) onClick() },
        background = if (selected) colors.primaryContainer else colors.surfaceVariant,
    ) {
        Text(
            text,
            color = when {
                selected -> colors.onPrimaryContainer
                enabled -> colors.onSurface
                else -> colors.onSurface.copy(alpha = 0.4f)
            },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun LiveBadge() {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Color(0xFFE53935)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(tr("camera.live"), color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
}
