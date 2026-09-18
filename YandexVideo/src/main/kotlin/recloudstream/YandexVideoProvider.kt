package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class YandexVideoProvider : MainAPI() {
    override var mainUrl = "https://yandex.com/video"
    override var name = "Yandex Video"
    override var lang = "ru"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Others)
    override val hasMainPage = true

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/131 Mobile Safari/537.36",
        "Accept-Language" to "ru-RU,ru;q=0.9,en;q=0.8"
    )

    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)
    private fun absolute(base: String, value: String) =
        runCatching { URI.create(base).resolve(value).toString() }.getOrDefault(value)

    private suspend fun getDocument(url: String) =
        Jsoup.parse(app.get(url, headers = headers).text, url)

    private fun imageUrl(element: org.jsoup.nodes.Element, base: String): String? {
        val raw = listOf("src", "data-src", "data-original")
            .firstNotNullOfOrNull { element.attr(it).takeIf(String::isNotBlank) } ?: return null
        return absolute(base, raw).takeIf { it.startsWith("http", true) }
    }

    private fun titleOf(element: org.jsoup.nodes.Element): String =
        element.attr("aria-label").ifBlank { element.attr("title") }
            .ifBlank { element.selectFirst("img")?.attr("alt").orEmpty() }
            .ifBlank { element.text().trim() }
            .ifBlank { "Yandex Video" }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) mainUrl else "$mainUrl/?page=$page"
        val doc = runCatching { getDocument(url) }.getOrNull()
            ?: return newHomePageResponse(emptyList(), false)

        val items = doc.select(
            "a[href*='/video/preview/'], a[href*='/video/'], " +
            "a[href*='yandex.com/video/'], a[href*='yandex.ru/video/']"
        ).mapNotNull { element ->
            val href = element.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val full = absolute(url, href)
            if (!full.startsWith("http")) return@mapNotNull null
            newMovieSearchResponse(titleOf(element), full, TvType.Movie) {
                posterUrl = element.selectFirst("img")?.let { imageUrl(it, url) }
            }
        }.distinctBy { it.url }.take(30)

        return newHomePageResponse(
            listOf(HomePageList("Yandex Video", items, isHorizontalImages = true)),
            hasNext = items.isNotEmpty() && page < 5
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?text=${encode(query)}"
        val doc = runCatching { getDocument(url) }.getOrNull() ?: return emptyList()
        return doc.select(
            "a[href*='/video/preview/'], a[href*='/video/'], " +
            "a[href*='yandex.com/video/'], a[href*='yandex.ru/video/']"
        ).mapNotNull { element ->
            val href = element.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val full = absolute(url, href)
            if (!full.startsWith("http")) return@mapNotNull null
            newMovieSearchResponse(titleOf(element), full, TvType.Movie) {
                posterUrl = element.selectFirst("img")?.let { imageUrl(it, url) }
            }
        }.distinctBy { it.url }.take(50)
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = getDocument(url)
        val title = doc.selectFirst("meta[property='og:title']")?.attr("content")
            ?.takeIf { it.isNotBlank() } ?: doc.title().ifBlank { "Yandex Video" }
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
        doc.select(
            "video source, video, source, iframe, " +
            "meta[property='og:video'], meta[property='og:video:url'], " +
            "a[href$='.m3u8'], a[href$='.mp4'], a[href$='.webm']"
        ).forEach { element ->
            val raw = listOf("src", "data-src", "content")
                .firstNotNullOfOrNull { element.attr(it).takeIf(String::isNotBlank) } ?: return@forEach
            val media = absolute(data, raw)
            if (!media.startsWith("http", true)) return@forEach
            when {
                media.contains(".m3u8", true) -> {
                    callback(newExtractorLink(name, name, media, ExtractorLinkType.M3U8) {
                        quality = Qualities.Unknown.value
                    })
                    found = true
                }
                media.contains(".mp4", true) || media.contains(".webm", true) -> {
                    callback(newExtractorLink(name, name, media, ExtractorLinkType.VIDEO) {
                        quality = Qualities.Unknown.value
                    })
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
