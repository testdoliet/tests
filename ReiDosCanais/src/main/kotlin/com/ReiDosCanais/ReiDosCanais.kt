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
    
    // Constante mágica para decodificação (descoberta na análise)
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

    // ================== LOAD LINKS (VERSÃO FINAL OTIMIZADA) ==================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Headers essenciais baseados na análise
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
            "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
            "sec-ch-ua" to "\"Chromium\";v=\"127\", \"Not)A;Brand\";v=\"99\", \"Microsoft Edge Simulate\";v=\"127\", \"Lemur\";v=\"127\"",
            "sec-ch-ua-mobile" to "?1",
            "sec-ch-ua-platform" to "\"Android\"",
            "Upgrade-Insecure-Requests" to "1"
        )

        try {
            // ===== PASSO 1: Buscar página do canal =====
            val channelDocument = app.get(data, headers = headers).document
            
            // ===== PASSO 2: Extrair iframe =====
            val iframeElement = channelDocument.selectFirst("iframe") ?: return false
            val iframeSrc = iframeElement.attr("src")
            
            // Construir URL completa do iframe
            val iframeUrl = if (iframeSrc.startsWith("http")) {
                iframeSrc
            } else {
                "${mainSite}${iframeSrc}"
            }
            
            // ===== PASSO 3: Headers ESPECÍFICOS para o iframe (com Referer) =====
            val iframeHeaders = headers.toMutableMap()
            iframeHeaders["Referer"] = data  // Referer é a página do canal!
            
            // ===== PASSO 4: Buscar página do iframe =====
            val iframeResponse = app.get(iframeUrl, headers = iframeHeaders)
            val iframeHtml = iframeResponse.text
            
            // ===== PASSO 5: Encontrar array mCW =====
            val mCWPattern = Regex("""var mCW = (\[.*?\]);""", setOf(RegexOption.DOT_MATCHES_ALL))
            val mCWMatch = mCWPattern.find(iframeHtml) ?: return false
            
            val mCWJsonString = mCWMatch.groupValues[1]
            
            // ===== PASSO 6: Parsear array mCW (pode ter vírgula no final, então tratamos) =====
            val mCWList = try {
                // Tentativa 1: Parse como JSON
                mapper.readTree(mCWJsonString).map { it.asText() }
            } catch (e: Exception) {
                // Tentativa 2: Parse manual (remove colchetes e aspas)
                mCWJsonString.removeSurrounding("[", "]")
                    .split(",")
                    .map { it.trim().replace("\"", "").replace("'", "") }
                    .filter { it.isNotBlank() }
            }
            
            // ===== PASSO 7: Decodificar array mCW =====
            val generatedCode = buildString {
                mCWList.forEach { base64Item ->
                    try {
                        // Ajustar padding do Base64 se necessário
                        var item = base64Item
                        val missingPadding = item.length % 4
                        if (missingPadding > 0) {
                            item += "=".repeat(4 - missingPadding)
                        }
                        
                        val decodedBytes = Base64.getDecoder().decode(item)
                        val decodedString = String(decodedBytes, Charsets.UTF_8)
                        
                        // Extrair apenas números e decodificar
                        val numberPart = decodedString.replace(Regex("\\D"), "")
                        if (numberPart.isNotEmpty()) {
                            val charCode = numberPart.toInt() - MAGIC_NUMBER
                            if (charCode in 32..126) { // Apenas caracteres imprimíveis
                                append(charCode.toChar())
                            }
                        }
                    } catch (e: Exception) {
                        // Ignora erros em itens individuais
                    }
                }
            }
            
            // ===== PASSO 8: Procurar link .m3u8 no código gerado =====
            // Padrão específico que descobrimos na análise
            val m3u8Pattern = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""")
            val match = m3u8Pattern.find(generatedCode) ?: return false
            
            val videoUrl = match.groupValues[0]
            
            // ===== PASSO 9: Extrair ID do canal para confirmar o padrão =====
            val channelId = data.substringAfterLast("/")
            
            // Headers para acessar o link do vídeo
            val videoHeaders = mapOf(
                "Referer" to "https://p2player.live/",
                "Origin" to "https://p2player.live",
                "User-Agent" to headers["User-Agent"]!!,
                "sec-ch-ua" to headers["sec-ch-ua"]!!,
                "sec-ch-ua-mobile" to headers["sec-ch-ua-mobile"]!!,
                "sec-ch-ua-platform" to headers["sec-ch-ua-platform"]!!
            )
            
            // Criar o link para o CloudStream reproduzir
            callback.invoke(
                ExtractorLink(
                    source = name,
                    name = "$name [HLS]",
                    url = videoUrl,
                    referer = "https://p2player.live/",
                    headers = videoHeaders,
                    quality = Qualifier.Unknown.value,
                    type = ExtractorLink.Type.M3U8
                )
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
