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
            
            val genre = if (href.contains("?genre=")) {
                href.substringAfter("?genre=").substringBefore("&")
            } else {
                null
            }
            
            println("🔄 [$index] Processando aba: '$tabName' (genre: $genre)")
            
            val categoryUrl = if (genre != null) {
                "$siteUrl?genre=$genre"
            } else {
                siteUrl
            }
            
            println("📡 Fazendo requisição AJAX para: $categoryUrl")
            
            // Pega o HTML bruto como string
            val response = app.get(categoryUrl, headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest"
            ))
            val html = response.text
            
            // Extrai os canais usando regex diretamente no HTML
            val channels = extractChannelsFromHtml(html)
            println("📺 Encontrados ${channels.size} canais em '$tabName'")
            
            if (channels.isNotEmpty()) {
                val displayName = if (tabName == "Todos") "📺 $tabName" else tabName
                categories.add(HomePageList(displayName, channels, isHorizontalImages = true))
                println("✅ Categoria adicionada: '$displayName'")
            } else {
                println("⚠️ Nenhum canal encontrado em '$tabName'")
                // Debug: print primeiros 500 chars do HTML
                println("📄 HTML preview: ${html.take(500)}")
            }
        }

        if (categories.isEmpty()) {
            println("❌ Nenhuma categoria encontrada!")
            throw ErrorLoadingException("Nenhum canal encontrado")
        }

        println("🎉 Total de ${categories.size} categorias carregadas com sucesso!")
        return newHomePageResponse(categories, hasNext = false)
    }

    private fun extractChannelsFromHtml(html: String): List<SearchResponse> {
        val channels = mutableListOf<SearchResponse>()
        
        // Regex para encontrar cada card e extrair link, nome e imagem
        // Padrão: href="https://rde.buzz/..." e dentro do card tem h4 com nome
        val cardPattern = Regex("""<div[^>]*class="[^"]*card[^"]*"[^>]*>.*?<a[^>]*href="(https://rde\.buzz/[^"]+)"[^>]*>.*?<h4[^>]*>([^<]+)</h4>.*?<img[^>]*src="([^"]+)"""", RegexOption.DOT_MATCHES_ALL)
        
        val matches = cardPattern.findAll(html)
        
        for (match in matches) {
            val channelUrl = match.groupValues[1]
            val name = match.groupValues[2].trim()
            var posterUrl = match.groupValues[3]
            
            if (posterUrl.startsWith("//")) posterUrl = "https:$posterUrl"
            
            println("  📺 Canal encontrado: '$name' -> $channelUrl")
            
            channels.add(
                newLiveSearchResponse(name, channelUrl, TvType.Live) {
                    this.posterUrl = posterUrl
                }
            )
        }
        
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
        
        val html = app.get(siteUrl).text
        val results = mutableListOf<SearchResponse>()
        
        val cardPattern = Regex("""<div[^>]*class="[^"]*card[^"]*"[^>]*>.*?<a[^>]*href="(https://rde\.buzz/[^"]+)"[^>]*>.*?<h4[^>]*>([^<]+)</h4>""", RegexOption.DOT_MATCHES_ALL)
        
        for (match in cardPattern.findAll(html)) {
            val channelUrl = match.groupValues[1]
            val name = match.groupValues[2].trim()
            
            if (name.contains(query, ignoreCase = true)) {
                println("✅ Encontrado: '$name' -> $channelUrl")
                results.add(newLiveSearchResponse(name, channelUrl, TvType.Live))
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
