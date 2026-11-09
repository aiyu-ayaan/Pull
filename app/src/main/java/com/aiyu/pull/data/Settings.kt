package com.aiyu.pull.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
    val torrentSequential: Boolean = false,
    val ytdlpChannel: String = "STABLE",
    val ytdlpAutoUpdate: Boolean = true,
    val ytdlpLastCheck: Long = 0,
    val torrentDht: Boolean = true,
    val torrentLsd: Boolean = true,
    val torrentUtp: Boolean = true,
    val torrentPortMapping: Boolean = true,
    val torrentEncryption: Encryption = Encryption.ENABLED,
    /** KB/s for all torrents together; 0 = unlimited. */
    val torrentDownLimit: Int = 0,
    val torrentUpLimit: Int = 0,
    val torrentConnections: Int = 200,
)

/** BitTorrent protocol encryption: allow both, require it, or never use it. */
enum class Encryption { ENABLED, FORCED, DISABLED }

/** A saved set of extra yt-dlp arguments (Seal's "custom command" templates). */
@Serializable
data class CommandTemplate(val id: Long, val name: String, val args: String)

private val Context.store by preferencesDataStore("settings")

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private object Keys {
        val theme = stringPreferencesKey("theme")
        val dynamic = booleanPreferencesKey("dynamic_color")
        val connections = intPreferencesKey("connections")
        val maxDownloads = intPreferencesKey("max_downloads")
        val autoResume = booleanPreferencesKey("auto_resume")
        val torrentSequential = booleanPreferencesKey("torrent_sequential")
        val ytdlpChannel = stringPreferencesKey("ytdlp_channel")
        val ytdlpAutoUpdate = booleanPreferencesKey("ytdlp_auto_update")
        val ytdlpLastCheck = longPreferencesKey("ytdlp_last_check")
        val templates = stringPreferencesKey("command_templates")
        val torrentDht = booleanPreferencesKey("torrent_dht")
        val torrentLsd = booleanPreferencesKey("torrent_lsd")
        val torrentUtp = booleanPreferencesKey("torrent_utp")
        val torrentPortMapping = booleanPreferencesKey("torrent_port_mapping")
        val torrentEncryption = stringPreferencesKey("torrent_encryption")
        val torrentDownLimit = intPreferencesKey("torrent_down_limit")
        val torrentUpLimit = intPreferencesKey("torrent_up_limit")
        val torrentConnections = intPreferencesKey("torrent_connections")
    }

    val settings: Flow<Settings> = context.store.data.map { p ->
        Settings(
            themeMode = p[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            dynamicColor = p[Keys.dynamic] ?: false,
            connections = p[Keys.connections] ?: 8,
            maxDownloads = p[Keys.maxDownloads] ?: 3,
            autoResume = p[Keys.autoResume] ?: true,
            torrentSequential = p[Keys.torrentSequential] ?: false,
            ytdlpChannel = p[Keys.ytdlpChannel] ?: "STABLE",
            ytdlpAutoUpdate = p[Keys.ytdlpAutoUpdate] ?: true,
            ytdlpLastCheck = p[Keys.ytdlpLastCheck] ?: 0,
            torrentDht = p[Keys.torrentDht] ?: true,
            torrentLsd = p[Keys.torrentLsd] ?: true,
            torrentUtp = p[Keys.torrentUtp] ?: true,
            torrentPortMapping = p[Keys.torrentPortMapping] ?: true,
            torrentEncryption = p[Keys.torrentEncryption]?.let { runCatching { Encryption.valueOf(it) }.getOrNull() } ?: Encryption.ENABLED,
            torrentDownLimit = p[Keys.torrentDownLimit] ?: 0,
            torrentUpLimit = p[Keys.torrentUpLimit] ?: 0,
            torrentConnections = p[Keys.torrentConnections] ?: 200,
        )
    }

    val templates: Flow<List<CommandTemplate>> = context.store.data.map { p ->
        p[Keys.templates]?.let { runCatching { Json.decodeFromString<List<CommandTemplate>>(it) }.getOrNull() } ?: DEFAULT_TEMPLATES
    }

    suspend fun saveTemplates(list: List<CommandTemplate>) = set(Keys.templates, Json.encodeToString(list))

    suspend fun current(): Settings = settings.first()

    private suspend fun <T> set(key: Preferences.Key<T>, value: T) { context.store.edit { it[key] = value } }

    suspend fun setTheme(mode: ThemeMode) = set(Keys.theme, mode.name)
    suspend fun setDynamicColor(on: Boolean) = set(Keys.dynamic, on)
    suspend fun setConnections(n: Int) = set(Keys.connections, n.coerceIn(1, 32))
    suspend fun setMaxDownloads(n: Int) = set(Keys.maxDownloads, n.coerceIn(1, 10))
    suspend fun setAutoResume(on: Boolean) = set(Keys.autoResume, on)
    suspend fun setTorrentSequential(on: Boolean) = set(Keys.torrentSequential, on)
    suspend fun setYtdlpChannel(channel: String) = set(Keys.ytdlpChannel, channel)
    suspend fun setYtdlpAutoUpdate(on: Boolean) = set(Keys.ytdlpAutoUpdate, on)
    suspend fun setYtdlpLastCheck(at: Long) = set(Keys.ytdlpLastCheck, at)
    suspend fun setTorrentDht(on: Boolean) = set(Keys.torrentDht, on)
    suspend fun setTorrentLsd(on: Boolean) = set(Keys.torrentLsd, on)
    suspend fun setTorrentUtp(on: Boolean) = set(Keys.torrentUtp, on)
    suspend fun setTorrentPortMapping(on: Boolean) = set(Keys.torrentPortMapping, on)
    suspend fun setTorrentEncryption(e: Encryption) = set(Keys.torrentEncryption, e.name)
    suspend fun setTorrentDownLimit(kb: Int) = set(Keys.torrentDownLimit, kb.coerceAtLeast(0))
    suspend fun setTorrentUpLimit(kb: Int) = set(Keys.torrentUpLimit, kb.coerceAtLeast(0))
    suspend fun setTorrentConnections(n: Int) = set(Keys.torrentConnections, n.coerceIn(20, 500))

    companion object {
        /** Starter templates; the user edits or deletes them on the Command templates screen. */
        val DEFAULT_TEMPLATES = listOf(
            CommandTemplate(1, "Embed subtitles & thumbnail", "--embed-subs --sub-langs all,-live_chat --embed-thumbnail --embed-metadata"),
            CommandTemplate(2, "Audio as MP3", "-x --audio-format mp3 --audio-quality 0 --embed-thumbnail"),
            CommandTemplate(3, "Split by chapters", "--split-chapters"),
        )
    }
}
