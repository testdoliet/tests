package com.reidoscanais

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.util.Base64

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
    private val mainSite = "https://rdcanais.top"
    
    private val mapper = jacksonObjectMapper()
    
    // Constante mágica para decodificação
    private val MAGIC_NUMBER = 45341212

    // ================== PÁGINA PRINCIPAL ==================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val homePageList = mutableListOf<HomePageList>()

        try {
            val response = app.get(channelsEndpoint, timeout = 30).text
            val json = mapper.readTree(response)
            
            if (json.has("success") && json["success"].asBoolean()) {
                val channelsData = json["data"]
                if (channelsData.isArray) {
                    val channelsByCategory = channelsData.mapNotNull { node ->
                        parseChannel(node)
                    }.groupBy { it.category }
                    
                    channelsByCategory.forEach { (categoryName, channels) ->
                        val channelList = channels.map { channel ->
                            newLiveSearchResponse(
                                channel.name,
                                channel.embedUrl,
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
        val channelId = url.substringAfterLast("/")
        
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

        return newMovieLoadResponse(
            channelName,
            url,
            TvType.Live,
            url
        ) {
            this.posterUrl = fixUrl(channelPoster)
            this.plot = "Assista ao vivo"
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

    // ================== LOAD LINKS (USANDO newExtractorLink IGUAL AO EXEMPLO) ==================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Headers básicos
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept-Language" to "pt-BR,pt;q=0.9"
        )

        try {
            // PASSO 1: Buscar página do canal
            val channelDocument = app.get(data, headers = headers).document
            
            // PASSO 2: Extrair iframe
            val iframeElement = channelDocument.selectFirst("iframe") ?: return false
            var iframeSrc = iframeElement.attr("src")
            
            // Construir URL completa do iframe
            if (!iframeSrc.startsWith("http")) {
                iframeSrc = "$mainSite$iframeSrc"
            }
            
            // PASSO 3: Headers para iframe com Referer
            val iframeHeaders = headers.toMutableMap()
            iframeHeaders["Referer"] = data
            
            // PASSO 4: Buscar iframe
            val iframeResponse = app.get(iframeSrc, headers = iframeHeaders)
            val iframeHtml = iframeResponse.text
            
            // PASSO 5: Encontrar e processar mCW
            val mCWStart = iframeHtml.indexOf("var mCW = [")
            if (mCWStart == -1) return false
            
            val mCWEnd = iframeHtml.indexOf("];", mCWStart) + 1
            if (mCWEnd == 0) return false
            
            val mCWJson = iframeHtml.substring(mCWStart + 10, mCWEnd)
            
            // PASSO 6: Parsear array
            val mCWList = mCWJson.removeSurrounding("[", "]")
                .split(",")
                .map { it.trim().replace("\"", "").replace("'", "") }
                .filter { it.isNotBlank() }
            
            // PASSO 7: Decodificar
            val generatedCode = StringBuilder()
            mCWList.forEach { item ->
                try {
                    var base64Item = item
                    val missingPadding = base64Item.length % 4
                    if (missingPadding > 0) {
                        base64Item += "=".repeat(4 - missingPadding)
                    }
                    
                    val decoded = Base64.getDecoder().decode(base64Item)
                    val decodedStr = String(decoded, Charsets.UTF_8)
                    
                    val numbers = decodedStr.replace(Regex("[^0-9]"), "")
                    if (numbers.isNotEmpty()) {
                        val charCode = numbers.toInt() - MAGIC_NUMBER
                        if (charCode in 32..126) {
                            generatedCode.append(charCode.toChar())
                        }
                    }
                } catch (e: Exception) {
                    // Ignora erros
                }
            }
            
            // PASSO 8: Procurar link .m3u8
            val linkPattern = Regex("(https?://[^\"]+\\.m3u8[^\"]*)")
            val match = linkPattern.find(generatedCode.toString()) ?: return false
            
            val videoUrl = match.groupValues[0]
            
            // PASSO 9: Headers para o vídeo
            val videoHeaders = mapOf(
                "Referer" to "https://p2player.live/",
                "Origin" to "https://p2player.live/",
                "User-Agent" to USER_AGENT
            )
            
            // USANDO newExtractorLink IGUAL AO SEU EXEMPLO QUE FUNCIONA!
            callback.invoke(
                newExtractorLink(name, "$name [HLS]", videoUrl) {
                    this.referer = iframeSrc
                    this.type = ExtractorLinkType.M3U8
                    this.headers = videoHeaders
                }
            )
            
            return true

        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
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
