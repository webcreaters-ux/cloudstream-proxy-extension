package recloudstream

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private const val BASE = "https://archive.org"
private const val SEARCH = "$BASE/advancedsearch.php"
private const val USER_AGENT = "Mozilla/5.0 (Android) CloudStream InternetArchiveProvider"

@JsonIgnoreProperties(ignoreUnknown = true)
data class IaDoc(val identifier: String? = null, val title: String? = null, val description: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class IaSearchResponse(val response: IaSearchBody? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class IaSearchBody(val docs: List<IaDoc> = emptyList())

@JsonIgnoreProperties(ignoreUnknown = true)
data class IaFile(
    val name: String? = null,
    val format: String? = null,
    val source: String? = null,
    val size: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class IaMetadata(
    val metadata: Map<String, Any?>? = null,
    val files: List<IaFile> = emptyList()
)

class InternetArchiveProvider : MainAPI() {
    override var mainUrl = BASE
    override var name = "Internet Archive"
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Others)
    override val hasMainPage = true

    private val mapper = jacksonObjectMapper()

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private suspend fun searchArchive(query: String, page: Int): List<IaDoc> {
        val url = "$SEARCH?q=${encode(query)}&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=description&rows=30&page=$page&output=json"
        return runCatching {
            mapper.readValue<IaSearchResponse>(app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).text)
                .response?.docs ?: emptyList()
        }.getOrDefault(emptyList())
    }

    private fun displayTitle(doc: IaDoc): String = doc.title?.trim().orEmpty().ifBlank { doc.identifier ?: "Internet Archive item" }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(emptyList(), hasNext = false)

        val collections = listOf(
            "featured" to "Featured",
            "mediatype:movies" to "Movies",
            "mediatype:etree" to "Audio & Concerts",
            "mediatype:software" to "Software"
        )

        val lists = collections.mapNotNull { (query, title) ->
            val docs = searchArchive(query, 1)
            if (docs.isEmpty()) null else HomePageList(
                title,
                docs.map { doc ->
                    newMovieSearchResponse(displayTitle(doc), "$BASE/details/${doc.identifier}", TvType.Movie) {
                        posterUrl = "$BASE/services/img/${doc.identifier}"
                    }
                },
                isHorizontalImages = true
            )
        }
        return newHomePageResponse(lists, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return searchArchive(query, 1).mapNotNull { doc ->
            val id = doc.identifier ?: return@mapNotNull null
            newMovieSearchResponse(displayTitle(doc), "$BASE/details/$id", TvType.Movie) {
                posterUrl = "$BASE/services/img/$id"
            }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val identifier = url.substringAfter("/details/").substringBefore('?').trim('/').ifBlank {
            throw ErrorLoadingException("Invalid Internet Archive item")
        }
        val metadataUrl = "$BASE/metadata/$identifier"
        val metadata = runCatching {
            mapper.readValue<IaMetadata>(app.get(metadataUrl, headers = mapOf("User-Agent" to USER_AGENT)).text)
        }.getOrElse { throw ErrorLoadingException("Could not load Internet Archive metadata") }

        val title = metadata.metadata?.get("title")?.toString()?.ifBlank { null } ?: identifier
        val description = metadata.metadata?.get("description")?.toString()
        return newMovieLoadResponse(title, url, TvType.Movie, identifier) {
            posterUrl = "$BASE/services/img/$identifier"
            plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val identifier = data.substringAfter("/details/").substringBefore('?').trim('/').ifBlank { data.trim('/') }
        val metadataUrl = "$BASE/metadata/$identifier"
        val metadata = runCatching {
            mapper.readValue<IaMetadata>(app.get(metadataUrl, headers = mapOf("User-Agent" to USER_AGENT)).text)
        }.getOrNull() ?: return false

        var found = false
        metadata.files.forEach { file ->
            val filename = file.name ?: return@forEach
            val format = file.format.orEmpty()
            val lower = filename.lowercase()
            val isVideo = lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".ogv") ||
                format.contains("MPEG4", true) || format.contains("WebM", true) || format.contains("Ogg Video", true)
            if (!isVideo || filename.contains("_thumb", true)) return@forEach

            val media = "$BASE/download/$identifier/${filename.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }}"
            val quality = when {
                lower.contains("2160") || lower.contains("4k") -> Qualities.P2160.value
                lower.contains("1440") -> Qualities.P1440.value
                lower.contains("1080") -> Qualities.P1080.value
                lower.contains("720") -> Qualities.P720.value
                lower.contains("480") -> Qualities.P480.value
                else -> Qualities.Unknown.value
            }

            if (lower.endsWith(".webm") || format.contains("WebM", true)) {
                callback(newExtractorLink(name, "Internet Archive", media, ExtractorLinkType.VIDEO) { this.quality = quality })
            } else {
                callback(newExtractorLink(name, "Internet Archive", media, ExtractorLinkType.VIDEO) { this.quality = quality })
            }
            found = true
        }
        return found
    }
}
