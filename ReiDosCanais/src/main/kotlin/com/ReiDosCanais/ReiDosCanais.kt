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
            // Headers completos (igual ao Python)
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
                "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
                "Upgrade-Insecure-Requests" to "1"
            )

            // Cookies necessários (do curl)
            val cookies = mapOf(
                "HstCfa4965742" to "1773157398878",
                "HstCmu4965742" to "1773157398878",
                "HstCnv4965742" to "1",
                "c_ref_4965742" to "https%3A%2F%2Freidoscanais.ooo%2F",
                "__dtsu" to "51A01773157400480B826F24D01EF775",
                "_pubcid" to "0ff87a18-6aee-4b4b-ba6e-7e74b924fe66",
                "_cc_id" to "9514611886549b0cd2b229f8630adc83",
                "panoramaId" to "48ae5276e40ef095a17b5a002aa0185ca02c2b50123f19905f96f252df3bd6f4",
                "panoramaIdType" to "panoDevice",
                "HstCns4965742" to "2",
                "panoramaId_expiry" to "1773765064352",
                "HstCla4965742" to "1773160470174",
                "HstPn4965742" to "6",
                "HstPt4965742" to "6"
            )

            // PASSO 1: Buscar página do canal com cookies
            println("\n📥 [PASSO 1] Buscando página do canal: $data")
            
            // Configurar cookies na sessão
            val cookieClient = app.getClient()
            cookies.forEach { (key, value) ->
                cookieClient.cookieManager.setCookie(data, key, value)
            }
            
            val channelDocument = app.get(data, headers = headers).document
            println("✅ [PASSO 1] Página carregada com sucesso")
            
            // PASSO 2: Extrair iframe
            println("\n🖼️ [PASSO 2] Procurando iframe...")
            val iframeElement = channelDocument.selectFirst("iframe") ?: run {
                println("❌ [PASSO 2] Nenhum iframe encontrado!")
                return false
            }
            var iframeSrc = iframeElement.attr("src")
            println("🔗 [PASSO 2] iframe src encontrado: $iframeSrc")
            
            if (!iframeSrc.startsWith("http")) {
                iframeSrc = "https:$iframeSrc".replace("//", "/").replace(":/", "://")
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
            
            // PASSO 5: Extrair array e número mágico
            println("\n🔍 [PASSO 5] Extraindo array e número mágico...")
            
            val extractionResult = extractJavaScriptData(iframeHtml)
            if (extractionResult == null) {
                println("❌ [PASSO 5] Não foi possível extrair os dados!")
                return false
            }
            
            val (arrayItems, magicNumber) = extractionResult
            println("✅ [PASSO 5] Dados extraídos com sucesso!")
            println("📊 Array: ${arrayItems.size} itens")
            println("🔢 Número mágico: $magicNumber")
            
            // PASSO 6: Decodificar
            println("\n🔓 [PASSO 6] Decodificando ${arrayItems.size} itens...")
            val generatedCode = StringBuilder()
            var processedCount = 0
            
            arrayItems.forEachIndexed { index, item ->
                try {
                    // Limpa o item (remove aspas se houver)
                    var cleanItem = item.trim().replace("^\"|\"$".toRegex(), "").replace("^'|'$".toRegex(), "")
                    
                    // Corrige padding do Base64
                    val missingPadding = cleanItem.length % 4
                    if (missingPadding > 0) {
                        cleanItem += "=".repeat(4 - missingPadding)
                    }
                    
                    val decoded = Base64.getDecoder().decode(cleanItem)
                    val decodedStr = String(decoded, Charsets.UTF_8)
                    
                    // Extrai apenas os números da string decodificada
                    val numbers = decodedStr.replace(Regex("[^0-9]"), "")
                    if (numbers.isNotEmpty()) {
                        val charCode = numbers.toInt()
                        val finalChar = charCode - magicNumber
                        
                        // Caracteres imprimíveis
                        if (finalChar in 32..126) {
                            generatedCode.append(finalChar.toChar())
                            processedCount++
                        }
                    }
                    
                } catch (e: Exception) {
                    // Ignora erros
                }
            }
            
            println("✅ [PASSO 6] Decodificação concluída! $processedCount caracteres gerados")
            
            if (generatedCode.isEmpty()) {
                println("❌ Nenhum caractere foi decodificado!")
                return false
            }
            
            // PASSO 7: Procurar link .m3u8
            println("\n🔎 [PASSO 7] Procurando link .m3u8...")
            val linkPattern = Regex("(https?://[^\"'\\s]+\\.m3u8[^\"'\\s]*)")
            val match = linkPattern.find(generatedCode.toString())
            
            if (match == null) {
                println("❌ [PASSO 7] Nenhum link .m3u8 encontrado!")
                return false
            }
            
            val videoUrl = match.groupValues[0]
            println("✅ [PASSO 7] Link encontrado: $videoUrl")
            
            // PASSO 8: Headers para o vídeo
            println("\n🔧 [PASSO 8] Configurando headers...")
            val videoHeaders = mapOf(
                "Referer" to "https://p2player.live/",
                "Origin" to "https://p2player.live/",
                "User-Agent" to headers["User-Agent"]!!
            )
            
            // PASSO 9: Retornar link
            println("\n🎬 [PASSO 9] Enviando link para o player...")
            callback.invoke(
                newExtractorLink("Rei dos Canais", "Rei dos Canais [HLS]", videoUrl) {
                    this.referer = iframeSrc
                    this.type = ExtractorLinkType.M3U8
                    this.headers = videoHeaders
                    this.quality = Qualities.Unknown.value
                }
            )
            
            println("\n🎉 [LOAD LINKS] SUCESSO!")
            println("=".repeat(60))
            return true

        } catch (e: Exception) {
            println("\n💥 [LOAD LINKS] EXCEÇÃO: ${e.message}")
            e.printStackTrace()
            println("=".repeat(60))
            return false
        }
    }

    /**
     * Extrai array e número mágico do JavaScript
     * Funciona com QUALQUER nome de variável!
     */
    private fun extractJavaScriptData(html: String): Pair<List<String>, Int>? {
        // Encontra todos os scripts
        val scriptPattern = Regex("""<script[^>]*>([\s\S]*?)</script>""", setOf(RegexOption.DOT_MATCHES_ALL))
        val scriptMatches = scriptPattern.findAll(html)
        
        for (match in scriptMatches) {
            val scriptContent = match.groupValues[1]
            
            // PROCURA O ARRAY (qualquer nome de variável)
            val arrayPattern = Regex("""(?:var|let|const)\s+([a-zA-Z_$][a-zA-Z0-9_$]*)\s*=\s*(\[.*?\]);""", setOf(RegexOption.DOT_MATCHES_ALL))
            val arrayMatch = arrayPattern.find(scriptContent)
            
            if (arrayMatch != null) {
                val arrayName = arrayMatch.groupValues[1]
                val arrayContent = arrayMatch.groupValues[2]
                println("✅ Array encontrado: nome='$arrayName'")
                
                // PROCURA O NÚMERO MÁGICO (última ocorrência)
                val magicNumberPattern = Regex("""- (\d+)""")
                val magicMatches = magicNumberPattern.findAll(scriptContent).toList()
                
                if (magicMatches.isNotEmpty()) {
                    val lastMagicMatch = magicMatches.last()
                    val magicNumber = lastMagicMatch.groupValues[1].toInt()
                    println("✅ Número mágico encontrado: $magicNumber")
                    
                    // Parseia o array
                    val items = try {
                        // Tenta parsear como JSON
                        mapper.readTree(arrayContent).map { it.asText() }
                    } catch (e: Exception) {
                        println("⚠️ Falha no parse JSON: ${e.message}")
                        // Fallback: parse manual
                        arrayContent.removeSurrounding("[", "]")
                            .split(",")
                            .map { it.trim().replace("\"", "").replace("'", "") }
                            .filter { it.isNotBlank() }
                    }
                    
                    return Pair(items, magicNumber)
                }
            }
        }
        
        // Se não encontrou, tenta método alternativo
        println("⚠️ Tentando método alternativo...")
        
        // Procura por qualquer array grande
        val anyArrayPattern = Regex("""(\[.*?\])""", setOf(RegexOption.DOT_MATCHES_ALL))
        val arrayMatches = anyArrayPattern.findAll(html)
        
        for (arrayMatch in arrayMatches) {
            val arrayContent = arrayMatch.groupValues[1]
            // Verifica se parece um array de strings base64
            if (arrayContent.contains("\"") && arrayContent.length > 100) {
                println("✅ Possível array encontrado")
                
                // Procura o número mágico
                val anyMagicPattern = Regex("""- (\d+)""")
                val magicMatches = anyMagicPattern.findAll(html).toList()
                
                if (magicMatches.isNotEmpty()) {
                    val lastMagicMatch = magicMatches.last()
                    val magicNumber = lastMagicMatch.groupValues[1].toInt()
                    println("✅ Possível número mágico: $magicNumber")
                    
                    // Parseia o array
                    val items = arrayContent.removeSurrounding("[", "]")
                        .split(",")
                        .map { it.trim().replace("\"", "").replace("'", "") }
                        .filter { it.isNotBlank() && it.length > 10 }
                    
                    if (items.size > 5) {
                        return Pair(items, magicNumber)
                    }
                }
            }
        }
        
        return null
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
