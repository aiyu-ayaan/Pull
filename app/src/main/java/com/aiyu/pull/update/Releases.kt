package com.aiyu.pull.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What a GitHub release is, and which one of them this device should install.
 *
 * All of it is pure: no Android, no network, no clock. The release workflow names versions
 * `1.0.1-alpha.1`, `1.0.1-beta.1` and `1.0.1`, and attaches one `Pull-<version>-<abi>.apk` per ABI. See `.github/workflows/release.yml`.
 */

/** Which builds a device is willing to be offered. */
enum class UpdateChannel(internal val stage: Int) {
    STABLE(Version.STABLE),
    BETA(Version.BETA),
    ALPHA(Version.ALPHA);

    /** A channel takes its own builds and everything steadier, so beta still sees the stable that supersedes it. */
    fun accepts(version: Version): Boolean = version.stage >= stage

    companion object {
        /** Default channel: the one this build came from, so an alpha install isn't stranded on stable. */
        fun forVersion(versionName: String): UpdateChannel = when (Version.parse(versionName)?.stage) {
            Version.ALPHA -> ALPHA
            Version.BETA -> BETA
            else -> STABLE
        }
    }
}

/**
 * Ordered semantic version. Only what the release workflow produces is understood
 * (`1.2.3`, `1.2.3-alpha.4`, `1.2.3-beta.4`, optional leading `v`); anything else parses to null and is
 * skipped, because a guess here installs the wrong APK.
 */
data class Version(val major: Int, val minor: Int, val patch: Int, val stage: Int, val stageNumber: Int) : Comparable<Version> {

    override fun compareTo(other: Version): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch }, { it.stage }, { it.stageNumber })

    companion object {
        const val ALPHA = 0
        const val BETA = 1
        const val STABLE = 2

        private val PATTERN = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:-(alpha|beta)\.(\d+))?$""", RegexOption.IGNORE_CASE)

        fun parse(text: String?): Version? {
            val match = PATTERN.matchEntire(text?.trim().orEmpty()) ?: return null
            val (major, minor, patch, label, number) = match.destructured
            return Version(
                major.toInt(), minor.toInt(), patch.toInt(),
                stage = when (label.lowercase()) { "alpha" -> ALPHA; "beta" -> BETA; else -> STABLE },
                // A stable release has no pre-release number and must still beat `-beta.9` of the same version.
                stageNumber = number.toIntOrNull() ?: Int.MAX_VALUE,
            )
        }
    }
}

data class ReleaseAsset(val name: String, val url: String, val size: Long, /** Lowercase hex SHA-256 GitHub records for the file, when it does. */ val sha256: String? = null)

data class Release(val version: Version, val name: String, val notes: String, val publishedAt: String, val assets: List<ReleaseAsset>)

object Releases {
    /** A constant, not a setting: this project signs the builds, and Android refuses an update signed by another key. */
    const val REPOSITORY = "aiyu-ayaan/Pull"
    const val API = "https://api.github.com/repos/$REPOSITORY/releases?per_page=30"

    /** Newest release on [channel] newer than [installed], by version and never by publish date. */
    fun pick(releases: List<Release>, installed: Version?, channel: UpdateChannel): Release? =
        releases.filter { channel.accepts(it.version) && (installed == null || it.version > installed) }.maxByOrNull { it.version }

    /**
     * The APK built for this device: `Pull-<v>-<abi>.apk` for the first of [abis] (Build.SUPPORTED_ABIS, most
     * preferred first) the release carries. A release without one is not an offer.
     */
    fun apkFor(release: Release, abis: List<String>): ReleaseAsset? = abis.firstNotNullOfOrNull { abi ->
        release.assets.firstOrNull { it.name.startsWith("Pull-") && it.name.endsWith("-$abi.apk", ignoreCase = true) }
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** The releases GitHub returned, minus drafts and tags this app doesn't understand. */
    fun parse(body: String): List<Release> = json.decodeFromString<List<ReleaseDto>>(body).mapNotNull { r ->
        if (r.draft) return@mapNotNull null
        val version = Version.parse(r.tag) ?: return@mapNotNull null
        Release(
            version = version,
            name = (r.name?.ifBlank { null } ?: r.tag).removePrefix("v"), // shown next to BuildConfig.VERSION_NAME, which has no "v"
            notes = r.body.orEmpty(),
            publishedAt = r.publishedAt.orEmpty(),
            assets = r.assets.map { ReleaseAsset(it.name, it.url, it.size, it.digest?.removePrefix("sha256:")?.lowercase()) },
        )
    }
}

@Serializable private class ReleaseDto(
    @SerialName("tag_name") val tag: String = "",
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    @SerialName("published_at") val publishedAt: String? = null,
    val assets: List<AssetDto> = emptyList(),
)

@Serializable private class AssetDto(
    val name: String = "",
    @SerialName("browser_download_url") val url: String = "",
    val size: Long = 0,
    val digest: String? = null,
)
