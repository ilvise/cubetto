package it.cubetto.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AudioOptions(
    val normalize: Boolean = true,
    val trimSilence: Boolean = true,
    val splitChapters: Boolean = false,
    val skipSponsors: Boolean = true,
    val mono: Boolean = true,
) {
    /** Bitrate costante (CBR) come gli originali FABA™ (96 kbps joint stereo); 128 kbps se i canali restano separati. */
    val bitrateKbps: Int get() = if (mono) 96 else 128
}

data class AppSettings(
    val audio: AudioOptions = AudioOptions(),
    val autoSync: Boolean = true,
    val warningDismissed: Boolean = false,
    val lastYtDlpCheck: Long = 0,
)

/** Preferenze semplici salvate in SharedPreferences ed esposte come StateFlow. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    private fun read() = AppSettings(
        audio = AudioOptions(
            normalize = prefs.getBoolean("normalize", true),
            trimSilence = prefs.getBoolean("trimSilence", true),
            splitChapters = prefs.getBoolean("splitChapters", false),
            skipSponsors = prefs.getBoolean("skipSponsors", true),
            mono = prefs.getBoolean("mono", true),
        ),
        autoSync = prefs.getBoolean("autoSync", true),
        warningDismissed = prefs.getBoolean("warningDismissed", false),
        lastYtDlpCheck = prefs.getLong("lastYtDlpCheck", 0),
    )

    fun update(block: (AppSettings) -> AppSettings) {
        val s = block(_state.value)
        prefs.edit()
            .putBoolean("normalize", s.audio.normalize)
            .putBoolean("trimSilence", s.audio.trimSilence)
            .putBoolean("splitChapters", s.audio.splitChapters)
            .putBoolean("skipSponsors", s.audio.skipSponsors)
            .putBoolean("mono", s.audio.mono)
            .putBoolean("autoSync", s.autoSync)
            .putBoolean("warningDismissed", s.warningDismissed)
            .putLong("lastYtDlpCheck", s.lastYtDlpCheck)
            .apply()
        _state.value = s
    }
}
