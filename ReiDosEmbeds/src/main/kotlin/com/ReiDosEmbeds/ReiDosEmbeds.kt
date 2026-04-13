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
        
        val categories = mutableListOf<HomePageList>()

        // Extrai todas as abas do data-channels-tabs
        val tabs = doc.select("[data-channels-tabs] a")
        println("📑 Encontradas ${tabs.size} abas")
        
        for ((index, tab) in tabs.withIndex()) {
            val tabName = tab.text().trim()
            if (tabName.isBlank()) continue
            
            val href = tab.attr("href")
            val genre = href.substringAfter("?genre=").substringBefore("&")
            
            println("🔄 [$index] Processando aba: '$tabName' (genre: '$genre')")
            
            // Faz requisição AJAX para cada categoria
            val categoryUrl = if (genre.isNotEmpty()) {
                "$siteUrl?genre=$genre"
            } else {
                siteUrl
            }
            
            println("📡 Fazendo requisição AJAX para: $categoryUrl")
            
            val categoryDoc = app.get(categoryUrl, headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest"
            )).document
            
            val channels = extractChannels(categoryDoc)
            println("📺 Encontrados ${channels.size} canais em '$tabName'")
            
            if (channels.isNotEmpty()) {
                val displayName = if (tabName == "Todos") "📺 $tabName" else tabName
                categories.add(HomePageList(displayName, channels, isHorizontalImages = true))
                println("✅ Categoria adicionada: '$displayName'")
            } else {
                println("⚠️ Nenhum canal encontrado em '$tabName'")
            }
        }

        if (categories.isEmpty()) {
            println("❌ Nenhuma categoria encontrada!")
            throw ErrorLoadingException("Nenhum canal encontrado")
        }

        println("🎉 Total de ${categories.size} categorias carregadas com sucesso!")
        return newHomePageResponse(categories, hasNext = false)
    }

    private suspend fun extractChannels(doc: org.jsoup.nodes.Document): List<SearchResponse> {
        println("🔍 Extraindo canais do documento...")
        val channels = mutableListOf<SearchResponse>()
        val cards = doc.select(".card")
        
        println("🎴 Encontrados ${cards.size} cards")
        
        for ((index, card) in cards.withIndex()) {
            val link = card.selectFirst("a[href*='rde.buzz']")
            if (link == null) {
                println("⚠️ Card $index: link não encontrado")
                continue
            }
            
            val channelUrl = link.attr("href")
            val name = card.selectFirst("h4")?.text()?.trim() 
                ?: card.selectFirst("h3")?.text()?.trim()
            
            if (name == null) {
                println("⚠️ Card $index: nome não encontrado")
                continue
            }
            
            val img = card.selectFirst("img")
            var posterUrl = img?.attr("src") ?: img?.attr("data-src") ?: ""
            if (posterUrl.startsWith("//")) posterUrl = "https:$posterUrl"
            
            println("  📺 [$index] Canal: '$name' -> $channelUrl")
            
            channels.add(
                newLiveSearchResponse(name, channelUrl, TvType.Live) {
                    this.posterUrl = posterUrl
                }
            )
        }
        
        println("✅ Extração finalizada: ${channels.size} canais")
        return channels
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
        
        println("📡 Gerando links M3U8...")
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
