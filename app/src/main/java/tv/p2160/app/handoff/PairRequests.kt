package tv.p2160.app.handoff

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import tv.p2160.app.R
import tv.p2160.core.i18n.I18n
import tv.p2160.core.i18n.tr

/**
 * «Устройство X хочет подключиться» на стороне хозяина: уведомление с кнопками «Разрешить» и
 * «Отклонить». На ТВ (уведомления почти не видны) и без разрешения на уведомления — диалог
 * [PairRequestActivity] с теми же кнопками. Сервер передачи работает, только пока приложение на
 * экране, поэтому диалог всегда можно показать.
 */
object PairRequests {
    private const val CHANNEL = "handoff_pair"
    internal const val ACTION_APPROVE = "tv.p2160.app.handoff.APPROVE"
    internal const val ACTION_DENY = "tv.p2160.app.handoff.DENY"
    internal const val EXTRA_NONCE = "nonce"
    internal const val EXTRA_NAME = "name"

    fun show(context: Context, nonce: String, name: String) {
        if (isTv(context) || !canNotify(context)) {
            context.startActivity(
                Intent(context, PairRequestActivity::class.java)
                    .putExtra(EXTRA_NONCE, nonce)
                    .putExtra(EXTRA_NAME, name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            )
            return
        }
        val strings = I18n.get(context).current
        ensureChannel(context, strings["handoff.pair_channel"])
        val text = if (HandoffAuth.mode == HandoffAuth.Mode.DAILY) {
            strings.format("handoff.pair_text_code", HandoffAuth.currentCode().chunked(3).joinToString(" "))
        } else {
            strings["handoff.pair_text"]
        }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_devices)
            .setContentTitle(strings.format("handoff.pair_title", name))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .setTimeoutAfter(3 * 60 * 1000L)
            .setContentIntent(activityIntent(context, nonce, name))
            .addAction(0, strings["handoff.pair_allow"], actionIntent(context, ACTION_APPROVE, nonce, name))
            .addAction(0, strings["handoff.pair_deny"], actionIntent(context, ACTION_DENY, nonce, name))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(notificationId(nonce), notification)
        } catch (_: SecurityException) {
            // Разрешение отозвали между проверкой и показом — спрашиваем окном.
            context.startActivity(
                Intent(context, PairRequestActivity::class.java)
                    .putExtra(EXTRA_NONCE, nonce)
                    .putExtra(EXTRA_NAME, name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            )
        }
    }

    fun cancel(context: Context, nonce: String) = NotificationManagerCompat.from(context).cancel(notificationId(nonce))

    /** Решение хозяина — из уведомления или диалога. */
    internal fun decide(context: Context, nonce: String, name: String, allow: Boolean) {
        cancel(context, nonce)
        if (allow) {
            if (HandoffAuth.approve(nonce)) {
                Toast.makeText(context, I18n.get(context).current.format("handoff.pair_allowed", name), Toast.LENGTH_SHORT).show()
            }
        } else {
            HandoffAuth.deny(nonce)
        }
    }

    private fun notificationId(nonce: String) = 0x2160 + (nonce.hashCode() and 0xFFFF)

    private fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun isTv(context: Context): Boolean =
        (context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).currentModeType == Configuration.UI_MODE_TYPE_TELEVISION

    private fun ensureChannel(context: Context, name: String) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, name, NotificationManager.IMPORTANCE_HIGH))
        }
    }

    private fun actionIntent(context: Context, action: String, nonce: String, name: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            notificationId(nonce) * 2 + if (action == ACTION_APPROVE) 0 else 1,
            Intent(context, PairActionReceiver::class.java).setAction(action).putExtra(EXTRA_NONCE, nonce).putExtra(EXTRA_NAME, name),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun activityIntent(context: Context, nonce: String, name: String): PendingIntent =
        PendingIntent.getActivity(
            context,
            notificationId(nonce),
            Intent(context, PairRequestActivity::class.java).putExtra(EXTRA_NONCE, nonce).putExtra(EXTRA_NAME, name),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}

/** Кнопки «Разрешить» / «Отклонить» в уведомлении. */
class PairActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val nonce = intent.getStringExtra(PairRequests.EXTRA_NONCE) ?: return
        val name = intent.getStringExtra(PairRequests.EXTRA_NAME).orEmpty()
        HandoffAuth.init(context)
        PairRequests.decide(context, nonce, name, allow = intent.action == PairRequests.ACTION_APPROVE)
    }
}

/** Тот же запрос диалогом — на ТВ, без разрешения на уведомления или по нажатию на уведомление. */
class PairRequestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val nonce = intent.getStringExtra(PairRequests.EXTRA_NONCE) ?: return finish()
        val name = intent.getStringExtra(PairRequests.EXTRA_NAME).orEmpty()
        HandoffAuth.init(this)
        dialogContent {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            val code = if (HandoffAuth.mode == HandoffAuth.Mode.DAILY) HandoffAuth.currentCode().chunked(3).joinToString(" ") else null
            AlertDialog(
                onDismissRequest = ::finish,
                icon = { Icon(Icons.Default.Devices, null) },
                title = { Text(tr("handoff.pair_title", name)) },
                text = { Text(if (code != null) tr("handoff.pair_text_code", code) else tr("handoff.pair_text")) },
                confirmButton = {
                    TextButton(
                        onClick = { PairRequests.decide(this, nonce, name, allow = true); finish() },
                        modifier = Modifier.focusRequester(focus),
                    ) { Text(tr("handoff.pair_allow")) }
                },
                dismissButton = {
                    TextButton(onClick = { PairRequests.decide(this, nonce, name, allow = false); finish() }) { Text(tr("handoff.pair_deny")) }
                },
            )
        }
    }
}
