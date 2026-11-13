package com.aiyu.pull.ui.settings

import com.aiyu.pull.data.BookmarkEntity
import com.aiyu.pull.data.Encryption
import com.aiyu.pull.data.SearchEngine
import com.aiyu.pull.data.Settings
import com.aiyu.pull.data.ShortcutEntity
import com.aiyu.pull.data.ThemeMode
import com.aiyu.pull.update.UpdateChannel
import com.aiyu.pull.video.AudioFormat
import com.aiyu.pull.video.Container
import com.aiyu.pull.video.VideoChoice
import com.aiyu.pull.video.VideoCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class BackupData(
    val settings: Settings = Settings(),
    val bookmarks: List<BookmarkEntity> = emptyList(),
    val shortcuts: List<ShortcutEntity> = emptyList(),
    val version: Int = 1,
)

private val prettyJson = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
}

object BackupRestore {
    fun serialize(
        settings: Settings,
        bookmarks: List<BookmarkEntity>,
        shortcuts: List<ShortcutEntity>,
    ): String = serialize(BackupData(settings = settings, bookmarks = bookmarks, shortcuts = shortcuts))

    fun serialize(data: BackupData): String {
        val s = data.settings
        val root = buildJsonObject {
            put("version", data.version)
            put(
                "settings",
                buildJsonObject {
                    put("themeMode", s.themeMode.name)
                    put("dynamicColor", s.dynamicColor)
                    put("connections", s.connections)
                    put("maxDownloads", s.maxDownloads)
                    put("autoResume", s.autoResume)
                    put("scheduleEnabled", s.scheduleEnabled)
                    put("scheduleStartHour", s.scheduleStartHour)
                    put("scheduleEndHour", s.scheduleEndHour)
                    put("torrentSequential", s.torrentSequential)
                    put("ytdlpChannel", s.ytdlpChannel)
                    put("ytdlpAutoUpdate", s.ytdlpAutoUpdate)
                    put("torrentDht", s.torrentDht)
                    put("torrentLsd", s.torrentLsd)
                    put("torrentUtp", s.torrentUtp)
                    put("torrentPortMapping", s.torrentPortMapping)
                    put("torrentEncryption", s.torrentEncryption.name)
                    put("torrentDownLimit", s.torrentDownLimit)
                    put("torrentUpLimit", s.torrentUpLimit)
                    put("torrentConnections", s.torrentConnections)
                    put("torrentSeed", s.torrentSeed)
                    put("torrentSeedRatio", s.torrentSeedRatio)
                    put("torrentDefaultTrackers", s.torrentDefaultTrackers)
                    put("videoMaxRes", s.video.maxRes)
                    put("videoCodec", s.video.codec.name)
                    put("videoContainer", s.video.container.name)
                    put("audioFormat", s.video.audio.name)
                    put("videoSubtitles", s.video.subtitles)
                    put("subtitleLangs", s.video.subtitleLangs)
                    put("videoThumbnail", s.video.thumbnail)
                    put("videoMetadata", s.video.metadata)
                    put("sponsorBlock", s.video.sponsorBlock)
                    put("videoMultiConnection", s.videoMultiConnection)
                    put("updatesEnabled", s.updatesEnabled)
                    s.updateChannel?.let { put("updateChannel", it.name) }
                    put("browserAdBlock", s.browserAdBlock)
                    put("browserPopupBlock", s.browserPopupBlock)
                    put("browserCosmetic", s.browserCosmetic)
                    put("browserSearchEngine", s.browserSearchEngine.name)
                    put("browserInterceptByExtension", s.browserInterceptByExtension)
                },
            )
            put(
                "bookmarks",
                buildJsonArray {
                    data.bookmarks.forEach { b ->
                        add(
                            buildJsonObject {
                                put("url", b.url)
                                put("title", b.title)
                                put("createdAt", b.createdAt)
                            },
                        )
                    }
                },
            )
            put(
                "shortcuts",
                buildJsonArray {
                    data.shortcuts.forEach { sc ->
                        add(
                            buildJsonObject {
                                put("url", sc.url)
                                put("title", sc.title)
                                put("createdAt", sc.createdAt)
                            },
                        )
                    }
                },
            )
        }
        return prettyJson.encodeToString(JsonObject.serializer(), root)
    }

