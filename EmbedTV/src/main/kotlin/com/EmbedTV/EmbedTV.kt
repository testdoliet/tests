package com.EmbedTV

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class EmbedTvProvider : BasePlugin() {
    override fun load() {
        registerMainAPI(EmbedTv())
    }
}

class EmbedTv : MainAPI() {
    override var name = "EmbedTv"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = false
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Live)
    
    private val mainSite = "https://embedtv.best"
    private val baseUrl = "https://www3.embedtv.best"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        println("📥 [EmbedTv] Carregando página principal...")
        val doc = app.get(mainSite).document
        
        // Jogos de Hoje
        val jogosSection = doc.selectFirst(".session.futebol")
        val jogosList = mutableListOf<SearchResponse>()
        
        if (jogosSection != null) {
            println("✅ [EmbedTv] Seção de jogos encontrada")
            val jogosCards = jogosSection.select(".card")
            println("📊 [EmbedTv] ${jogosCards.size} jogos encontrados")
            
            for (card in jogosCards) {
                val channelId = card.attr("data-channel")
                if (channelId.isBlank()) continue
                
                val nameElement = card.selectFirst("h3")
                val channelName = nameElement?.text()?.trim()
                if (channelName.isNullOrBlank()) continue
                
                val imgElement = card.selectFirst("img")
                val imgSrc = imgElement?.attr("src")?.trim()
                val imgDataSrc = imgElement?.attr("data-src")?.trim()
                val imageUrl = if (!imgSrc.isNullOrBlank()) imgSrc else imgDataSrc
                
                val timeElement = card.selectFirst("span")
                val time = timeElement?.text()?.trim()
                
                val displayName = if (!time.isNullOrBlank()) "$channelName ($time)" else channelName
                
                if (channelId.isNotBlank() && imageUrl != null) {
                    jogosList.add(
                        newLiveSearchResponse(
                            displayName,
                            "$baseUrl/$channelId",
                            TvType.Live
                        ) {
                            this.posterUrl = fixUrl(imageUrl)
                        }
                    )
                }
            }
        } else {
            println("⚠️ [EmbedTv] Seção de jogos não encontrada")
        }
        
        // Categorias
        val categories = doc.select(".categorie")
        println("📊 [EmbedTv] ${categories.size} categorias encontradas")
        
        val allCategories = mutableListOf<HomePageList>()
        
        if (jogosList.isNotEmpty()) {
            allCategories.add(HomePageList("📺 Jogos de Hoje", jogosList))
        }
        
        for (category in categories) {
            val titleElement = category.selectFirst(".title")
            val categoryTitle = titleElement?.text()?.trim()
            if (categoryTitle.isNullOrBlank()) continue
            
            println("📁 [EmbedTv] Processando categoria: $categoryTitle")
            
            val channelList = mutableListOf<SearchResponse>()
            val cards = category.select(".card")
            
            for (card in cards) {
                val channelId = card.attr("data-channel")
                if (channelId.isBlank()) continue
                
                val nameElement = card.selectFirst("h3")
                val channelName = nameElement?.text()?.trim()
                if (channelName.isNullOrBlank()) continue
                
                val imgElement = card.selectFirst("img")
                val imgSrc = imgElement?.attr("src")?.trim()
                val imgDataSrc = imgElement?.attr("data-src")?.trim()
                val imageUrl = if (!imgSrc.isNullOrBlank()) imgSrc else imgDataSrc
                
                if (channelId.isNotBlank() && imageUrl != null) {
                    channelList.add(
                        newLiveSearchResponse(
                            channelName,
                            "$baseUrl/$channelId",
                            TvType.Live
                        ) {
                            this.posterUrl = fixUrl(imageUrl)
                        }
                    )
                }
            }
            
            if (channelList.isNotEmpty()) {
                println("✅ [EmbedTv] ${channelList.size} canais em $categoryTitle")
                allCategories.add(HomePageList(categoryTitle, channelList))
            }
        }
        
        if (allCategories.isEmpty()) {
            println("❌ [EmbedTv] Nenhum canal encontrado!")
            throw ErrorLoadingException("Nenhum canal encontrado.")
        }
        
        println("✅ [EmbedTv] Total de ${allCategories.size} categorias carregadas")
        return newHomePageResponse(allCategories)
    }

    override suspend fun load(url: String): LoadResponse {
        val channelId = url.substringAfterLast("/")
        println("📥 [EmbedTv] Carregando detalhes do canal: $channelId")
        
        var channelName = "Canal $channelId"
        var channelImage = "https://embedtv.best/assets/icon.png"
        
        try {
            val doc = app.get(mainSite).document
            val card = doc.select(".card[data-channel=\"$channelId\"]").firstOrNull()
            
            if (card != null) {
                println("✅ [EmbedTv] Card encontrado para $channelId")
                
                val nameElement = card.selectFirst("h3")
                val name = nameElement?.text()?.trim()
                if (!name.isNullOrBlank()) {
                    channelName = name
                }
                
                val imgElement = card.selectFirst("img")
                if (imgElement != null) {
                    val src = imgElement.attr("src").trim()
                    val dataSrc = imgElement.attr("data-src").trim()
                    val image = if (src.isNotBlank()) src else dataSrc
                    if (image.isNotBlank()) {
                        channelImage = image
                    }
                }
                
                val timeElement = card.selectFirst("span")
                val time = timeElement?.text()?.trim()
                if (!time.isNullOrBlank()) {
                    channelName = "$channelName ($time)"
                    println("⏰ [EmbedTv] Horário: $time")
                }
            } else {
                println("⚠️ [EmbedTv] Card não encontrado para $channelId")
            }
        } catch (e: Exception) {
            println("❌ [EmbedTv] Erro ao carregar detalhes: ${e.message}")
        }

        return newMovieLoadResponse(
            channelName,
            url,
            TvType.Live,
            url
        ) {
            this.posterUrl = fixUrl(channelImage)
            this.plot = "Assista $channelName ao vivo no EmbedTv"
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        println("🔍 [EmbedTv] Buscando por: $query")
        val doc = app.get(mainSite).document
        val allCards = doc.select(".card")
        val results = mutableListOf<SearchResponse>()
        
        println("📊 [EmbedTv] Total de ${allCards.size} cards para buscar")
        
        for (card in allCards) {
            val channelId = card.attr("data-channel")
            if (channelId.isBlank()) continue
            
            val nameElement = card.selectFirst("h3")
            val channelName = nameElement?.text()?.trim()
            if (channelName.isNullOrBlank()) continue
            
            if (!channelName.contains(query, ignoreCase = true)) continue
            
            val imgElement = card.selectFirst("img")
            val imgSrc = imgElement?.attr("src")?.trim()
            val imgDataSrc = imgElement?.attr("data-src")?.trim()
            val imageUrl = if (!imgSrc.isNullOrBlank()) imgSrc else imgDataSrc
            
            val timeElement = card.selectFirst("span")
            val time = timeElement?.text()?.trim()
            
            val displayName = if (!time.isNullOrBlank()) "$channelName ($time)" else channelName
            
            if (imageUrl != null) {
                results.add(
                    newLiveSearchResponse(
                        displayName,
                        "$baseUrl/$channelId",
                        TvType.Live
                    ) {
                        this.posterUrl = fixUrl(imageUrl)
                    }
                )
            }
        }
        
        println("✅ [EmbedTv] ${results.size} resultados encontrados")
        return results
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val channelUrl = data.ifEmpty { return false }
        println("\n🎬 [EmbedTv] Carregando links para: $channelUrl")
        
        try {
            val doc = app.get(channelUrl).document
            val html = doc.html()
            
            // Procura data.channel.stream
            println("🔍 [EmbedTv] Procurando data.channel.stream...")
            val dataPattern = Regex("""data\.channel\.stream\s*=\s*"([^"]+\.m3u8[^"]*)""")
            val dataMatch = dataPattern.find(html)
            
            var finalUrl = dataMatch?.groupValues?.get(1)
            
            if (finalUrl != null) {
                println("✅ [EmbedTv] Link encontrado em data.channel.stream")
            } else {
                println("⚠️ [EmbedTv] data.channel.stream não encontrado, procurando link direto...")
                val directPattern = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")
                finalUrl = directPattern.find(html)?.value
                if (finalUrl != null) {
                    println("✅ [EmbedTv] Link direto encontrado")
                }
            }
            
            if (finalUrl == null) {
                println("❌ [EmbedTv] Nenhum link encontrado!")
                return false
            }
            
            println("📺 [EmbedTv] URL encontrada: $finalUrl")
            
            val headers = mapOf(
                "referer" to baseUrl,
                "origin" to baseUrl,
                "user-agent" to USER_AGENT,
                "accept" to "*/*"
            )
            
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "EmbedTv Live",
                    url = finalUrl
                ) {
                    this.referer = baseUrl
                    this.type = ExtractorLinkType.M3U8
                    this.headers = headers
                    this.quality = Qualities.Unknown.value
                }
            )
            
            println("✅ [EmbedTv] Link enviado com sucesso!")
            return true
            
        } catch (e: Exception) {
            println("❌ [EmbedTv] Erro: ${e.message}")
            return false
        }
    }

    private fun fixUrl(url: String): String {
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("http") -> url
            url.startsWith("/") -> "$mainSite$url"
            else -> url
        }
    }
}
