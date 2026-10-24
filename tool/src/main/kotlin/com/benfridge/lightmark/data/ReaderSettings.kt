package com.benfridge.lightmark.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The handful of things worth remembering between sessions that are not reading
 * positions.
 *
 * There is no settings screen and should not be one; what little exists lives as a row
 * on the library, which is already a list of rows.
 */
class ReaderSettings(private val dataStore: DataStore<Preferences>) {

    /**
     * Whether the volume keys turn pages.
     *
     * LightOS hands a key to the foreground screen before acting on it itself, so
     * consuming volume up and down means they page instead of changing the volume. That
     * is a genuine trade: while the reader is open you cannot adjust what you are
     * listening to. Hence a switch rather than a decision made for you. On by default,
     * because reading is what the tool is for.
     */
    suspend fun volumeKeysTurnPages(): Boolean =
        dataStore.data.map { it[VOLUME_KEYS] ?: true }.first()

    suspend fun setVolumeKeysTurnPages(enabled: Boolean) {
        dataStore.edit { it[VOLUME_KEYS] = enabled }
    }

    private companion object {
        val VOLUME_KEYS = booleanPreferencesKey("volumeKeysTurnPages")
    }
}
