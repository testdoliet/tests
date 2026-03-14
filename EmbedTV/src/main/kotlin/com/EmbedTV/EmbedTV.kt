package com.EmbedTV

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.logger.Log
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

    // Cache de jogos (da seção futebol)
    private var jogosCache = mutableMapOf<String, Pair<String, String>>()
    
    // Cache de canais fixos (das categorias)
    private var canaisCache = mutableMapOf<String, Pair<String, String>>()

    // Função para redimensionar imagens
    private fun fixImageUrl(url: String): String {
        return when {
            url.contains("cloudfront.net") -> {
                val cleanUrl = url.split("?")[0]
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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        println("📥 [EmbedTv] Carregando página principal...")
        val doc = app.get(mainSite).document
        
        // Limpar caches
        jogosCache.clear()
        canaisCache.clear()
        
        val allCategories = mutableListOf<HomePageList>()
        
        // ========== JOGOS DE HOJE (seção futebol) ==========
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
                val gameName = nameElement?.text()?.trim() ?: continue
                
                val imgElement = card.selectFirst("img")
                val imgSrc = imgElement?.attr("src")?.trim()
                val imgDataSrc = imgElement?.attr("data-src")?.trim()
                val imageUrl = if (!imgSrc.isNullOrBlank()) imgSrc else imgDataSrc ?: continue
                
                val timeElement = card.selectFirst("span")
                val time = timeElement?.text()?.trim()
                val displayName = if (!time.isNullOrBlank()) "$gameName ($time)" else gameName
                
                // Salvar no cache de jogos
                jogosCache[channelId] = Pair(displayName, imageUrl)
                
                // IMPORTANTE: Adicionar um parâmetro na URL para identificar que veio dos jogos
                val jogoUrl = "$baseUrl/$channelId?source=jogos"
                
                jogosList.add(
                    newLiveSearchResponse(
                        displayName,
                        jogoUrl,
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
        
        // ========== CATEGORIAS (canais fixos) ==========
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
                
                // Salvar no cache de canais fixos
                if (!canaisCache.containsKey(channelId)) {
                    canaisCache[channelId] = Pair(channelName, imageUrl)
                }
                
                // URL sem parâmetro (para canais fixos)
                val canalUrl = "$baseUrl/$channelId"
                
                channelList.add(
                    newLiveSearchResponse(
                        channelName,
                        canalUrl,
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
        
        println("✅ [EmbedTv] Total de ${allCategories.size} categorias carregadas")
        println("📊 Cache: ${jogosCache.size} jogos, ${canaisCache.size} canais fixos")
        
        return newHomePageResponse(allCategories)
    }

    override suspend fun load(url: String): LoadResponse {
        // Verificar se veio dos jogos (tem o parâmetro source=jogos)
        val isFromJogos = url.contains("source=jogos")
        val cleanUrl = url.replace("?source=jogos", "")
        
        val channelId = cleanUrl.substringAfterLast("/")
        println("\n" + "=".repeat(60))
        println("📥 [EmbedTv] Carregando detalhes do canal: $channelId")
        println("📥 [EmbedTv] Veio dos jogos? $isFromJogos")
        println("=".repeat(60))
        
        // Busca a página principal
        val mainPage = app.get(mainSite).document
        
        // DEBUG: Mostrar primeiros cards
        val allCards = mainPage.select(".card")
        println("🔍 Total de cards: ${allCards.size}")
        
        val channelCard = mainPage.selectFirst(".card[data-channel=\"$channelId\"]")
        
        if (channelCard == null) {
            println("❌ Card não encontrado!")
            return newMovieLoadResponse(
                "Canal $channelId",
                cleanUrl,
                TvType.Live,
                cleanUrl
            ) {
                this.posterUrl = "https://embedtv.best/assets/icon.png"
                this.plot = "Assista ao vivo no EmbedTv"
            }
        }
        
        // DECISÃO: Qual imagem usar?
        val (finalName, finalImage) = if (isFromJogos) {
            // VEIO DOS JOGOS: usa dados do jogo (com horário)
            println("🎮 Usando dados do JOGO")
            val nameElement = channelCard.selectFirst("h3")
            val gameName = nameElement?.text()?.trim() ?: "Canal $channelId"
            
            val imgElement = channelCard.selectFirst("img")
            val imgSrc = imgElement?.attr("src")?.trim()
            val imgDataSrc = imgElement?.attr("data-src")?.trim()
            val gameImage = if (!imgSrc.isNullOrBlank()) imgSrc else imgDataSrc ?: "https://embedtv.best/assets/icon.png"
            
            val timeElement = channelCard.selectFirst("span")
            val time = timeElement?.text()?.trim()
            val displayName = if (!time.isNullOrBlank()) "$gameName ($time)" else gameName
            
            Pair(displayName, gameImage)
            
        } else {
            // VEIO DOS CANAIS FIXOS: usa dados do cache de canais (logo do canal)
            println("📺 Usando dados do CANAL FIXO")
            
            // Tenta pegar do cache de canais fixos
            val canalData = canaisCache[channelId]
            if (canalData != null) {
                val (canalName, canalImage) = canalData
                Pair(canalName, canalImage)
            } else {
                // Fallback: pega do card mas remove horário se tiver
                val nameElement = channelCard.selectFirst("h3")
                val rawName = nameElement?.text()?.trim() ?: "Canal $channelId"
                
                // Remove horário se tiver (ex: "Al-Fateh X Al-Hilal" vira "Band")
                // Mas como é canal fixo, o nome já deve ser "Band"
                
                val imgElement = channelCard.selectFirst("img")
                val imgSrc = imgElement?.attr("src")?.trim()
                val imgDataSrc = imgElement?.attr("data-src")?.trim()
                val fallbackImage = if (!imgSrc.isNullOrBlank()) imgSrc else imgDataSrc ?: "https://embedtv.best/assets/icon.png"
                
                Pair(rawName, fallbackImage)
            }
        }
        
        println("📺 Nome: $finalName")
        println("🖼️ Imagem: $finalImage")
        println("=".repeat(60))

        return newMovieLoadResponse(
            finalName,
            cleanUrl,
            TvType.Live,
            cleanUrl
        ) {
            this.posterUrl = fixImageUrl(finalImage)
            this.plot = "Assista $finalName ao vivo no EmbedTv"
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        println("🔍 [EmbedTv] Buscando por: $query")
        val doc = app.get(mainSite).document
        val allCards = doc.select(".card")
        val results = mutableListOf<SearchResponse>()
        
        for (card in allCards) {
            val channelId = card.attr("data-channel")
            if (channelId.isBlank()) continue
            
            val nameElement = card.selectFirst("h3")
            val channelName = nameElement?.text()?.trim() ?: continue
            
            if (!channelName.contains(query, ignoreCase = true)) continue
            
            val imgElement = card.selectFirst("img")
            val imgSrc = imgElement?.attr("src")?.trim()
            val imgDataSrc = imgElement?.attr("data-src")?.trim()
            val imageUrl = if (!imgSrc.isNullOrBlank()) imgSrc else imgDataSrc ?: continue
            
            val timeElement = card.selectFirst("span")
            val hasHorario = timeElement != null
            
            // Se tem horário, é um jogo - adiciona parâmetro
            val finalUrl = if (hasHorario) {
                "$baseUrl/$channelId?source=jogos"
            } else {
                "$baseUrl/$channelId"
            }
            
            val time = timeElement?.text()?.trim()
            val displayName = if (!time.isNullOrBlank()) "$channelName ($time)" else channelName
            
            results.add(
                newLiveSearchResponse(
                    displayName,
                    finalUrl,
                    TvType.Live
                ) {
                    this.posterUrl = fixImageUrl(imageUrl)
                }
            )
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
        // Remove parâmetros da URL antes de carregar os links
        val cleanUrl = data.split("?")[0]
        val channelUrl = cleanUrl.ifEmpty { return false }
        
        println("\n" + "=".repeat(60))
        println("🎬 [EmbedTv] Carregando links para: $channelUrl")
        println("=".repeat(60))
        
        try {
            val doc = app.get(channelUrl).document
            val html = doc.html()
            
            // Procurar pela string codificada no padrão dc("...")
            println("🔍 [PASSO 1] Procurando string codificada...")
            val encodedPattern = Regex("""dc\("([A-Za-z0-9+/=]+)"\)""")
            val encodedMatch = encodedPattern.find(html)
            val encodedString = encodedMatch?.groupValues?.get(1)
            
            var finalUrl: String? = null
            
            if (encodedString != null) {
                println("✅ String codificada encontrada!")
                
                val tokenUrl = decryptStreamUrl(encodedString)
                
                if (tokenUrl != null) {
                    println("✅ Token URL obtido: $tokenUrl")
                    
                    if (tokenUrl.endsWith(".txt", ignoreCase = true)) {
                        println("📄 Token é um arquivo .txt, verificando...")
                        
                        val testResponse = app.get(tokenUrl, headers = mapOf(
                            "Referer" to baseUrl,
                            "Origin" to baseUrl,
                            "User-Agent" to USER_AGENT
                        )).text
                        
                        if (testResponse.contains("#EXTM3U")) {
                            println("✅ Playlist HLS válida!")
                            finalUrl = tokenUrl
                        }
                    } else {
                        finalUrl = tokenUrl
                    }
                }
            }
            
            if (finalUrl == null) {
                println("⚠️ Procurando link direto .m3u8...")
                val directPattern = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")
                finalUrl = directPattern.find(html)?.value
            }
            
            if (finalUrl == null) {
                println("❌ Nenhum link encontrado!")
                return false
            }
            
            println("📺 URL final: $finalUrl")
            
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
            
            println("✅ Link enviado com sucesso!")
            println("=".repeat(60))
            return true
            
        } catch (e: Exception) {
            println("❌ Erro: ${e.message}")
            e.printStackTrace()
            println("=".repeat(60))
            return false
        }
    }
}
