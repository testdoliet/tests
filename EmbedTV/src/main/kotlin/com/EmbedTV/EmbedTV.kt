package com.embedtv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.util.Base64

@CloudstreamPlugin
class EmbedTvProvider : BasePlugin() {
    override fun load() {
        registerMainAPI(EmbedTv())
    }
}

// ================== DATA CLASSES ==================
data class EmbedChannel(
    @JsonProperty("id") val id: String,
    @JsonProperty("name") val name: String,
    @JsonProperty("image") val image: String,
    @JsonProperty("stream") val stream: String,
    @JsonProperty("category") val category: String? = "Todos"
)

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
    private val apiBase = "$baseUrl/api"
    
    private val mapper = jacksonObjectMapper()

    // ================== PÁGINA PRINCIPAL ==================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val homePageList = mutableListOf<HomePageList>()

        try {
            val document = app.get(mainSite).document
            val categories = document.select(".categorie")
            
            categories.forEach { category ->
                val categoryName = category.selectFirst(".categorie-title")?.text() ?: "Canais"
                val channelCards = category.select(".card")
                
                val channelList = channelCards.mapNotNull { card ->
                    val channelId = card.attr("data-channel")
                    val channelName = card.attr("data-name")
                    val imageUrl = card.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                    
                    if (channelId.isNotBlank() && channelName.isNotBlank()) {
                        newLiveSearchResponse(
                            channelName,
                            "$baseUrl/$channelId",
                            TvType.Live
                        ) {
                            this.posterUrl = imageUrl
                        }
                    } else null
                }
                
                if (channelList.isNotEmpty()) {
                    homePageList.add(HomePageList(categoryName, channelList, isHorizontalImages = true))
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
        
        val document = app.get(url).document
        
        // Tenta extrair informações da página
        val channelName = document.selectFirst("title")?.text()
            ?.replace("EmbedTv -", "")
            ?.replace("Assistir", "")
            ?.trim() ?: "Canal $channelId"
        
        // Tenta extrair a imagem do canal
        val channelImage = document.selectFirst("meta[property='og:image']")?.attr("content")
            ?: "https://embedtv.best/assets/icon.png"

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
                val channelName = card.attr("data-name")
                val channelId = card.attr("data-channel")
                val imageUrl = card.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                
                if (channelName.contains(query, ignoreCase = true) && channelId.isNotBlank()) {
                    newLiveSearchResponse(
                        channelName,
                        "$baseUrl/$channelId",
                        TvType.Live
                    ) {
                        this.posterUrl = imageUrl
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

    // ================== LOAD LINKS COM DETECÇÃO AUTOMÁTICA ==================
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
            // Headers padrão (igual ao Python)
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
                "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
                "Upgrade-Insecure-Requests" to "1"
            )

            // PASSO 1: Buscar página do canal
            println("\n📥 [PASSO 1] Buscando página do canal: $data")
            val response = app.get(data, headers = headers)
            val html = response.text
            println("✅ [PASSO 1] Página carregada (${html.length} caracteres)")
            
            // PASSO 2: Procurar link direto no HTML (mais simples)
            println("\n🔍 [PASSO 2] Procurando link .m3u8 no HTML...")
            
            // Padrão para encontrar o link no objeto data.channel
            val dataStreamPattern = Regex("""data\.channel\.stream\s*=\s*"([^"]+\.m3u8[^"]*)""")
            val dataMatch = dataStreamPattern.find(html)
            
            if (dataMatch != null) {
                val videoUrl = dataMatch.groupValues[1]
                println("✅ [PASSO 2] Link encontrado diretamente em data.channel.stream!")
                println("📺 Link: $videoUrl")
                
                // Headers para o vídeo
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
            
            // PASSO 4: Se não encontrou, procura no player Clappr
            println("⚠️ [PASSO 3] Link direto não encontrado, procurando no Clappr...")
            
            val clapprPattern = Regex("""new\s+Clappr\.Player\s*\(\s*({[^}]+})""", setOf(RegexOption.DOT_MATCHES_ALL))
            val clapprMatch = clapprPattern.find(html)
            
            if (clapprMatch != null) {
                val configStr = clapprMatch.groupValues[1]
                val sourcePattern = Regex("""source:\s*"([^"]+)"""")
                val sourceMatch = sourcePattern.find(configStr)
                
                if (sourceMatch != null) {
                    val videoUrl = sourceMatch.groupValues[1]
                    println("✅ [PASSO 4] Link encontrado na configuração do Clappr!")
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
            }
            
            // Se chegou aqui, não encontrou nada
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

    // ================== MÉTODO DE FIX ==================
    private fun fixUrl(url: String): String {
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("http") -> url
            url.startsWith("/") -> "$mainSite$url"
            else -> "$mainSite/$url"
        }
    }
}
