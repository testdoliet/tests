package com.EmbedTV

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import java.io.ByteArrayOutputStream

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
    
    private val mainSite = "https://embedtv.best"
    private val baseUrl = "https://www3.embedtv.best"
    private val decryptionKey = "embedtv@123"

    // Cache simples para armazenar os dados dos canais da página inicial
    private var cachedChannels: Map<String, Pair<String, String>>? = null

    // Função para redimensionar imagens (adaptado do FilmesPK)
    private fun fixImageUrl(url: String): String {
        return when {
            url.contains("cloudfront.net") -> {
                // Remove qualquer transformação existente
                val cleanUrl = url.split("?")[0]
                // Adiciona parâmetros de redimensionamento para o CloudFront
                "$cleanUrl?width=300&height=170&fit=crop&format=webp"
            }
            url.startsWith("//") -> "https:$url"
            url.startsWith("http") -> url
            url.startsWith("/") -> "$mainSite$url"
            else -> url
        }
    }

    private fun decryptStreamUrl(encodedString: String): String? {
        return try {
            val decodedBytes = Base64.decode(encodedString, Base64.DEFAULT)
            val decodedString = String(decodedBytes, Charsets.ISO_8859_1)
            val reversedString = decodedString.reversed()
            
            val keyBytes = decryptionKey.toByteArray(Charsets.ISO_8859_1)
            val inputBytes = reversedString.toByteArray(Charsets.ISO_8859_1)
            val outputStream = ByteArrayOutputStream()
            
            for (i in inputBytes.indices) {
                val keyByte = keyBytes[i % keyBytes.size]
                outputStream.write((inputBytes[i].toInt() xor keyByte.toInt()).toByte().toInt())
            }
            
            outputStream.toString(Charsets.UTF_8.name())
        } catch (e: Exception) {
            println("❌ [EmbedTv] Erro na descriptografia: ${e.message}")
            null
        }
    }

    // Função para carregar e cachear todos os canais da página inicial
    private suspend fun loadAllChannels(): Map<String, Pair<String, String>> {
        // Se já temos cache, retorna ele
        cachedChannels?.let { return it }

        println("📥 [EmbedTv] Carregando cache de canais da página inicial...")
        val doc = app.get(mainSite).document
        val channelsMap = mutableMapOf<String, Pair<String, String>>()
        
        // Jogos de Hoje
        val jogosSection = doc.selectFirst(".session.futebol")
        jogosSection?.select(".card")?.forEach { card ->
            val channelId = card.attr("data-channel")
            if (channelId.isNotBlank()) {
                val nameElement = card.selectFirst("h3")
                val channelName = nameElement?.text()?.trim() ?: return@forEach
                
                val imgElement = card.selectFirst("img")
                val imgSrc = imgElement?.attr("src")?.trim()
                val imgDataSrc = imgElement?.attr("data-src")?.trim()
                val imageUrl = if (!imgSrc.isNullOrBlank()) imgSrc else imgDataSrc ?: return@forEach
                
                val timeElement = card.selectFirst("span")
                val time = timeElement?.text()?.trim()
                val displayName = if (!time.isNullOrBlank()) "$channelName ($time)" else channelName
                
                channelsMap[channelId] = Pair(displayName, imageUrl)
            }
        }
        
        // Categorias
        val categories = doc.select(".categorie")
        categories.forEach { category ->
            category.select(".card").forEach { card ->
                val channelId = card.attr("data-channel")
                if (channelId.isNotBlank() && !channelsMap.containsKey(channelId)) {
                    val nameElement = card.selectFirst("h3")
                    val channelName = nameElement?.text()?.trim() ?: return@forEach
                    
                    val imgElement = card.selectFirst("img")
                    val imgSrc = imgElement?.attr("src")?.trim()
                    val imgDataSrc = imgElement?.attr("data-src")?.trim()
                    val imageUrl = if (!imgSrc.isNullOrBlank()) imgSrc else imgDataSrc ?: return@forEach
                    
                    channelsMap[channelId] = Pair(channelName, imageUrl)
                }
            }
        }
        
        println("✅ [EmbedTv] Cache carregado com ${channelsMap.size} canais")
        cachedChannels = channelsMap
        return channelsMap
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        println("📥 [EmbedTv] Carregando página principal...")
        val doc = app.get(mainSite).document
        
        val allCategories = mutableListOf<HomePageList>()
        
        // Jogos de Hoje
        val jogosSection = doc.selectFirst(".session.futebol")
        if (jogosSection != null) {
            println("✅ [EmbedTv] Seção de jogos encontrada")
            val jogosCards = jogosSection.select(".card")
            println("📊 [EmbedTv] ${jogosCards.size} jogos encontrados")
            
            val jogosList = mutableListOf<SearchResponse>()
            for (card in jogosCards) {
                val channelId = card.attr("data-channel")
                if (channelId.isBlank()) continue
                
                val nameElement = card.selectFirst("h3")
                val channelName = nameElement?.text()?.trim() ?: continue
                
                val imgElement = card.selectFirst("img")
                val imgSrc = imgElement?.attr("src")?.trim()
                val imgDataSrc = imgElement?.attr("data-src")?.trim()
                val imageUrl = if (!imgSrc.isNullOrBlank()) imgSrc else imgDataSrc ?: continue
                
                val timeElement = card.selectFirst("span")
                val time = timeElement?.text()?.trim()
                val displayName = if (!time.isNullOrBlank()) "$channelName ($time)" else channelName
                
                jogosList.add(
                    newLiveSearchResponse(
                        displayName,
                        "$baseUrl/$channelId",
                        TvType.Live
                    ) {
                        this.posterUrl = fixImageUrl(imageUrl)
                    }
                )
            }
            
            if (jogosList.isNotEmpty()) {
                allCategories.add(HomePageList("📺 Jogos de Hoje", jogosList, isHorizontalImages = true))
            }
        }
        
        // Categorias
        val categories = doc.select(".categorie")
        println("📊 [EmbedTv] ${categories.size} categorias encontradas")
        
        for (category in categories) {
            val titleElement = category.selectFirst(".title")
            val categoryTitle = titleElement?.text()?.trim() ?: continue
            
            println("📁 [EmbedTv] Processando categoria: $categoryTitle")
            
            val channelList = mutableListOf<SearchResponse>()
            val cards = category.select(".card")
            
            for (card in cards) {
                val channelId = card.attr("data-channel")
                if (channelId.isBlank()) continue
                
                val nameElement = card.selectFirst("h3")
                val channelName = nameElement?.text()?.trim() ?: continue
                
                val imgElement = card.selectFirst("img")
                val imgSrc = imgElement?.attr("src")?.trim()
                val imgDataSrc = imgElement?.attr("data-src")?.trim()
                val imageUrl = if (!imgSrc.isNullOrBlank()) imgSrc else imgDataSrc ?: continue
                
                channelList.add(
                    newLiveSearchResponse(
                        channelName,
                        "$baseUrl/$channelId",
                        TvType.Live
                    ) {
                        this.posterUrl = fixImageUrl(imageUrl)
                    }
                )
            }
            
            if (channelList.isNotEmpty()) {
                println("✅ [EmbedTv] ${channelList.size} canais em $categoryTitle")
                allCategories.add(HomePageList(categoryTitle, channelList, isHorizontalImages = true))
            }
        }
        
        if (allCategories.isEmpty()) {
            println("❌ [EmbedTv] Nenhum canal encontrado!")
            throw ErrorLoadingException("Nenhum canal encontrado.")
        }
        
        println("✅ [EmbedTv] Total de ${allCategories.size} categorias carregadas")
        return newHomePageResponse(allCategories)
    }

    override suspend fun load(url: String): LoadResponse {
        val channelId = url.substringAfterLast("/")
        println("📥 [EmbedTv] Carregando detalhes do canal: $channelId")
        
        // Carrega todos os canais do cache
        val allChannels = loadAllChannels()
        
        // Pega os dados do canal específico do cache
        val channelData = allChannels[channelId]
        val channelName = channelData?.first ?: "Canal $channelId"
        val channelImage = channelData?.second ?: "https://embedtv.best/assets/icon.png"

        // Tenta buscar informações adicionais da página do canal (opcional)
        try {
            val doc = app.get(url).document
            val card = doc.select(".card[data-channel=\"$channelId\"]").firstOrNull()
            
            if (card != null) {
                val timeElement = card.selectFirst("span")
                val time = timeElement?.text()?.trim()
                if (!time.isNullOrBlank()) {
                    println("⏰ [EmbedTv] Horário: $time")
                }
            }
        } catch (e: Exception) {
            // Ignora erros, já temos os dados do cache
        }

        return newMovieLoadResponse(
            channelName,
            url,
            TvType.Live,
            url
        ) {
            this.posterUrl = fixImageUrl(channelImage)
            this.plot = "Assista $channelName ao vivo no EmbedTv"
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        println("🔍 [EmbedTv] Buscando por: $query")
        val allChannels = loadAllChannels()
        val results = mutableListOf<SearchResponse>()
        
        allChannels.forEach { (channelId, data) ->
            val (channelName, imageUrl) = data
            if (channelName.contains(query, ignoreCase = true)) {
                results.add(
                    newLiveSearchResponse(
                        channelName,
                        "$baseUrl/$channelId",
                        TvType.Live
                    ) {
                        this.posterUrl = fixImageUrl(imageUrl)
                    }
                )
            }
        }
        
        println("✅ [EmbedTv] ${results.size} resultados encontrados")
        return results
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val channelUrl = data.ifEmpty { return false }
        println("\n🎬 [EmbedTv] Carregando links para: $channelUrl")
        
        try {
            val doc = app.get(channelUrl).document
            val html = doc.html()
            
            // Procurar pela string codificada no padrão dc("...")
            println("🔍 [EmbedTv] Procurando string codificada...")
            val encodedPattern = Regex("""dc\("([A-Za-z0-9+/=]+)"\)""")
            val encodedMatch = encodedPattern.find(html)
            val encodedString = encodedMatch?.groupValues?.get(1)
            
            var finalUrl: String? = null
            
            if (encodedString != null) {
                println("✅ [EmbedTv] String codificada encontrada!")
                
                // Descriptografar URL com XOR
                val tokenUrl = decryptStreamUrl(encodedString)
                
                if (tokenUrl != null) {
                    println("✅ [EmbedTv] Token URL obtido: $tokenUrl")
                    
                    if (tokenUrl.endsWith(".txt", ignoreCase = true)) {
                        println("📄 [EmbedTv] Token é um arquivo .txt, usando como playlist...")
                        
                        // Verificar se a URL .txt retorna uma playlist válida
                        val testResponse = app.get(tokenUrl, headers = mapOf(
                            "Referer" to baseUrl,
                            "Origin" to baseUrl,
                            "User-Agent" to USER_AGENT
                        )).text
                        
                        if (testResponse.contains("#EXTM3U")) {
                            println("✅ [EmbedTv] Playlist válida encontrada diretamente no .txt!")
                            finalUrl = tokenUrl // USA A PRÓPRIA URL DO .TXT!
                        } else {
                            println("⚠️ [EmbedTv] Conteúdo não é uma playlist HLS")
                        }
                    } else {
                        finalUrl = tokenUrl
                    }
                }
            }
            
            // Fallback: procurar link direto .m3u8
            if (finalUrl == null) {
                println("⚠️ [EmbedTv] String codificada não encontrada, procurando link direto...")
                val directPattern = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")
                finalUrl = directPattern.find(html)?.value
            }
            
            if (finalUrl == null) {
                println("❌ [EmbedTv] Nenhum link encontrado!")
                return false
            }
            
            println("📺 [EmbedTv] URL final: $finalUrl")
            
            val headers = mapOf(
                "referer" to baseUrl,
                "origin" to baseUrl,
                "user-agent" to USER_AGENT,
                "accept" to "*/*"
            )
            
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "EmbedTv Live",
                    url = finalUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = baseUrl
                    this.headers = headers
                    this.quality = Qualities.Unknown.value
                }
            )
            
            println("✅ [EmbedTv] Link enviado com sucesso!")
            return true
            
        } catch (e: Exception) {
            println("❌ [EmbedTv] Erro: ${e.message}")
            e.printStackTrace()
            return false
        }
    }
}
