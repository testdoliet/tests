package com.embedtv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

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
    
    // URLs
    private val mainSite = "https://embedtv.best"
    private val baseUrl = "https://www3.embedtv.best"

    // ================== PÁGINA PRINCIPAL ==================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val homePageList = mutableListOf<HomePageList>()

        try {
            val document = app.get(mainSite).document
            
            // Primeira seção: Jogos de Hoje (destaques)
            val jogosSection = document.selectFirst(".session.futebol")
            if (jogosSection != null) {
                val jogosList = mutableListOf<SearchResponse>()
                val jogosCards = jogosSection.select(".card")
                
                jogosCards.forEach { card ->
                    val channelId = card.attr("data-channel")
                    val channelNameElement = card.selectFirst("h3")
                    val channelName = channelNameElement?.text()
                    
                    if (!channelId.isNullOrBlank() && !channelName.isNullOrBlank()) {
                        val imgElement = card.selectFirst("img")
                        val imageUrl = imgElement?.attr("src") ?: imgElement?.attr("data-src") ?: ""
                        val timeElement = card.selectFirst("span")
                        val time = timeElement?.text() ?: ""
                        
                        val displayName = if (time.isNotBlank()) "$channelName ($time)" else channelName
                        
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
                
                if (jogosList.isNotEmpty()) {
                    homePageList.add(HomePageList("📺 Jogos de Hoje", jogosList, isHorizontalImages = true))
                }
            }
            
            // Todas as categorias
            val categories = document.select(".categorie")
            
            categories.forEach { category ->
                val titleElement = category.selectFirst(".title")
                val categoryTitle = titleElement?.text()
                
                if (!categoryTitle.isNullOrBlank()) {
                    val channelCards = category.select(".card")
                    
                    val channelList = channelCards.mapNotNull { card ->
                        val channelId = card.attr("data-channel")
                        val channelNameElement = card.selectFirst("h3")
                        val channelName = channelNameElement?.text()
                        
                        if (!channelId.isNullOrBlank() && !channelName.isNullOrBlank()) {
                            val imgElement = card.selectFirst("img")
                            val imageUrl = imgElement?.attr("src") ?: imgElement?.attr("data-src") ?: ""
                            
                            newLiveSearchResponse(
                                channelName,
                                "$baseUrl/$channelId",
                                TvType.Live
                            ) {
                                this.posterUrl = fixUrl(imageUrl)
                            }
                        } else null
                    }
                    
                    if (channelList.isNotEmpty()) {
                        homePageList.add(HomePageList(categoryTitle, channelList, isHorizontalImages = true))
                    }
                }
            }
            
        } catch (e: Exception) {
            e.printStackTrace()
            throw ErrorLoadingException("Falha ao carregar canais: ${e.message}")
        }

        if (homePageList.isEmpty()) {
            throw ErrorLoadingException("Nenhum canal encontrado.")
        }
        
        return newHomePageResponse(homePageList)
    }

    // ================== PÁGINA DE DETALHES ==================
    override suspend fun load(url: String): LoadResponse {
        val channelId = url.substringAfterLast("/")
        
        // Tenta buscar informações do canal na página principal
        var channelName = "Canal $channelId"
        var channelImage = "https://embedtv.best/assets/icon.png"
        
        try {
            val mainDoc = app.get(mainSite).document
            val allCards = mainDoc.select(".card[data-channel=\"$channelId\"]")
            
            if (allCards.isNotEmpty()) {
                val card = allCards.first()
                val nameElement = card.selectFirst("h3")
                val name = nameElement?.text()
                if (!name.isNullOrBlank()) {
                    channelName = name
                }
                
                val imgElement = card.selectFirst("img")
                val image = imgElement?.attr("src") ?: imgElement?.attr("data-src")
                if (!image.isNullOrBlank()) {
                    channelImage = image
                }
                
                // Se for jogo, adiciona horário
                val timeElement = card.selectFirst("span")
                val time = timeElement?.text()
                if (!time.isNullOrBlank()) {
                    channelName = "$channelName ($time)"
                }
            }
        } catch (e: Exception) {
            // Ignora erro, usa valores padrão
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

    // ================== BUSCA ==================
    override suspend fun search(query: String): List<SearchResponse>? {
        val results = mutableListOf<SearchResponse>()
        
        try {
            val document = app.get(mainSite).document
            val allCards = document.select(".card")
            
            val matchingChannels = allCards.mapNotNull { card ->
                val channelId = card.attr("data-channel")
                val channelNameElement = card.selectFirst("h3")
                val channelName = channelNameElement?.text()
                
                if (!channelId.isNullOrBlank() && !channelName.isNullOrBlank() && 
                    channelName.contains(query, ignoreCase = true)) {
                    
                    val imgElement = card.selectFirst("img")
                    val imageUrl = imgElement?.attr("src") ?: imgElement?.attr("data-src") ?: ""
                    val timeElement = card.selectFirst("span")
                    val time = timeElement?.text() ?: ""
                    
                    val displayName = if (time.isNotBlank()) "$channelName ($time)" else channelName
                    
                    newLiveSearchResponse(
                        displayName,
                        "$baseUrl/$channelId",
                        TvType.Live
                    ) {
                        this.posterUrl = fixUrl(imageUrl)
                    }
                } else null
            }
            
            results.addAll(matchingChannels)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }

        return results
    }

    // ================== LOAD LINKS ==================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("\n" + "=".repeat(60))
        println("🎬 [LOAD LINKS] INICIANDO EXTRAÇÃO PARA: $data")
        println("=".repeat(60))

        try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
                "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
                "Upgrade-Insecure-Requests" to "1"
            )

            println("\n📥 [PASSO 1] Buscando página do canal: $data")
            val response = app.get(data, headers = headers)
            val html = response.text
            println("✅ [PASSO 1] Página carregada (${html.length} caracteres)")
            
            // PASSO 2: Procurar link direto no HTML
            println("\n🔍 [PASSO 2] Procurando link .m3u8 no HTML...")
            
            // Padrão para encontrar o link no objeto data.channel
            val dataStreamPattern = Regex("""data\.channel\.stream\s*=\s*"([^"]+\.m3u8[^"]*)""")
            val dataMatch = dataStreamPattern.find(html)
            
            if (dataMatch != null) {
                val videoUrl = dataMatch.groupValues[1]
                println("✅ [PASSO 2] Link encontrado em data.channel.stream!")
                println("📺 Link: $videoUrl")
                
                val videoHeaders = mapOf(
                    "Referer" to baseUrl,
                    "Origin" to baseUrl,
                    "User-Agent" to headers["User-Agent"]!!
                )
                
                callback.invoke(
                    newExtractorLink("EmbedTv", "EmbedTv [HLS]", videoUrl) {
                        this.referer = baseUrl
                        this.type = ExtractorLinkType.M3U8
                        this.headers = videoHeaders
                        this.quality = Qualities.Unknown.value
                    }
                )
                
                println("\n🎉 [LOAD LINKS] SUCESSO!")
                println("=".repeat(60))
                return true
            }
            
            // PASSO 3: Se não encontrou, procura link direto .m3u8
            println("⚠️ [PASSO 2] Link não encontrado em data.channel, procurando direto...")
            
            val directPattern = Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""")
            val directMatch = directPattern.find(html)
            
            if (directMatch != null) {
                val videoUrl = directMatch.groupValues[1]
                println("✅ [PASSO 3] Link .m3u8 encontrado diretamente!")
                println("📺 Link: $videoUrl")
                
                val videoHeaders = mapOf(
                    "Referer" to baseUrl,
                    "Origin" to baseUrl,
                    "User-Agent" to headers["User-Agent"]!!
                )
                
                callback.invoke(
                    newExtractorLink("EmbedTv", "EmbedTv [HLS]", videoUrl) {
                        this.referer = baseUrl
                        this.type = ExtractorLinkType.M3U8
                        this.headers = videoHeaders
                        this.quality = Qualities.Unknown.value
                    }
                )
                
                println("\n🎉 [LOAD LINKS] SUCESSO!")
                println("=".repeat(60))
                return true
            }
            
            println("❌ [LOAD LINKS] Nenhum link encontrado!")
            println("=".repeat(60))
            return false

        } catch (e: Exception) {
            println("\n💥 [LOAD LINKS] EXCEÇÃO: ${e.message}")
            e.printStackTrace()
            println("=".repeat(60))
            return false
        }
    }

    private fun fixUrl(url: String): String {
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("http") -> url
            url.startsWith("/") -> "$mainSite$url"
            url.startsWith("https://d1muf25xaso8hp.cloudfront.net/") -> url
            else -> "$mainSite/$url"
        }
    }
}
