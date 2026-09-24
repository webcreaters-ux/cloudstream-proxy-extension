package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class PlexFreeProvider : MainAPI() {
    override var mainUrl = "https://watch.plex.tv"
    override var name = "Plex"
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Others)
    override val hasMainPage = true

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/131 Mobile Safari/537.36",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    private fun absolute(base: String, value: String) =
        runCatching { URI.create(base).resolve(value).toString() }.getOrDefault(value)

    private suspend fun document(url: String) =
        Jsoup.parse(app.get(url, headers = headers).text, url)

    private fun titleOf(e: org.jsoup.nodes.Element): String =
        e.attr("aria-label").ifBlank { e.attr("title") }
            .ifBlank { e.selectFirst("img")?.attr("alt").orEmpty() }
            .ifBlank { e.text().trim() }
            .ifBlank { name }

    private fun cards(doc: org.jsoup.nodes.Document, base: String): List<SearchResponse> =
        doc.select("a[href]").mapNotNull { e ->
            val href = e.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val url = absolute(base, href)
            if (!url.startsWith("http") || !url.contains("plex.tv", true)) return@mapNotNull null
            if (url == mainUrl || url.endsWith("/")) return@mapNotNull null
            newMovieSearchResponse(titleOf(e), url, TvType.Movie) {
                posterUrl = e.selectFirst("img")?.let {
                    listOf("src", "data-src", "data-original")
                        .firstNotNullOfOrNull { a -> it.attr(a).takeIf(String::isNotBlank) }
                        ?.let { raw -> absolute(base, raw) }
                }
            }
        }.distinctBy { it.url }.take(40)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) mainUrl else "$mainUrl/?page=$page"
        val items = runCatching { cards(document(url), url) }.getOrDefault(emptyList())
        return newHomePageResponse(
            listOf(HomePageList(name, items, isHorizontalImages = true)),
            hasNext = items.isNotEmpty() && page < 5
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8)
        val candidates = listOf(
            "$mainUrl/search?query=$encoded",
            "$mainUrl/search?q=$encoded",
            "$mainUrl/?search=$encoded"
        )
        for (url in candidates) {
            val items = runCatching { cards(document(url), url) }.getOrDefault(emptyList())
            if (items.isNotEmpty()) return items
        }
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = document(url)
        val title = doc.selectFirst("meta[property='og:title']")?.attr("content")
            ?.takeIf { it.isNotBlank() } ?: doc.title().ifBlank { name }
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
        val doc = document(data)
        var found = false
        doc.select(
            "video source, video, source, iframe, " +
            "meta[property='og:video'], meta[property='og:video:url'], " +
            "a[href$='.m3u8'], a[href$='.mp4'], a[href$='.webm']"
        ).forEach { e ->
            val raw = listOf("src", "data-src", "content", "href")
                .firstNotNullOfOrNull { a -> e.attr(a).takeIf(String::isNotBlank) }
                ?: return@forEach
            val media = absolute(data, raw)
            if (!media.startsWith("http", true)) return@forEach
            when {
                media.contains(".m3u8", true) -> {
                    callback(newExtractorLink(name, name, media, ExtractorLinkType.M3U8))
                    found = true
                }
                media.contains(".mp4", true) || media.contains(".webm", true) -> {
                    callback(newExtractorLink(name, name, media, ExtractorLinkType.VIDEO))
                    found = true
                }
                else -> runCatching {
                    loadExtractor(media, data, subtitleCallback, callback)
                    found = true
                }
            }
        }
        return found
    }
}