    fun deserialize(jsonText: String): BackupData {
        val root = prettyJson.parseToJsonElement(jsonText).jsonObject
        val defaults = Settings()
        val version = root["version"]?.jsonPrimitive?.intOrNull ?: 1
        val sObj = root["settings"]?.jsonObject

        fun JsonObject?.str(key: String): String? = this?.get(key)?.jsonPrimitive?.contentOrNull
        fun JsonObject?.bool(key: String, def: Boolean): Boolean = this?.get(key)?.jsonPrimitive?.booleanOrNull ?: def
        fun JsonObject?.int(key: String, def: Int): Int = this?.get(key)?.jsonPrimitive?.intOrNull ?: def
        fun <E : Enum<E>> JsonObject?.enumVal(key: String, def: E, values: Array<E>): E {
            val raw = str(key) ?: return def
            return values.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: def
        }

        val settings = if (sObj == null) {
            defaults
        } else {
            Settings(
                themeMode = sObj.enumVal("themeMode", defaults.themeMode, ThemeMode.entries.toTypedArray()),
                dynamicColor = sObj.bool("dynamicColor", defaults.dynamicColor),
                connections = sObj.int("connections", defaults.connections).coerceIn(1, 32),
                maxDownloads = sObj.int("maxDownloads", defaults.maxDownloads).coerceIn(1, 10),
                autoResume = sObj.bool("autoResume", defaults.autoResume),
                scheduleEnabled = sObj.bool("scheduleEnabled", defaults.scheduleEnabled),
                scheduleStartHour = sObj.int("scheduleStartHour", defaults.scheduleStartHour).coerceIn(0, 23),
                scheduleEndHour = sObj.int("scheduleEndHour", defaults.scheduleEndHour).coerceIn(0, 23),
                torrentSequential = sObj.bool("torrentSequential", defaults.torrentSequential),
                ytdlpChannel = sObj.str("ytdlpChannel") ?: defaults.ytdlpChannel,
                ytdlpAutoUpdate = sObj.bool("ytdlpAutoUpdate", defaults.ytdlpAutoUpdate),
                torrentDht = sObj.bool("torrentDht", defaults.torrentDht),
                torrentLsd = sObj.bool("torrentLsd", defaults.torrentLsd),
                torrentUtp = sObj.bool("torrentUtp", defaults.torrentUtp),
                torrentPortMapping = sObj.bool("torrentPortMapping", defaults.torrentPortMapping),
                torrentEncryption = sObj.enumVal("torrentEncryption", defaults.torrentEncryption, Encryption.entries.toTypedArray()),
                torrentDownLimit = sObj.int("torrentDownLimit", defaults.torrentDownLimit).coerceAtLeast(0),
                torrentUpLimit = sObj.int("torrentUpLimit", defaults.torrentUpLimit).coerceAtLeast(0),
                torrentConnections = sObj.int("torrentConnections", defaults.torrentConnections).coerceIn(20, 500),
                torrentSeed = sObj.bool("torrentSeed", defaults.torrentSeed),
                torrentSeedRatio = sObj.int("torrentSeedRatio", defaults.torrentSeedRatio).coerceAtLeast(0),
                torrentDefaultTrackers = sObj.str("torrentDefaultTrackers") ?: defaults.torrentDefaultTrackers,
                video = VideoChoice(
                    maxRes = sObj.int("videoMaxRes", defaults.video.maxRes).coerceAtLeast(0),
                    codec = sObj.enumVal("videoCodec", defaults.video.codec, VideoCodec.entries.toTypedArray()),
                    container = sObj.enumVal("videoContainer", defaults.video.container, Container.entries.toTypedArray()),
                    audio = sObj.enumVal("audioFormat", defaults.video.audio, AudioFormat.entries.toTypedArray()),
                    subtitles = sObj.bool("videoSubtitles", defaults.video.subtitles),
                    subtitleLangs = sObj.str("subtitleLangs") ?: defaults.video.subtitleLangs,
                    thumbnail = sObj.bool("videoThumbnail", defaults.video.thumbnail),
                    metadata = sObj.bool("videoMetadata", defaults.video.metadata),
                    sponsorBlock = sObj.bool("sponsorBlock", defaults.video.sponsorBlock),
                ),
                videoMultiConnection = sObj.bool("videoMultiConnection", defaults.videoMultiConnection),
                updatesEnabled = sObj.bool("updatesEnabled", defaults.updatesEnabled),
                updateChannel = sObj.str("updateChannel")?.let { ch ->
                    UpdateChannel.entries.firstOrNull { it.name.equals(ch, ignoreCase = true) }
                },
                browserAdBlock = sObj.bool("browserAdBlock", defaults.browserAdBlock),
                browserPopupBlock = sObj.bool("browserPopupBlock", defaults.browserPopupBlock),
                browserCosmetic = sObj.bool("browserCosmetic", defaults.browserCosmetic),
                browserSearchEngine = sObj.enumVal("browserSearchEngine", defaults.browserSearchEngine, SearchEngine.entries.toTypedArray()),
                browserInterceptByExtension = sObj.bool("browserInterceptByExtension", defaults.browserInterceptByExtension),
            )
        }

        val bookmarks = root["bookmarks"]?.jsonArray?.mapNotNull { el ->
            val obj = el.jsonObject
            val url = obj["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: url
            val createdAt = obj["createdAt"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis()
            BookmarkEntity(url = url, title = title, createdAt = createdAt)
        }.orEmpty()

        val shortcuts = root["shortcuts"]?.jsonArray?.mapNotNull { el ->
            val obj = el.jsonObject
            val url = obj["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: url
            val createdAt = obj["createdAt"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis()
            ShortcutEntity(url = url, title = title, createdAt = createdAt)
        }.orEmpty()

        return BackupData(
            settings = settings,
            bookmarks = bookmarks,
            shortcuts = shortcuts,
            version = version,
        )
    }
}

fun serializeBackup(
    settings: Settings,
    bookmarks: List<BookmarkEntity>,
    shortcuts: List<ShortcutEntity>,
): String = BackupRestore.serialize(settings, bookmarks, shortcuts)

fun deserializeBackup(jsonText: String): BackupData = BackupRestore.deserialize(jsonText)
