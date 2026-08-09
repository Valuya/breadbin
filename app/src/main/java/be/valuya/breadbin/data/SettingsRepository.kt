package be.valuya.breadbin.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import be.valuya.breadbin.engine.vic.VideoModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore("settings")

class SettingsRepository(private val context: Context) {

    val settings: Flow<Settings> = context.settingsStore.data.map { preferences ->
        Settings(
            model = preferences[MODEL]?.let { runCatching { VideoModel.valueOf(it) }.getOrNull() }
                ?: VideoModel.PAL,
            aspect = preferences[ASPECT]?.let { runCatching { Aspect.valueOf(it) }.getOrNull() }
                ?: Aspect.TELEVISION,
            smoothing = preferences[SMOOTHING] ?: false,
            showBorder = preferences[BORDER] ?: true,
            sound = preferences[SOUND] ?: true,
            haptics = preferences[HAPTICS] ?: true,
            stickSize = preferences[STICK_SIZE] ?: 1f,
            opacity = preferences[OPACITY] ?: 0.5f,
            autostart = preferences[AUTOSTART] ?: true,
            joystickPort = preferences[PORT] ?: 2,
        )
    }

    suspend fun setModel(model: VideoModel) = put { it[MODEL] = model.name }
    suspend fun setAspect(aspect: Aspect) = put { it[ASPECT] = aspect.name }
    suspend fun setSmoothing(value: Boolean) = put { it[SMOOTHING] = value }
    suspend fun setShowBorder(value: Boolean) = put { it[BORDER] = value }
    suspend fun setSound(value: Boolean) = put { it[SOUND] = value }
    suspend fun setHaptics(value: Boolean) = put { it[HAPTICS] = value }
    suspend fun setStickSize(value: Float) = put { it[STICK_SIZE] = value }
    suspend fun setOpacity(value: Float) = put { it[OPACITY] = value }
    suspend fun setAutostart(value: Boolean) = put { it[AUTOSTART] = value }
    suspend fun setJoystickPort(value: Int) = put { it[PORT] = value }

    private suspend fun put(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsStore.edit(block)
    }

    private companion object {
        val MODEL = stringPreferencesKey("model")
        val ASPECT = stringPreferencesKey("aspect")
        val SMOOTHING = booleanPreferencesKey("smoothing")
        val BORDER = booleanPreferencesKey("border")
        val SOUND = booleanPreferencesKey("sound")
        val HAPTICS = booleanPreferencesKey("haptics")
        val STICK_SIZE = floatPreferencesKey("stickSize")
        val OPACITY = floatPreferencesKey("opacity")
        val AUTOSTART = booleanPreferencesKey("autostart")
        val PORT = intPreferencesKey("joystickPort")
    }
}
