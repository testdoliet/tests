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
    
    // Número mágico NOVO (identificado no debug)
    private val MAGIC_NUMBER = 38956356

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

    // ================== LOAD LINKS COM NOMES ATUALIZADOS ==================
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
            // Headers completos (igual ao Python)
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
                "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
                "sec-ch-ua" to "\"Chromium\";v=\"127\", \"Not)A;Brand\";v=\"99\", \"Microsoft Edge Simulate\";v=\"127\", \"Lemur\";v=\"127\"",
                "sec-ch-ua-mobile" to "?1",
                "sec-ch-ua-platform" to "\"Android\"",
                "Upgrade-Insecure-Requests" to "1"
            )

            // PASSO 1: Buscar página do canal
            println("\n📥 [PASSO 1] Buscando página do canal: $data")
            val channelDocument = app.get(data, headers = headers).document
            println("✅ [PASSO 1] Página carregada com sucesso")
            
            // PASSO 2: Extrair iframe
            println("\n🖼️ [PASSO 2] Procurando iframe...")
            val iframeElement = channelDocument.selectFirst("iframe") ?: return false
            var iframeSrc = iframeElement.attr("src")
            println("🔗 [PASSO 2] iframe src encontrado: $iframeSrc")
            
            if (!iframeSrc.startsWith("http")) {
                iframeSrc = "$mainSite$iframeSrc"
            }
            
            // PASSO 3: Headers para iframe com Referer
            println("\n🔧 [PASSO 3] Configurando headers para iframe...")
            val iframeHeaders = headers.toMutableMap()
            iframeHeaders["Referer"] = data
            
            // PASSO 4: Buscar iframe
            println("\n📥 [PASSO 4] Buscando página do iframe: $iframeSrc")
            val iframeResponse = app.get(iframeSrc, headers = iframeHeaders)
            val iframeHtml = iframeResponse.text
            println("✅ [PASSO 4] Página do iframe carregada (${iframeHtml.length} caracteres)")
            
            // PASSO 5: Procurar o array (agora chamado SHi)
            println("\n🔍 [PASSO 5] Procurando array 'SHi' no HTML...")
            
            // Padrões para encontrar o array (agora SHi em vez de mCW)
            val arrayPatterns = listOf(
                Regex("""var SHi = (\[.*?\]);""", setOf(RegexOption.DOT_MATCHES_ALL)),
                Regex("""var SHi\s*=\s*(\[.*?\]);""", setOf(RegexOption.DOT_MATCHES_ALL)),
                Regex("""SHi\s*=\s*(\[.*?\])""", setOf(RegexOption.DOT_MATCHES_ALL)),
                Regex("""const SHi = (\[.*?\]);""", setOf(RegexOption.DOT_MATCHES_ALL)),
                Regex("""let SHi = (\[.*?\]);""", setOf(RegexOption.DOT_MATCHES_ALL))
            )
            
            var arrayJsonString: String? = null
            
            for (pattern in arrayPatterns) {
                val match = pattern.find(iframeHtml)
                if (match != null) {
                    arrayJsonString = match.groupValues[1]
                    println("✅ Array SHi encontrado!")
                    break
                }
            }
            
            if (arrayJsonString == null) {
                println("❌ [PASSO 5] Array SHi não encontrado!")
                return false
            }
            
            println("ℹ️ Tamanho do array: ~${arrayJsonString.length} caracteres")
            
            // PASSO 6: Parsear array
            println("\n🔨 [PASSO 6] Parseando array SHi para lista...")
            val arrayList = try {
                mapper.readTree(arrayJsonString).map { it.asText() }
            } catch (e: Exception) {
                println("⚠️ Falha no parse JSON, usando método alternativo")
                arrayJsonString.removeSurrounding("[", "]")
                    .split(",")
                    .map { it.trim().replace("\"", "").replace("'", "") }
                    .filter { it.isNotBlank() }
            }
            
            println("✅ [PASSO 6] Lista criada com ${arrayList.size} itens")
            
            // PASSO 7: Decodificar (usando o novo número mágico)
            println("\n🔓 [PASSO 7] Decodificando ${arrayList.size} itens...")
            val generatedCode = StringBuilder()
            var processedCount = 0
            
            arrayList.forEachIndexed { index, item ->
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
                            processedCount++
                        }
                    }
                    
                } catch (e: Exception) {
                    // Ignora erros
                }
            }
            
            println("✅ [PASSO 7] Decodificação concluída! $processedCount itens processados")
            
            // PASSO 8: Procurar link .m3u8
            println("\n🔎 [PASSO 8] Procurando link .m3u8 no código gerado...")
            val linkPattern = Regex("(https?://[^\"]+\\.m3u8[^\"]*)")
            val match = linkPattern.find(generatedCode.toString())
            
            if (match == null) {
                println("❌ [PASSO 8] Nenhum link .m3u8 encontrado!")
                return false
            }
            
            val videoUrl = match.groupValues[0]
            println("✅ [PASSO 8] Link encontrado: $videoUrl")
            
            // PASSO 9: Headers para o vídeo
            println("\n🔧 [PASSO 9] Configurando headers para o vídeo...")
            val videoHeaders = mapOf(
                "Referer" to "https://p2player.live/",
                "Origin" to "https://p2player.live/",
                "User-Agent" to headers["User-Agent"]!!
            )
            
            // PASSO 10: Retornar link
            println("\n🎬 [PASSO 10] Enviando link para o player...")
            callback.invoke(
                newExtractorLink(name, "$name [HLS]", videoUrl) {
                    this.referer = iframeSrc
                    this.type = ExtractorLinkType.M3U8
                    this.headers = videoHeaders
                }
            )
            
            println("\n🎉 [LOAD LINKS] SUCESSO! Link extraído e enviado")
            println("=".repeat(60))
            return true

        } catch (e: Exception) {
            println("\n💥 [LOAD LINKS] EXCEÇÃO FATAL: ${e.message}")
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
            url.startsWith("/") -> "$apiBaseUrl$url"
            else -> url
        }
    }
}
