package recloudstream

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private const val CONFIG_URL = "https://raw.githubusercontent.com/webcreaters-ux/cloudstream-proxy-extension/main/sites.json"
private const val W3SCHOOLS_TEST_URL = "https://www.w3schools.com/html/html5_video.asp"

private val mapper = jacksonObjectMapper()

data class ProxyConfig(val name: String, val template: String, val enabled: Boolean = true)

data class SiteConfig(
    val name: String,
    val baseUrl: String,
    val enabled: Boolean = true,
    val iconUrl: String? = null,
    val searchUrl: String? = null,
    val resultSelector: String = "a[href]",
    val linkSelector: String = "a[href]",
    val titleSelector: String? = null,
    val posterSelector: String? = null,
    val descriptionSelector: String? = null,
    val mediaSelector: String = "video source, video, source, iframe, meta[property='og:video'], meta[property='og:video:url'], meta[property='og:video:secure_url']",
    val proxy: String? = null
)

data class SourcesConfig(
    val sites: List<SiteConfig> = emptyList(),
    val proxies: List<ProxyConfig> = emptyList()
)

class WebSourceProvider : MainAPI() {
    override var mainUrl = "https://github.com/webcreaters-ux/cloudstream-proxy-extension"
    override var name = "Web Sources"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Others)
    override var lang = "en"
    override val hasMainPage = true

    private var cachedConfig: SourcesConfig? = null

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8)

    private suspend fun config(): SourcesConfig {
        cachedConfig?.let { return it }
        val parsed = runCatching {
            mapper.readValue(app.get(CONFIG_URL, headers = mapOf("User-Agent" to WebSourceSettings.userAgent)).text, SourcesConfig::class.java)
        }.getOrDefault(SourcesConfig())
        cachedConfig = parsed
        return parsed
    }

    private fun findSite(url: String, cfg: SourcesConfig): SiteConfig? {
        val targetHost = runCatching { URI(url).host }.getOrNull() ?: return null
        return cfg.sites.firstOrNull { site ->
            val host = runCatching { URI(site.baseUrl).host }.getOrNull()
            host != null && (targetHost == host || targetHost.endsWith(".$host"))
        }
    }

    private fun proxied(url: String, site: SiteConfig, cfg: SourcesConfig): String {
        val proxyName = site.proxy
        val siteProxy = proxyName?.let { cfg.proxies.firstOrNull { p -> p.name == it && p.enabled } }
        val configured = if (siteProxy != null) {
            siteProxy.template.replace("{url}", encode(url))
        } else {
            url
        }
        return WebSourceSettings.applyProxy(configured)
    }

    private fun absolute(base: String, value: String): String {
        return runCatching { URI.create(base).resolve(value).toString() }.getOrDefault(value)
    }

    private fun icon(site: SiteConfig): String? = site.iconUrl?.replace("%size%", "128")

    private fun elementUrl(element: org.jsoup.nodes.Element, base: String): String? {
        val raw = element.attr("href").ifBlank {
            element.attr("src").ifBlank { element.attr("data-src") }.ifBlank { element.attr("content") }
        }
        return raw.takeIf { it.isNotBlank() }?.let { absolute(base, it) }
    }

    private fun elementTitle(element: org.jsoup.nodes.Element, site: SiteConfig): String {
        return element.attr("data-title").ifBlank { element.attr("aria-label") }.ifBlank { element.attr("title") }.ifBlank {
            element.selectFirst("img")?.attr("alt").orEmpty()
        }.ifBlank { element.text().trim() }.ifBlank { site.name }
    }

    private fun preferredQuality(): Int = when (WebSourceSettings.preferredQuality) {
        "2160p" -> Qualities.P2160.value
        "1440p" -> Qualities.P1440.value
        "1080p" -> Qualities.P1080.value
        "720p" -> Qualities.P720.value
        "480p" -> Qualities.P480.value
        else -> Qualities.Unknown.value
    }

    private fun requestHeaders(): Map<String, String> = mapOf("User-Agent" to WebSourceSettings.userAgent)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val cfg = config()
        val cards = cfg.sites.filter { it.enabled }.map { site ->
            newMovieSearchResponse(site.name, site.baseUrl, TvType.Others) {
                posterUrl = icon(site)
            }
        }
        return newHomePageResponse(
            listOf(HomePageList("Configured video sites", cards, isHorizontalImages = true)),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cfg = config()
        val results = mutableListOf<SearchResponse>()

        for (site in cfg.sites.filter { it.enabled }) {
            if (site.baseUrl == W3SCHOOLS_TEST_URL) {
                results += newMovieSearchResponse(
                    "${site.name} — $query",
                    site.baseUrl,
                    TvType.Movie
                ) {
                    posterUrl = icon(site)
                }
                continue
            }

            val template = site.searchUrl ?: continue
            val url = template
                .replace("{query}", encode(query))
                .replace("{page}", "1")

            val document = runCatching {
                Jsoup.parse(
                    app.get(proxied(url, site, cfg), headers = requestHeaders()).text,
                    site.baseUrl
                )
            }.getOrNull() ?: continue

            document.select(site.resultSelector).forEach { element ->
                val href = elementUrl(element, site.baseUrl) ?: return@forEach
                if (!href.startsWith("http", ignoreCase = true)) return@forEach
                val title = elementTitle(element, site)
                results += newMovieSearchResponse(title, href, TvType.Movie) {
                    posterUrl = icon(site)
                }
            }
        }

        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val cfg = config()
        val site = findSite(url, cfg) ?: throw ErrorLoadingException("No configured site for $url")
        val document = Jsoup.parse(
            app.get(proxied(url, site, cfg), headers = requestHeaders()).text,
            url
        )
        val title = site.titleSelector?.let { document.select(it).firstOrNull()?.text() }
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")
            ?: document.title().ifBlank { site.name }
        val poster = site.posterSelector?.let { selector ->
            document.select(selector).firstOrNull()?.let { element ->
                element.attr("src").ifBlank { element.attr("content") }
            }
        }?.let { absolute(url, it) }
            ?: document.selectFirst("meta[property='og:image']")?.attr("content")
        val description = site.descriptionSelector?.let { selector ->
            document.select(selector).firstOrNull()?.let { element ->
                element.attr("content").ifBlank { element.text() }
            }
        } ?: document.selectFirst("meta[property='og:description']")?.attr("content")

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
        val cfg = config()
        val site = findSite(data, cfg) ?: return false
        val document = Jsoup.parse(
            app.get(proxied(data, site, cfg), headers = requestHeaders()).text,
            data
        )
        var found = false
        val quality = preferredQuality()

        document.select(site.mediaSelector).forEach { element ->
            val raw = element.attr("src").takeIf { it.isNotBlank() }
                ?: element.attr("data-src").takeIf { it.isNotBlank() }
                ?: element.attr("content").takeIf { it.isNotBlank() }
                ?: return@forEach
            val mediaUrl = absolute(data, raw)

            when {
                mediaUrl.contains(".m3u8", ignoreCase = true) -> {
                    callback(newExtractorLink(name, name, mediaUrl, ExtractorLinkType.M3U8) {
                        this.quality = quality
                    })
                    found = true
                }
                mediaUrl.contains(".mp4", ignoreCase = true) || mediaUrl.contains(".webm", ignoreCase = true) -> {
                    callback(newExtractorLink(name, name, mediaUrl, ExtractorLinkType.VIDEO) {
                        this.quality = quality
                    })
                    found = true
                }
                mediaUrl.startsWith("http", ignoreCase = true) -> {
                    runCatching { loadExtractor(mediaUrl, data, subtitleCallback, callback) }
                    found = true
                }
            }
        }
        return found
    }
}
