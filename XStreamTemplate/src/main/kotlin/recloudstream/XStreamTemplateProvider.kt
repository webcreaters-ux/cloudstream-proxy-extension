package recloudstream

import com.lagradost.cloudstream3.*

class XStreamTemplateProvider : MainAPI() {
    override var mainUrl = "https://xstream.cc"
    override var name = "XStream Template"
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Others)
    override val hasMainPage = true

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        return newHomePageResponse(
            listOf(HomePageList("XStream Template", emptyList(), isHorizontalImages = true)),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> = emptyList()

    override suspend fun load(url: String): LoadResponse {
        throw Error("This standalone template does not load adult media.")
    }
}
