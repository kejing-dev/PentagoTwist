package io.embodit.pentagotwist.data

import android.content.Context
import android.util.Log
import io.embodit.pentagotwist.domain.SeatPick
import io.embodit.pentagotwist.domain.GameSetup
import io.embodit.pentagotwist.domain.GameStore
import io.embodit.pentagotwist.domain.Language
import io.embodit.pentagotwist.domain.Mode
import io.embodit.pentagotwist.domain.SaveCodec
import io.embodit.pentagotwist.domain.SavedGame
import io.embodit.pentagotwist.domain.Settings
import io.embodit.pentagotwist.domain.DEFAULT_TILT
import io.embodit.pentagotwist.domain.SIZES
import io.embodit.pentagotwist.domain.TILTS
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * files/game.txt (config + move list), written atomically after every step: a temp file, a read-back check, then a rename; the previous
 * good copy stays as game.bak (Chess Stores.kt pattern).
 */
class FileGameStore(context: Context) : GameStore {
    private val dir = context.filesDir
    private val file = File(dir, "game.txt")
    private val backup = File(dir, "game.bak")

    override fun load(): SavedGame? =
        listOf(file, backup).firstNotNullOfOrNull { f -> runCatching { if (f.exists()) SaveCodec.decode(f.readText()) else null }.getOrNull() }

    override fun save(game: SavedGame) {
        val text = SaveCodec.encode(game)
        runCatching {
            val tmp = File(dir, "game.tmp")
            tmp.writeText(text)
            check(SaveCodec.decode(tmp.readText()) == game) { "read-back mismatch" }
            if (file.exists()) file.copyTo(backup, overwrite = true)
            if (!tmp.renameTo(file)) { file.delete(); check(tmp.renameTo(file)) }
        }.onFailure { Log.e("PentagoTwist", "SAVE failed", it) }
    }

    override fun clear() {
        file.delete()
        backup.delete()
    }
}

/** App settings in SharedPreferences, exposed as a StateFlow so every window recomposes on change. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<Settings> = _settings.asStateFlow()
    val value: Settings get() = _settings.value

    fun update(change: (Settings) -> Settings) = set(change(_settings.value))

    fun set(s: Settings) {
        _settings.value = s
        prefs.edit()
            .putString("language", s.language.name)
            .putBoolean("sound", s.sound)
            .putInt("volume", s.volume)
            .putBoolean("reducedMotion", s.reducedMotion)
            .putBoolean("coach", s.coach)
            .putInt("tilt", s.tilt)
            .putInt("size", s.size)
            .putBoolean("firstRunDone", s.firstRunDone)
            .putString("setupMode", s.lastSetup.mode.name)
            .putInt("setupLevel", s.lastSetup.aiLevel)
            .putString("setupSeat", s.lastSetup.seat.name)
            .apply()
    }

    private fun read(): Settings = runCatching {
        Settings(
            language = Language.valueOf(prefs.getString("language", Language.SYSTEM.name)!!),
            sound = prefs.getBoolean("sound", true),
            volume = prefs.getInt("volume", 2).coerceIn(0, 2),
            reducedMotion = prefs.getBoolean("reducedMotion", false),
            coach = prefs.getBoolean("coach", true),
            tilt = prefs.getInt("tilt", DEFAULT_TILT).takeIf { it in TILTS } ?: DEFAULT_TILT,
            size = prefs.getInt("size", 100).takeIf { it in SIZES } ?: 100,
            firstRunDone = prefs.getBoolean("firstRunDone", false),
            lastSetup = GameSetup(
                mode = Mode.valueOf(prefs.getString("setupMode", Mode.AI.name)!!),
                aiLevel = prefs.getInt("setupLevel", 2).coerceIn(1, 3),
                seat = SeatPick.valueOf(prefs.getString("setupSeat", SeatPick.BLACK.name)!!),
            ),
        )
    }.getOrElse { Settings() }
}
