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
        println("\n🚀 ===================================================")
        println("🚀 [PÁGINA PRINCIPAL] Iniciando carregamento")
        println("🚀 ===================================================\n")
        
        val homePageList = mutableListOf<HomePageList>()

        try {
            println("📡 [PÁGINA PRINCIPAL] Fazendo requisição para API: $channelsEndpoint")
            val response = app.get(channelsEndpoint, timeout = 30).text
            val json = mapper.readTree(response)
            
            if (json.has("success") && json["success"].asBoolean()) {
                println("✅ [PÁGINA PRINCIPAL] API respondeu com sucesso")
                val channelsData = json["data"]
                
                if (channelsData.isArray) {
                    println("📊 [PÁGINA PRINCIPAL] Total de canais encontrados: ${channelsData.size()}")
                    
                    val channelsByCategory = channelsData.mapNotNull { node ->
                        parseChannel(node)
                    }.groupBy { it.category }
                    
                    println("📂 [PÁGINA PRINCIPAL] Categorias encontradas: ${channelsByCategory.keys}")
                    
                    channelsByCategory.forEach { (categoryName, channels) ->
                        println("📁 [PÁGINA PRINCIPAL] Processando categoria: $categoryName (${channels.size} canais)")
                        
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
                            println("✅ [PÁGINA PRINCIPAL] Categoria '$categoryName' adicionada com ${channelList.size} canais")
                        }
                    }
                }
            } else {
                println("❌ [PÁGINA PRINCIPAL] API retornou erro ou success = false")
            }
        } catch (e: Exception) {
            println("💥 [PÁGINA PRINCIPAL] EXCEÇÃO: ${e.message}")
            e.printStackTrace()
            throw ErrorLoadingException("Falha ao carregar canais: ${e.message}")
        }

        if (homePageList.isEmpty()) {
            println("⚠️ [PÁGINA PRINCIPAL] Nenhum canal encontrado")
            throw ErrorLoadingException("Nenhum canal encontrado.")
        }
        
        println("🎉 [PÁGINA PRINCIPAL] Carregamento concluído com ${homePageList.size} categorias\n")
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
            println("⚠️ [PARSE] Erro ao parsear canal: ${e.message}")
            null
        }
    }

    // ================== PÁGINA DE DETALHES ==================
    override suspend fun load(url: String): LoadResponse {
        println("\n🔍 [DETALHES] Carregando detalhes do canal: $url")
        
        val channelId = url.substringAfterLast("/")
        println("🆔 [DETALHES] ID do canal extraído: $channelId")
        
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
                        println("✅ [DETALHES] Canal encontrado: $channelName")
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
        println("\n🔎 [BUSCA] Buscando por: '$query'")
        
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
                    println("✅ [BUSCA] Encontrados ${matchingChannels.size} resultados para '$query'")
                }
            }
        } catch (e: Exception) {
            println("💥 [BUSCA] Exceção: ${e.message}")
            e.printStackTrace()
            return null
        }

        return results
    }

    // ================== LOAD LINKS COM DEBUGS ==================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("\n${"=".repeat(60)}")
        println("🎬 [LOAD LINKS] INICIANDO EXTRAÇÃO PARA: $data")
        println("=".repeat(60))

        // Headers básicos
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept-Language" to "pt-BR,pt;q=0.9"
        )

        try {
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
            println("ℹ️ Headers: Referer=$data")
            
            // ===== PASSO 4: Buscar iframe =====
            println("\n📥 [PASSO 4] Buscando página do iframe...")
            val iframeResponse = app.get(iframeSrc, headers = iframeHeaders)
            val iframeHtml = iframeResponse.text
            println("✅ [PASSO 4] Página do iframe carregada (${iframeHtml.length} caracteres)")
            
            // ===== PASSO 5: Encontrar array mCW =====
            println("\n🔍 [PASSO 5] Procurando array 'mCW' no HTML...")
            val mCWStart = iframeHtml.indexOf("var mCW = [")
            if (mCWStart == -1) {
                println("❌ [PASSO 5] Array mCW não encontrado!")
                return false
            }
            
            val mCWEnd = iframeHtml.indexOf("];", mCWStart) + 1
            if (mCWEnd == 0) {
                println("❌ [PASSO 5] Fim do array não encontrado!")
                return false
            }
            
            val mCWJson = iframeHtml.substring(mCWStart + 10, mCWEnd)
            println("✅ [PASSO 5] Array mCW encontrado!")
            println("ℹ️ Tamanho do array: ~${mCWJson.length} caracteres")
            
            // ===== PASSO 6: Parsear array =====
            println("\n🔨 [PASSO 6] Parseando array mCW para lista...")
            val mCWList = mCWJson.removeSurrounding("[", "]")
                .split(",")
                .map { it.trim().replace("\"", "").replace("'", "") }
                .filter { it.isNotBlank() }
            
            println("✅ [PASSO 6] Lista criada com ${mCWList.size} itens")
            
            // Mostrar primeiros itens para debug
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
                    
                    // Mostrar progresso a cada 20 itens
                    if ((index + 1) % 20 == 0) {
                        println("ℹ️ Processados ${index + 1}/${mCWList.size} itens...")
                    }
                    
                } catch (e: Exception) {
                    // Ignora erros
                }
            }
            
            println("✅ [PASSO 7] Decodificação concluída! $processedCount itens processados")
            
            // Mostrar preview do código gerado
            val preview = if (generatedCode.length > 300) {
                generatedCode.substring(0, 300) + "..."
            } else {
                generatedCode.toString()
            }
            println("\n📄 Código gerado (primeiros 300 caracteres):")
            println("-".repeat(40))
            println(preview)
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
            
            // ===== PASSO 9: Configurar headers para o vídeo =====
            println("\n🔧 [PASSO 9] Configurando headers para o vídeo...")
            val videoHeaders = mapOf(
                "Referer" to "https://p2player.live/",
                "Origin" to "https://p2player.live/",
                "User-Agent" to USER_AGENT
            )
            println("ℹ️ Headers: Referer=https://p2player.live/")
            
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
