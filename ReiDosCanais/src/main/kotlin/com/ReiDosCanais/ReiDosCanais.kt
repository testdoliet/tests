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

    // ================== LOAD LINKS COM DEBUG COMPLETO ==================
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
            // ===== HEADERS COMPLETOS (igual ao Python) =====
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
                "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
                "sec-ch-ua" to "\"Chromium\";v=\"127\", \"Not)A;Brand\";v=\"99\", \"Microsoft Edge Simulate\";v=\"127\", \"Lemur\";v=\"127\"",
                "sec-ch-ua-mobile" to "?1",
                "sec-ch-ua-platform" to "\"Android\"",
                "Upgrade-Insecure-Requests" to "1"
            )

            // ===== PASSO 1: Buscar página do canal =====
            println("\n📥 [PASSO 1] Buscando página do canal: $data")
            val channelDocument = app.get(data, headers = headers).document
            println("✅ [PASSO 1] Página carregada com sucesso")
            
            // ===== PASSO 2: Extrair iframe =====
            println("\n🖼️ [PASSO 2] Procurando iframe...")
            val iframeElement = channelDocument.selectFirst("iframe")
            if (iframeElement == null) {
                println("❌ [PASSO 2] Nenhum iframe encontrado!")
                return false
            }
            
            var iframeSrc = iframeElement.attr("src")
            println("🔗 [PASSO 2] iframe src encontrado: $iframeSrc")
            
            // Construir URL completa do iframe
            if (!iframeSrc.startsWith("http")) {
                iframeSrc = "$mainSite$iframeSrc"
                println("🔧 [PASSO 2] URL convertida para: $iframeSrc")
            }
            
            // ===== PASSO 3: Headers para iframe com Referer =====
            println("\n🔧 [PASSO 3] Configurando headers para iframe...")
            val iframeHeaders = headers.toMutableMap()
            iframeHeaders["Referer"] = data
            println("ℹ️ Headers enviados:")
            iframeHeaders.forEach { (key, value) ->
                println("   $key: $value")
            }
            
            // ===== PASSO 4: Buscar iframe =====
            println("\n📥 [PASSO 4] Buscando página do iframe: $iframeSrc")
            val iframeResponse = app.get(iframeSrc, headers = iframeHeaders)
            val iframeHtml = iframeResponse.text
            println("✅ [PASSO 4] Página do iframe carregada (${iframeHtml.length} caracteres)")
            
            // ===== DEBUG: Salvar HTML para análise =====
            println("\n🔍 [DEBUG] Primeiros 1000 caracteres do HTML do iframe:")
            println("-".repeat(40))
            val preview = iframeHtml.take(1000)
            println(preview)
            println("-".repeat(40))
            
            // ===== DEBUG: Procurar por "mCW" no HTML =====
            println("\n🔍 [DEBUG] Procurando pela string 'mCW' no HTML...")
            val containsMcw = iframeHtml.contains("mCW")
            println("ℹ️ HTML contém 'mCW'? $containsMcw")
            
            if (containsMcw) {
                val mcwIndex = iframeHtml.indexOf("mCW")
                val contexto = iframeHtml.substring(maxOf(0, mcwIndex - 50), minOf(iframeHtml.length, mcwIndex + 50))
                println("📌 Contexto ao redor de 'mCW': ...$contexto...")
            }
            
            // ===== PASSO 5: Encontrar array mCW com múltiplos padrões =====
            println("\n🔍 [PASSO 5] Procurando array 'mCW' no HTML...")
            
            val padroesMcw = listOf(
                Regex("""var mCW = (\[.*?\]);""", setOf(RegexOption.DOT_MATCHES_ALL)),
                Regex("""var mCW\s*=\s*(\[.*?\]);""", setOf(RegexOption.DOT_MATCHES_ALL)),
                Regex("""mCW\s*=\s*(\[.*?\])""", setOf(RegexOption.DOT_MATCHES_ALL)),
                Regex("""const mCW = (\[.*?\]);""", setOf(RegexOption.DOT_MATCHES_ALL)),
                Regex("""let mCW = (\[.*?\]);""", setOf(RegexOption.DOT_MATCHES_ALL))
            )
            
            var mCWJsonString: String? = null
            var padraoUsado = ""
            
            for (padrao in padroesMcw) {
                val match = padrao.find(iframeHtml)
                if (match != null) {
                    mCWJsonString = match.groupValues[1]
                    padraoUsado = padrao.pattern
                    println("✅ Array mCW encontrado com padrão: $padraoUsado")
                    break
                }
            }
            
            if (mCWJsonString == null) {
                println("❌ [PASSO 5] Array mCW não encontrado com nenhum padrão!")
                
                // ===== DEBUG: Mostrar primeiras linhas do HTML =====
                println("\n🔍 [DEBUG] Primeiras 20 linhas do HTML:")
                val linhas = iframeHtml.split("\n").take(20)
                linhas.forEachIndexed { i, linha ->
                    if (linha.length > 100) {
                        println("   ${i+1}: ${linha.take(100)}...")
                    } else {
                        println("   ${i+1}: $linha")
                    }
                }
                return false
            }
            
            println("ℹ️ Tamanho do array: ~${mCWJsonString.length} caracteres")
            
            // ===== PASSO 6: Parsear array mCW =====
            println("\n🔨 [PASSO 6] Parseando array mCW para lista...")
            val mCWList = try {
                mapper.readTree(mCWJsonString).map { it.asText() }
            } catch (e: Exception) {
                println("⚠️ Falha no parse JSON, usando método alternativo")
                mCWJsonString.removeSurrounding("[", "]")
                    .split(",")
                    .map { it.trim().replace("\"", "").replace("'", "") }
                    .filter { it.isNotBlank() }
            }
            
            println("✅ [PASSO 6] Lista criada com ${mCWList.size} itens")
            
            // Mostrar primeiros itens
            println("ℹ️ Primeiros 5 itens do array:")
            for (i in 0 until minOf(5, mCWList.size)) {
                val preview = if (mCWList[i].length > 50) "${mCWList[i].substring(0, 50)}..." else mCWList[i]
                println("   ${i + 1}: $preview")
            }
            
            // ===== PASSO 7: Decodificar =====
            println("\n🔓 [PASSO 7] Decodificando ${mCWList.size} itens...")
            val generatedCode = StringBuilder()
            var processedCount = 0
            
            mCWList.forEachIndexed { index, item ->
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
                    
                    if ((index + 1) % 20 == 0) {
                        println("ℹ️ Processados ${index + 1}/${mCWList.size} itens...")
                    }
                    
                } catch (e: Exception) {
                    // Ignora erros
                }
            }
            
            println("✅ [PASSO 7] Decodificação concluída! $processedCount itens processados")
            
            // Mostrar preview do código gerado
            val codePreview = if (generatedCode.length > 500) {
                generatedCode.substring(0, 500) + "..."
            } else {
                generatedCode.toString()
            }
            println("\n📄 Código gerado (primeiros 500 caracteres):")
            println("-".repeat(40))
            println(codePreview)
            println("-".repeat(40))
            
            // ===== PASSO 8: Procurar link .m3u8 =====
            println("\n🔎 [PASSO 8] Procurando link .m3u8 no código gerado...")
            val linkPattern = Regex("(https?://[^\"]+\\.m3u8[^\"]*)")
            val match = linkPattern.find(generatedCode.toString())
            
            if (match == null) {
                println("❌ [PASSO 8] Nenhum link .m3u8 encontrado!")
                return false
            }
            
            val videoUrl = match.groupValues[0]
            println("✅ [PASSO 8] Link encontrado: $videoUrl")
            
            // ===== PASSO 9: Headers para o vídeo =====
            println("\n🔧 [PASSO 9] Configurando headers para o vídeo...")
            val videoHeaders = mapOf(
                "Referer" to "https://p2player.live/",
                "Origin" to "https://p2player.live/",
                "User-Agent" to headers["User-Agent"]!!
            )
            
            // ===== PASSO 10: Retornar link =====
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
            println("📋 Stacktrace:")
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
