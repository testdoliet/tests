package com.reidoscanais

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

@CloudstreamPlugin
class ReiDosCanaisProvider : BasePlugin() {
    override fun load() {
        registerMainAPI(ReiDosCanais())
    }
}

// ================== DATA CLASSES ==================

data class ApiChannel(
    @JsonProperty("id") val id: String,
    @JsonProperty("name") val name: String,
    @JsonProperty("logo_url") val logoUrl: String,
    @JsonProperty("embed_url") val embedUrl: String,
    @JsonProperty("category") val category: String
)

data class ApiResponse<T>(
    @JsonProperty("success") val success: Boolean,
    @JsonProperty("data") val data: T
)

class ReiDosCanais : MainAPI() {
    override var name = "Rei dos Canais"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = false
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Live)
    
    // URLs
    private val apiBaseUrl = "https://api.reidoscanais.io"
    private val channelsEndpoint = "$apiBaseUrl/channels"
    private val siteBaseUrl = "https://rdcanais.top"
    
    private val mapper = jacksonObjectMapper()

    // ================== PÁGINA PRINCIPAL ==================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val homePageList = mutableListOf<HomePageList>()

        try {
            // Buscar todos os canais
            val response = app.get(channelsEndpoint, timeout = 30).text
            val json = mapper.readTree(response)
            
            if (json.has("success") && json["success"].asBoolean()) {
                val channelsData = json["data"]
                if (channelsData.isArray) {
                    // Agrupar canais por categoria
                    val channelsByCategory = channelsData.mapNotNull { node ->
                        parseChannel(node)
                    }.groupBy { it.category }
                    
                    // Para cada categoria, criar uma HomePageList
                    channelsByCategory.forEach { (categoryName, channels) ->
                        val channelList = channels.map { channel ->
                            newLiveSearchResponse(
                                channel.name,
                                channel.embedUrl,  // URL direta do player
                                TvType.Live
                            ) {
                                this.posterUrl = fixUrl(channel.logoUrl)
                            }
                        }
                        
                        if (channelList.isNotEmpty()) {
                            homePageList.add(HomePageList(categoryName, channelList, isHorizontalImages = true))
                        }
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
    
    private fun parseChannel(node: com.fasterxml.jackson.databind.JsonNode): ApiChannel? {
        return try {
            ApiChannel(
                id = node["id"]?.asText() ?: return null,
                name = node["name"]?.asText() ?: return null,
                logoUrl = node["logo_url"]?.asText() ?: "",
                embedUrl = node["embed_url"]?.asText() ?: return null,
                category = node["category"]?.asText() ?: "Sem Categoria"
            )
        } catch (e: Exception) {
            null
        }
    }

    // ================== PÁGINA DE DETALHES ==================
    override suspend fun load(url: String): LoadResponse {
        // A URL já é a página do player (ex: https://rdcanais.top/adultswim)
        
        // Extrair o ID da URL
        val channelId = url.substringAfterLast("/")
        
        // Buscar informações do canal na API
        val response = app.get(channelsEndpoint, timeout = 30).text
        val json = mapper.readTree(response)
        
        var channelName = "Canal"
        var channelPoster = ""
        
        if (json.has("success") && json["success"].asBoolean()) {
            val channelsData = json["data"]
            if (channelsData.isArray) {
                for (node in channelsData) {
                    val id = node["id"]?.asText()
                    if (id == channelId) {
                        channelName = node["name"]?.asText() ?: channelName
                        channelPoster = node["logo_url"]?.asText() ?: ""
                        break
                    }
                }
            }
        }
        
        // Retornar resposta simples com as informações que temos
        return newMovieLoadResponse(
            channelName,
            url,
            TvType.Live,
            url  // A mesma URL será usada para carregar os links
        ) {
            this.posterUrl = fixUrl(channelPoster)
            this.plot = "Assista ao vivo"  // Sinopse simples
        }
    }

    // ================== BUSCA ==================
    override suspend fun search(query: String): List<SearchResponse>? {
        val results = mutableListOf<SearchResponse>()
        
        try {
            val response = app.get(channelsEndpoint, timeout = 30).text
            val json = mapper.readTree(response)
            
            if (json.has("success") && json["success"].asBoolean()) {
                val channelsData = json["data"]
                if (channelsData.isArray) {
                    val matchingChannels = channelsData.mapNotNull { node ->
                        parseChannel(node)
                    }.filter { it.name.contains(query, ignoreCase = true) }
                    .map { channel ->
                        newLiveSearchResponse(
                            channel.name,
                            channel.embedUrl,
                            TvType.Live
                        ) {
                            this.posterUrl = fixUrl(channel.logoUrl)
                        }
                    }
                    results.addAll(matchingChannels)
                }
            }
            
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
        
        return results
    }

    // ================== EXTRAIR LINK DO PLAYER ==================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data é a URL do player (ex: https://rdcanais.top/adultswim)
        
        try {
            // Acessar a página do player
            val response = app.get(data, allowRedirects = true)
            val doc = response.document
            val html = doc.html()
            
            // Procurar por iframe
            val iframe = doc.selectFirst("iframe")
            if (iframe != null) {
                val iframeSrc = iframe.attr("src")
                
                // Criar link direto para o iframe
                // O Cloudstream vai abrir este link em um WebView
                val link = newExtractorLink(
                    source = "Rei dos Canais",
                    name = "Assistir",
                    url = iframeSrc,
                    type = ExtractorLinkType.WEBVIEW  // Usar WEBVIEW em vez de IFRAME
                ) {
                    this.referer = data
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to data
                    )
                }
                callback.invoke(link)
                return true
            }
            
            // Se não encontrar iframe, tentar abrir a própria página
            val link = newExtractorLink(
                source = "Rei dos Canais",
                name = "Assistir",
                url = data,
                type = ExtractorLinkType.WEBVIEW
            ) {
                this.referer = data
                this.quality = Qualities.Unknown.value
            }
            callback.invoke(link)
            return true
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return false
    }
    
    // ================== MÉTODO DE FIX ==================
    private fun fixUrl(url: String): String {
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("http") -> url
            url.startsWith("/") -> "$apiBaseUrl$url"
            else -> url
        }
    }
}
