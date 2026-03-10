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
        
        // PASSO 5: DETECÇÃO AUTOMÁTICA DO ARRAY
        println("\n🔍 [PASSO 5] Detectando array automaticamente...")
        
        val arrayContent = extractArrayFromHtml(iframeHtml)
        if (arrayContent == null) {
            println("❌ [PASSO 5] Não foi possível detectar o array!")
            return false
        }
        
        println("✅ [PASSO 5] Array detectado!")
        println("ℹ️ Tamanho do array: ~${arrayContent.length} caracteres")
        
        // PASSO 6: Parsear array
        println("\n🔨 [PASSO 6] Parseando array para lista...")
        val arrayList = try {
            // Tenta parsear como JSON primeiro
            mapper.readTree(arrayContent).map { it.asText() }
        } catch (e: Exception) {
            println("⚠️ Falha no parse JSON, usando método alternativo")
            // Remove colchetes e divide por vírgulas
            arrayContent.removeSurrounding("[", "]")
                .split(",")
                .map { it.trim().replace("\"", "").replace("'", "") }
                .filter { it.isNotBlank() }
        }
        
        println("✅ [PASSO 6] Lista criada com ${arrayList.size} itens")
        
        // PASSO 7: Decodificar usando o número mágico
        println("\n🔓 [PASSO 7] Decodificando ${arrayList.size} itens...")
        val generatedCode = StringBuilder()
        var processedCount = 0
        val magicNumber = 19667483  // Número mágico fixo (vem do código original)
        
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
                    val charCode = numbers.toInt() - magicNumber
                    if (charCode in 32..126) {
                        generatedCode.append(charCode.toChar())
                        processedCount++
                    }
                }
                
            } catch (e: Exception) {
                // Ignora erros
            }
        }
        
        println("✅ [PASSO 7] Decodificação concluída! $processedCount caracteres gerados")
        
        // PASSO 8: Procurar link .m3u8
        println("\n🔎 [PASSO 8] Procurando link .m3u8 no código gerado...")
        val linkPattern = Regex("(https?://[^\"'\\s]+\\.m3u8[^\"'\\s]*)")
        val match = linkPattern.find(generatedCode.toString())
        
        if (match == null) {
            println("❌ [PASSO 8] Nenhum link .m3u8 encontrado!")
            println("📄 Primeiros 500 caracteres do código gerado:")
            println(generatedCode.toString().take(500))
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

/**
 * Extrai automaticamente o array do HTML, independente do nome da variável
 */
private fun extractArrayFromHtml(html: String): String? {
    // Padrão 1: var NOME = [...] 
    val varPattern = Regex("""var\s+([a-zA-Z_$][a-zA-Z0-9_$]*)\s*=\s*(\[.*?\]);""", setOf(RegexOption.DOT_MATCHES_ALL))
    val varMatch = varPattern.find(html)
    if (varMatch != null) {
        println("✅ Detectado padrão 'var nome = [...]'")
        return varMatch.groupValues[2]
    }
    
    // Padrão 2: let NOME = [...]
    val letPattern = Regex("""let\s+([a-zA-Z_$][a-zA-Z0-9_$]*)\s*=\s*(\[.*?\]);""", setOf(RegexOption.DOT_MATCHES_ALL))
    val letMatch = letPattern.find(html)
    if (letMatch != null) {
        println("✅ Detectado padrão 'let nome = [...]'")
        return letMatch.groupValues[2]
    }
    
    // Padrão 3: const NOME = [...]
    val constPattern = Regex("""const\s+([a-zA-Z_$][a-zA-Z0-9_$]*)\s*=\s*(\[.*?\]);""", setOf(RegexOption.DOT_MATCHES_ALL))
    val constMatch = constPattern.find(html)
    if (constMatch != null) {
        println("✅ Detectado padrão 'const nome = [...]'")
        return constMatch.groupValues[2]
    }
    
    // Padrão 4: NOME = [...] (sem declaração)
    val assignPattern = Regex("""([a-zA-Z_$][a-zA-Z0-9_$]*)\s*=\s*(\[.*?\]);""", setOf(RegexOption.DOT_MATCHES_ALL))
    val assignMatch = assignPattern.find(html)
    if (assignMatch != null) {
        println("✅ Detectado padrão 'nome = [...]'")
        return assignMatch.groupValues[2]
    }
    
    // Padrão 5: Procura por qualquer array grande (mais de 10 itens) com strings base64
    val base64Pattern = Regex("""\[(\s*"[A-Za-z0-9+/=]+"\s*,?\s*)+\]""", setOf(RegexOption.DOT_MATCHES_ALL))
    val matches = base64Pattern.findAll(html)
    
    for (match in matches) {
        val arrayContent = match.value
        // Verifica se tem muitos itens (provavelmente é o array que queremos)
        val itemCount = arrayContent.split(",").size
        if (itemCount > 10) {
            println("✅ Detectado array grande com $itemCount itens")
            return arrayContent
        }
    }
    
    // Padrão 6: Procura pelo padrão específico do site (com atob)
    val scriptBlockPattern = Regex("""<script[^>]*>([\s\S]*?)</script>""", setOf(RegexOption.DOT_MATCHES_ALL))
    val scriptMatches = scriptBlockPattern.findAll(html)
    
    for (scriptMatch in scriptMatches) {
        val scriptContent = scriptMatch.groupValues[1]
        
        // Procura por padrão: algo.forEach(function...
        val forEachPattern = Regex("""([a-zA-Z_$][a-zA-Z0-9_$]*)\.forEach\(function""")
        val forEachMatch = forEachPattern.find(scriptContent)
        
        if (forEachMatch != null) {
            val arrayName = forEachMatch.groupValues[1]
            println("✅ Detectado array pelo forEach: nome = '$arrayName'")
            
            // Agora procura pela declaração desse array
            val arrayDeclPattern = Regex("""(?:var|let|const)\s+${Regex.escape(arrayName)}\s*=\s*(\[.*?\]);""", setOf(RegexOption.DOT_MATCHES_ALL))
            val arrayDeclMatch = arrayDeclPattern.find(scriptContent)
            if (arrayDeclMatch != null) {
                return arrayDeclMatch.groupValues[1]
            }
        }
    }
    
    println("❌ Nenhum array detectado!")
    return null
}
}
