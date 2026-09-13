package recloudstream

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private const val CONFIG_URL = "https://raw.githubusercontent.com/webcreaters-ux/cloudstream-proxy-extension/main/sites.json"

private val mapper = jacksonObjectMapper()

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

    private suspend fun config(): SourcesConfig {
        cachedConfig?.let { return it }
        val parsed = runCatching {
            mapper.readValue(app.get(CONFIG_URL).text, SourcesConfig::class.java)
        }.getOrDefault(SourcesConfig())
        cachedConfig = parsed
        return parsed
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun findSite(url: String, cfg: SourcesConfig): SiteConfig? {
        val targetHost = runCatching { URI(url).host }.getOrNull() ?: return null
        return cfg.sites.firstOrNull { site ->
            val host = runCatching { URI(site.baseUrl).host }.getOrNull()
            host != null && (targetHost == host || targetHost.endsWith(".$host"))
        }
    }

    private fun proxied(url: String, site: SiteConfig, cfg: SourcesConfig): String {
        val proxyName = site.proxy ?: return url
        val proxy = cfg.proxies.firstOrNull { it.name == proxyName && it.enabled } ?: return url
        return proxy.template.replace("{url}", encode(url))
    }

    private fun absolute(base: String, value: String): String {
        return runCatching { URI.create(base).resolve(value).toString() }.getOrDefault(value)
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

    override suspend fun search(query: String): List<SearchResponse> {
        val cfg = config()
        val results = mutableListOf<SearchResponse>()
        for (site in cfg.sites.filter { it.enabled && !it.searchUrl.isNullOrBlank() }) {
            val url = site.searchUrl!!
                .replace("{query}", encode(query))
                .replace("{page}", "1")
            val document = runCatching {
                Jsoup.parse(app.get(proxied(url, site, cfg)).text, site.baseUrl)
            }.getOrNull() ?: continue
            document.select(site.resultSelector).forEach { element ->
                val href = element.attr("href").takeIf { it.isNotBlank() } ?: return@forEach
                val title = site.titleSelector?.let { document.select(it).firstOrNull()?.text() }
                    ?: element.text().ifBlank { site.name }
                results += newMovieSearchResponse(title, absolute(site.baseUrl, href), TvType.Movie)
            }
        }
        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val cfg = config()
        val site = findSite(url, cfg) ?: throw ErrorLoadingException("No configured site for $url")
        val document = Jsoup.parse(app.get(proxied(url, site, cfg)).text, url)
        val title = site.titleSelector?.let { document.select(it).firstOrNull()?.text() }
            ?: document.title().ifBlank { site.name }
        val poster = site.posterSelector?.let { document.select(it).firstOrNull()?.attr("src") }
            ?.let { absolute(url, it) }
        val description = site.descriptionSelector?.let { document.select(it).firstOrNull()?.text() }
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
        val document = Jsoup.parse(app.get(proxied(data, site, cfg)).text, data)
        var found = false
        document.select(site.mediaSelector).forEach { element ->
            val raw = element.attr("src").takeIf { it.isNotBlank() } ?: return@forEach
            val mediaUrl = absolute(data, raw)
            when {
                mediaUrl.contains(".m3u8", ignoreCase = true) -> {
                    callback(newExtractorLink(name, name, mediaUrl, ExtractorLinkType.M3U8) {
                        quality = Qualities.Unknown.value
                    })
                    found = true
                }
                mediaUrl.contains(".mp4", ignoreCase = true) || mediaUrl.contains(".webm", ignoreCase = true) -> {
                    callback(newExtractorLink(name, name, mediaUrl, ExtractorLinkType.VIDEO) {
                        quality = Qualities.Unknown.value
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
