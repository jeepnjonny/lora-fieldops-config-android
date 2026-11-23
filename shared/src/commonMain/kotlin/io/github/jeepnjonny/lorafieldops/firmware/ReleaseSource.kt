package io.github.jeepnjonny.lorafieldops.firmware

import io.github.jeepnjonny.lorafieldops.model.FirmwareVersion
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One downloadable file of a release. */
data class ReleaseAsset(val name: String, val size: Long, val url: String)

/** A published firmware release. */
data class FirmwareRelease(val tag: String, val pageUrl: String, val assets: List<ReleaseAsset>) {
    val version: FirmwareVersion? get() = FirmwareVersion.parse(tag)
    fun asset(name: String): ReleaseAsset? = assets.firstOrNull { it.name == name }
}

/**
 * Where the app learns about new firmware. Today that is the GitHub Releases API; the
 * firmware repo plans a manifest with per-board version, size and SHA-256 (its
 * Phase 4), which can replace this without touching the updater.
 */
fun interface ReleaseSource {
    suspend fun latest(): FirmwareRelease
}

/** Latest non-prerelease release of the tracker firmware on GitHub. */
class GitHubReleaseSource(
    private val get: suspend (url: String) -> ByteArray,
    private val repo: String = "KJ7NYE/LoRa_FieldOps_APRS_Tracker",
) : ReleaseSource {
    override suspend fun latest(): FirmwareRelease =
        parseGitHubRelease(get("https://api.github.com/repos/$repo/releases/latest").decodeToString())
}

@Serializable
private class GhRelease(
    @SerialName("tag_name") val tag: String,
    @SerialName("html_url") val pageUrl: String = "",
    val assets: List<GhAsset> = emptyList(),
)

@Serializable
private class GhAsset(
    val name: String,
    val size: Long = 0,
    @SerialName("browser_download_url") val url: String,
)

private val json = Json { ignoreUnknownKeys = true }

internal fun parseGitHubRelease(body: String): FirmwareRelease {
    val r = json.decodeFromString(GhRelease.serializer(), body)
    return FirmwareRelease(r.tag, r.pageUrl, r.assets.map { ReleaseAsset(it.name, it.size, it.url) })
}
