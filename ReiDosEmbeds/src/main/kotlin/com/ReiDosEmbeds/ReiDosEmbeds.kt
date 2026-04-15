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
        println("🚀 Iniciando getMainPage...")
        
        val doc = app.get(siteUrl).document
        println("✅ Página carregada: $siteUrl")
        
        val channels = mutableListOf<SearchResponse>()
        val cards = doc.select(".card")
        
        println("🎴 Encontrados ${cards.size} cards")
        
        for (card in cards) {
            val link = card.selectFirst("a[href*='rde.buzz']")
            if (link == null) {
                println("⚠️ Card sem link encontrado")
                continue
            }
            
            val channelUrl = link.attr("href")
            val name = card.selectFirst("h4")?.text()?.trim() 
                ?: card.selectFirst("h3")?.text()?.trim()
            
            if (name == null) {
                println("⚠️ Card sem nome encontrado")
                continue
            }
            
            val img = card.selectFirst("img")
            var posterUrl = img?.attr("src") ?: img?.attr("data-src") ?: ""
            if (posterUrl.startsWith("//")) posterUrl = "https:$posterUrl"
            
            println("  📺 Canal: '$name' -> $channelUrl")
            
            channels.add(
                newLiveSearchResponse(name, channelUrl, TvType.Live) {
                    this.posterUrl = posterUrl
                }
            )
        }
        
        if (channels.isEmpty()) {
            println("❌ Nenhum canal encontrado!")
            throw ErrorLoadingException("Nenhum canal encontrado")
        }

        println("🎉 Total de ${channels.size} canais carregados!")
        return newHomePageResponse(
            listOf(HomePageList("📺 Todos os Canais", channels, isHorizontalImages = true)),
            hasNext = false
        )
    }

    override suspend fun load(url: String): LoadResponse {
        println("📖 Carregando canal: $url")
        
        val doc = app.get(url).document
        val title = doc.selectFirst("title")?.text()?.replace("Assistindo ", "") ?: "Canal"
        
        println("📺 Título do canal: $title")
        
        return newMovieLoadResponse(title, url, TvType.Live, url)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        println("🔎 Buscando por: '$query'")
        
        val doc = app.get(siteUrl).document
        val results = mutableListOf<SearchResponse>()
        
        for (card in doc.select(".card")) {
            val link = card.selectFirst("a[href*='rde.buzz']") ?: continue
            val name = card.selectFirst("h4, h3")?.text()?.trim() ?: continue
            
            if (name.contains(query, ignoreCase = true)) {
                println("✅ Encontrado: '$name' -> ${link.attr("href")}")
                results.add(newLiveSearchResponse(name, link.attr("href"), TvType.Live))
            }
        }
        
        println("🔎 Busca finalizada: ${results.size} resultados")
        return results
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🎬 Carregando links para: $data")
        
        val html = app.get(data).text
        println("📄 HTML obtido, tamanho: ${html.length} caracteres")
        
        val pattern = Regex(""""sources":\s*\[\s*\{\s*"src":\s*"([^"]+\.txt[^"]*?)"""")
        val match = pattern.find(html)
        
        if (match == null) {
            println("❌ Padrão 'sources' não encontrado no HTML")
            return false
        }
        
        var streamUrl = match.groupValues[1].replace("\\/", "/")
        println("✅ Stream URL encontrada: $streamUrl")
        
        M3u8Helper.generateM3u8(
            name,
            streamUrl,
            data,
            headers = mapOf("Referer" to data)
        ).forEach { link ->
            println("  🔗 Link gerado: ${link.url}")
            callback(link)
        }
        
        println("🎉 Links carregados com sucesso!")
        return true
    }
}
