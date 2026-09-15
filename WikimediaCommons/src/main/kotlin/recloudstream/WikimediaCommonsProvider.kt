package recloudstream

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private const val API = "https://commons.wikimedia.org/w/api.php"
private const val UA = "Mozilla/5.0 (Android) CloudStream WikimediaCommonsProvider"

@JsonIgnoreProperties(ignoreUnknown = true)
data class CommonsImageInfo(val url: String? = null, val mime: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CommonsPage(val pageid: Long? = null, val title: String? = null, val imageinfo: List<CommonsImageInfo> = emptyList())

@JsonIgnoreProperties(ignoreUnknown = true)
data class CommonsQuery(val pages: Map<String, CommonsPage> = emptyMap())

@JsonIgnoreProperties(ignoreUnknown = true)
data class CommonsResponse(val query: CommonsQuery? = null)

class WikimediaCommonsProvider : MainAPI() {
    override var mainUrl = "https://commons.wikimedia.org"
    override var name = "Wikimedia Commons"
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.Others)

    private val mapper = jacksonObjectMapper()

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private suspend fun searchPages(query: String): List<CommonsPage> {
        val url = "$API?action=query&format=json&generator=search&gsrsearch=${encode(query)}&gsrnamespace=6&gsrlimit=30&prop=imageinfo&iiprop=url|mime"
        return runCatching {
            val response = mapper.readValue<CommonsResponse>(app.get(url, headers = mapOf("User-Agent" to UA)).text)
            response.query?.pages?.values?.toList().orEmpty()
        }.getOrDefault(emptyList())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return searchPages(query).mapNotNull { page ->
            val title = page.title?.removePrefix("File:")?.trim() ?: return@mapNotNull null
            val media = page.imageinfo.firstOrNull()?.url ?: return@mapNotNull null
            if (!isVideo(media, page.imageinfo.firstOrNull()?.mime)) return@mapNotNull null
            newMovieSearchResponse(title, media, TvType.Movie) {
                posterUrl = null
            }
        }.distinctBy { it.url }
    }

    private fun isVideo(url: String, mime: String?): Boolean {
        val value = (mime.orEmpty() + " " + url).lowercase()
        return listOf("video/mp4", "video/webm", "video/ogg", ".mp4", ".webm", ".ogv").any { value.contains(it) }
    }

    override suspend fun load(url: String): LoadResponse {
        val title = url.substringAfterLast('/').substringBefore('?').replace('_', ' ').ifBlank { "Wikimedia Commons video" }
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            plot = "Freely licensed or public-domain media hosted by Wikimedia Commons."
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val lower = data.lowercase()
        val type = if (lower.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        callback(newExtractorLink(name, name, data, type) { quality = Qualities.Unknown.value })
        return true
    }
}
