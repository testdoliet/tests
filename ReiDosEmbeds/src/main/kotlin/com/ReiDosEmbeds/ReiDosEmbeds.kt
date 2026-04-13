package com.reidosembeds

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class ReiDosEmbedsProvider : BasePlugin() {
    override fun load() {
        registerMainAPI(ReiDosEmbeds())
    }
}

class ReiDosEmbeds : MainAPI() {
    override var name = "Rei dos Embeds"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = false
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Live)

    private val siteUrl = "https://reidosembeds.com"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(siteUrl).document
        val categories = mutableListOf<HomePageList>()

        // Extrai todas as abas do data-channels-tabs
        val tabs = doc.select("[data-channels-tabs] a")
        
        for (tab in tabs) {
            val tabName = tab.text().trim()
            if (tabName.isBlank()) continue
            
            val href = tab.attr("href")
            val genre = href.substringAfter("?genre=").substringBefore("&")
            
            // Faz requisição AJAX para cada categoria
            val categoryUrl = if (genre.isNotEmpty()) {
                "$siteUrl?genre=$genre"
            } else {
                siteUrl
            }
            
            val categoryDoc = app.get(categoryUrl, headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest"
            )).document
            
            val channels = extractChannels(categoryDoc)
            
            if (channels.isNotEmpty()) {
                val displayName = if (tabName == "Todos") "📺 $tabName" else tabName
                categories.add(HomePageList(displayName, channels, isHorizontalImages = true))
            }
        }

        if (categories.isEmpty()) {
            throw ErrorLoadingException("Nenhum canal encontrado")
        }

        return newHomePageResponse(categories, hasNext = false)
    }

    private suspend fun extractChannels(doc: org.jsoup.nodes.Document): List<SearchResponse> {
        val channels = mutableListOf<SearchResponse>()
        val cards = doc.select(".card")
        
        for (card in cards) {
            val link = card.selectFirst("a[href*='rde.buzz']") ?: continue
            val channelUrl = link.attr("href")
            val name = card.selectFirst("h4")?.text()?.trim() 
                ?: card.selectFirst("h3")?.text()?.trim() 
                ?: continue
            
            val img = card.selectFirst("img")
            var posterUrl = img?.attr("src") ?: img?.attr("data-src") ?: ""
            if (posterUrl.startsWith("//")) posterUrl = "https:$posterUrl"
            
            channels.add(
                newLiveSearchResponse(name, channelUrl, TvType.Live) {
                    this.posterUrl = posterUrl
                }
            )
        }
        return channels
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("title")?.text()?.replace("Assistindo ", "") ?: "Canal"
        
        return newMovieLoadResponse(title, url, TvType.Live, url)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val doc = app.get(siteUrl).document
        val results = mutableListOf<SearchResponse>()
        
        for (card in doc.select(".card")) {
            val link = card.selectFirst("a[href*='rde.buzz']") ?: continue
            val name = card.selectFirst("h4, h3")?.text()?.trim() ?: continue
            
            if (name.contains(query, ignoreCase = true)) {
                results.add(newLiveSearchResponse(name, link.attr("href"), TvType.Live))
            }
        }
        return results
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = app.get(data).text
        val pattern = Regex(""""sources":\s*\[\s*\{\s*"src":\s*"([^"]+\.txt[^"]*?)"""")
        val match = pattern.find(html) ?: return false
        
        val streamUrl = match.groupValues[1].replace("\\/", "/")
        
        M3u8Helper.generateM3u8(
            name,
            streamUrl,
            data,
            headers = mapOf("Referer" to data)
        ).forEach(callback)
        
        return true
    }
}
