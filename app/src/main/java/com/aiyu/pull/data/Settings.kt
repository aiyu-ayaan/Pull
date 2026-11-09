package com.aiyu.pull.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class Settings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val connections: Int = 8,
    val maxDownloads: Int = 3,
    /** Downloads cut by a crash, reboot or the system killing Pull continue on their own. */
    val autoResume: Boolean = true,
)

private val Context.store by preferencesDataStore("settings")

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private object Keys {
        val theme = stringPreferencesKey("theme")
        val dynamic = booleanPreferencesKey("dynamic_color")
        val connections = intPreferencesKey("connections")
        val maxDownloads = intPreferencesKey("max_downloads")
        val autoResume = booleanPreferencesKey("auto_resume")
    }

    val settings: Flow<Settings> = context.store.data.map { p ->
        Settings(
            themeMode = p[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            dynamicColor = p[Keys.dynamic] ?: false,
            connections = p[Keys.connections] ?: 8,
            maxDownloads = p[Keys.maxDownloads] ?: 3,
            autoResume = p[Keys.autoResume] ?: true,
        )
    }

    suspend fun current(): Settings = settings.first()

    private suspend fun <T> set(key: Preferences.Key<T>, value: T) { context.store.edit { it[key] = value } }

    suspend fun setTheme(mode: ThemeMode) = set(Keys.theme, mode.name)
    suspend fun setDynamicColor(on: Boolean) = set(Keys.dynamic, on)
    suspend fun setConnections(n: Int) = set(Keys.connections, n.coerceIn(1, 32))
    suspend fun setMaxDownloads(n: Int) = set(Keys.maxDownloads, n.coerceIn(1, 10))
    suspend fun setAutoResume(on: Boolean) = set(Keys.autoResume, on)
}
