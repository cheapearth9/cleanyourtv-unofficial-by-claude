package io.github.jqssun.airplay

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.content.ContextCompat
import io.github.jqssun.airplay.service.AirPlayService
import org.json.JSONObject

/**
 * The control door for other apps (the cleanyourtv launcher), protected by [CONTROL_PERMISSION]: read and change settings,
 * start and stop the server (no screen has to open for that), ask whether it runs, and read or clear its log.
 * Every answer comes back as the broadcast's result code (OK or CANCELED) and result data.
 */
class SettingsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        when (intent.action) {
            ACTION_SET -> _set(prefs, intent.getStringExtra(EXTRA_KEY), intent.getStringExtra(EXTRA_VALUE))
            ACTION_LIST -> _reply(true, _list(prefs))
            ACTION_START -> _start(context)
            ACTION_STOP -> _stop(context)
            ACTION_STATUS -> _reply(true, _status())
            ACTION_LOGS -> _reply(true, LogBuffer.tail(MAX_LOG_CHARS))
            ACTION_CLEAR_LOGS -> { LogBuffer.clear(); _reply(true, "cleared") }
        }
    }

    private fun _start(context: Context) {
        // the same start the boot receiver does, without looking at the "start at boot" setting
        val serviceIntent = Intent(context, AirPlayService::class.java).setAction(AirPlayService.ACTION_START_SERVER)
        ContextCompat.startForegroundService(context, serviceIntent)
        _reply(true, "started")
    }

    private fun _stop(context: Context) {
        val service = AirPlayService.instance
        if (service != null) service.stopServer() else context.stopService(Intent(context, AirPlayService::class.java))
        _reply(true, "stopped")
    }

    private fun _status(): String {
        val service = AirPlayService.instance
        return JSONObject()
            .put("running", service?.serverState?.value == AirPlayService.ServerState.RUNNING)
            .put("connections", service?.connectionCount?.value ?: 0)
            .toString()
    }

    private fun _set(prefs: SharedPreferences, key: String?, value: String?) {
        val pref = Prefs.all[key]
        when {
            pref == null -> _reply(false, "unknown key: $key")
            value == null || !pref.writeText(prefs, value) -> _reply(false, "invalid value for $key: $value")
            else -> _reply(true, "$key=$value")
        }
    }

    private fun _list(prefs: SharedPreferences) = JSONObject().apply {
        Prefs.all.forEach { (key, pref) -> put(key, JSONObject().put("value", prefs[pref]).put("default", pref.def)) }
    }.toString()

    private fun <T : Any> Pref<T>.writeText(p: SharedPreferences, s: String) = parse(s)?.let { write(p, it) } == true

    private fun _reply(ok: Boolean, data: String) =
        setResult(if (ok) Activity.RESULT_OK else Activity.RESULT_CANCELED, data, null)

    companion object {
        const val CONTROL_PERMISSION = "tv.clean.airplay.permission.CONTROL"
        const val ACTION_SET = "io.github.jqssun.airplay.SET_SETTING"
        const val ACTION_LIST = "io.github.jqssun.airplay.LIST_SETTINGS"
        const val ACTION_START = "io.github.jqssun.airplay.START"
        const val ACTION_STOP = "io.github.jqssun.airplay.STOP"
        const val ACTION_STATUS = "io.github.jqssun.airplay.STATUS"
        const val ACTION_LOGS = "io.github.jqssun.airplay.LOGS"
        const val ACTION_CLEAR_LOGS = "io.github.jqssun.airplay.CLEAR_LOGS"
        const val EXTRA_KEY = "key"
        const val EXTRA_VALUE = "value"
        // a broadcast answer travels through Binder (about 1 MB at most)
        private const val MAX_LOG_CHARS = 60_000
    }
}
