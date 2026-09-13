package recloudstream

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.StringUtils.encodeUri
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup

private const val CONFIG_URL = "https://raw.githubusercontent.com/webcreaters-ux/cloudstream-proxy-extension/main/sites.json"

data class ProxyConfig(val name: String, val template: String, val enabled: Boolean = true)

data class SiteConfig(
    val name: String,
    val baseUrl: String,
    val enabled: Boolean = true,
    val searchUrl: String? = null,
    val resultSelector: String = "a[href]",
    val linkSelector: String = "a[href]",
    val titleSelector: String? = null,
    val posterSelector: String? = null,
    val descriptionSelector: String? = null,
    val mediaSelector: String = "video source, video, source, iframe",
    val proxy: String? = null
)

data class SourcesConfig(val sites: List<SiteConfig> = emptyList(), val proxies: List<ProxyConfig> = emptyList())

class WebSourceProvider : MainAPI() {
    override var mainUrl = "https://github.com/webcreaters-ux/cloudstream-proxy-extension"
    override var name = "Web Sources"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Others)
    override var lang = "en"
    override val hasMainPage = true

    private var cachedConfig: SourcesConfig? = null

    private suspend fun config(): SourcesConfig {
        cachedConfig?.let { return it }
        val parsed = tryParseJson<SourcesConfig>(app.get(CONFIG_URL).text) ?: SourcesConfig()
        cachedConfig = parsed
        return parsed
    }

    private fun findSite(url: String, cfg: SourcesConfig): SiteConfig? {
        val targetHost = runCatching { java.net.URI(url).host }.getOrNull() ?: return null
        return cfg.sites.firstOrNull { site ->
            val host = runCatching { java.net.URI(site.baseUrl).host }.getOrNull()
            host != null && (targetHost == host || targetHost.endsWith(".$host"))
        }
    }

    private fun proxied(url: String, site: SiteConfig, cfg: SourcesConfig): String {
        val proxyName = site.proxy ?: return url
        val proxy = cfg.proxies.firstOrNull { it.name == proxyName && it.enabled } ?: return url
        return proxy.template.replace("{url}", url.encodeUri())
    }

    private fun absolute(base: String, value: String): String {
        return runCatching { java.net.URI(java.net.URI(base), value).toString() }.getOrDefault(value)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val cfg = config()
        val cards = cfg.sites.filter { it.enabled }.map {
            newMovieSearchResponse(it.name, it.baseUrl, TvType.Others)
        }
        return newHomePageResponse(
            listOf(HomePageList("Configured websites", cards, isHorizontalImages = true)),
            hasNext = false
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val cfg = config()
        val results = mutableListOf<SearchResponse>()

        for (site in cfg.sites.filter { it.enabled && !it.searchUrl.isNullOrBlank() }) {
            runCatching {
                val target = site.searchUrl!!
                    .replace("{query}", query.encodeUri())
                    .replace("{page}", page.toString())
                val url = proxied(target, site, cfg)
                val doc = Jsoup.parse(app.get(url, referer = site.baseUrl).text, target)

                doc.select(site.resultSelector).take(40).forEach { item ->
                    val linkElement = if (site.linkSelector.isBlank()) item else item.selectFirst(site.linkSelector)
                    val href = linkElement?.attr("href").orEmpty()
                    if (href.isBlank()) return@forEach

                    val realUrl = absolute(target, href)
                    val title = site.titleSelector?.let { item.selectFirst(it)?.text() }
                        ?.takeIf { it.isNotBlank() }
                        ?: item.text().trim().ifBlank { realUrl }

                    val poster = site.posterSelector?.let {
                        item.selectFirst(it)?.let { el ->
                            el.attr("src").ifBlank { el.attr("data-src") }
                        }
                    }

                    results += newMovieSearchResponse(title, realUrl, TvType.Movie) {
                        posterUrl = poster?.let { absolute(target, it) }
                    }
                }
            }
        }

        return results.distinctBy { it.url }.toNewSearchResponseList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val cfg = config()
        val site = findSite(url, cfg) ?: return null
        val target = proxied(url, site, cfg)
        val html = app.get(target, referer = site.baseUrl).text
        val doc = Jsoup.parse(html, url)

        val title = site.titleSelector?.let { doc.selectFirst(it)?.text() }
            ?.takeIf { it.isNotBlank() }
            ?: doc.title().ifBlank { url }

        val poster = site.posterSelector?.let {
            doc.selectFirst(it)?.let { el ->
                el.attr("src").ifBlank { el.attr("data-src") }
            }
        }

        val plot = site.descriptionSelector?.let { doc.selectFirst(it)?.text() }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = poster?.let { absolute(url, it) }
            this.plot = plot
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
        val target = proxied(data, site, cfg)
        val html = app.get(target, referer = site.baseUrl).text
        val doc = Jsoup.parse(html, data)
        var found = false

        doc.select(site.mediaSelector).forEach { element ->
            val raw = element.attr("src")
                .ifBlank { element.attr("data-src") }
                .ifBlank { element.attr("data-video") }
            if (raw.isBlank()) return@forEach

            val mediaUrl = absolute(data, raw)
            when {
                mediaUrl.contains(".m3u8", true) -> {
                    callback(newExtractorLink(name, "Web Source HLS", mediaUrl) {
                        type = ExtractorLinkType.M3U8
                        quality = Qualities.Unknown.value
                        referer = data
                    })
                    found = true
                }
                mediaUrl.matches(Regex("(?i).+\\.(mp4|webm)(\\?.*)?$")) -> {
                    callback(newExtractorLink(name, "Web Source", mediaUrl) {
                        type = ExtractorLinkType.VIDEO
                        quality = Qualities.Unknown.value
                        referer = data
                    })
                    found = true
                }
                element.tagName() == "iframe" -> {
                    runCatching {
                        loadExtractor(mediaUrl, subtitleCallback, callback)
                        found = true
                    }
                }
            }
        }

        return found
    }
}
