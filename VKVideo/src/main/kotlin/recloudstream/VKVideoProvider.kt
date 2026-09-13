package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class VKVideoProvider : MainAPI() {
    override var mainUrl = "https://vkvideo.ru"
    override var name = "VK Video"
    override var lang = "ru"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Others)

    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)
    private fun absolute(base: String, value: String) = runCatching { URI.create(base).resolve(value).toString() }.getOrDefault(value)
    private fun getDocument(url: String) = Jsoup.parse(
        app.get(url, headers = mapOf("User-Agent" to "Mozilla/5.0 (Android) CloudStream")).text,
        url
    )

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=${encode(query)}"
        val doc = runCatching { getDocument(url) }.getOrNull() ?: return emptyList()
        return doc.select("a[href*='/video'], a[href*='/clip'], a[href*='/live']")
            .mapNotNull { element ->
                val href = element.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val full = absolute(url, href)
                if (!full.startsWith("http")) return@mapNotNull null
                val title = element.attr("aria-label").ifBlank { element.attr("title") }.ifBlank { element.text().trim() }.ifBlank { "VK Video" }
                newMovieSearchResponse(title, full, TvType.Movie) {
                    posterUrl = element.selectFirst("img")?.let { img -> absolute(url, img.attr("src")) }
                }
            }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = getDocument(url)
        val title = doc.selectFirst("meta[property='og:title']")?.attr("content")
            ?: doc.title().ifBlank { "VK Video" }
        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
        val description = doc.selectFirst("meta[property='og:description']")?.attr("content")
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = poster
            plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = getDocument(data)
        var found = false
        doc.select("video source, video, source, iframe, meta[property='og:video'], meta[property='og:video:url']").forEach { element ->
            val raw = element.attr("src").ifBlank { element.attr("data-src") }.ifBlank { element.attr("content") }
            if (raw.isBlank()) return@forEach
            val media = absolute(data, raw)
            when {
                media.contains(".m3u8", true) -> {
                    callback(newExtractorLink(name, name, media, ExtractorLinkType.M3U8) { quality = Qualities.Unknown.value })
                    found = true
                }
                media.contains(".mp4", true) || media.contains(".webm", true) -> {
                    callback(newExtractorLink(name, name, media, ExtractorLinkType.VIDEO) { quality = Qualities.Unknown.value })
                    found = true
                }
                media.startsWith("http", true) -> {
                    runCatching { loadExtractor(media, data, subtitleCallback, callback) }
                    found = true
                }
            }
        }
        return found
    }
}
