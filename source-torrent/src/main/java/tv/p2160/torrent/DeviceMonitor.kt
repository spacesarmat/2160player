package tv.p2160.torrent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Состояние устройства для правил раздачи.
 * [metered] — сеть с оплатой трафика (мобильный интернет, точка доступа телефона);
 * [charging] — на зарядке (или без батареи вовсе: ТВ, приставка).
 */
data class DeviceState(val metered: Boolean = false, val charging: Boolean = true)

/** Следит за типом сети и зарядкой. Регистрируется один раз на процесс (из синглтона [TorrentEngine]). */
internal class DeviceMonitor(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val _state = MutableStateFlow(DeviceState(metered = cm?.isActiveNetworkMetered ?: false))
    val state: StateFlow<DeviceState> = _state.asStateFlow()

    init {
        runCatching {
            cm?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                    _state.update { it.copy(metered = metered) }
                }

                override fun onLost(network: Network) {
                    _state.update { it.copy(metered = cm.isActiveNetworkMetered) }
                }
            })
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                _state.update { it.copy(charging = charging(intent)) }
            }
        }
        // ACTION_BATTERY_CHANGED «липкий»: регистрация сразу возвращает текущее состояние.
        ContextCompat.registerReceiver(context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            ?.let { sticky -> _state.update { it.copy(charging = charging(sticky)) } }
    }

    private fun charging(intent: Intent): Boolean {
        if (!intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true)) return true
        return intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }
}
