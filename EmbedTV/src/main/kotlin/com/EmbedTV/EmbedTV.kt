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
                val gameName = nameElement?.text()?.trim() ?: continue
                
                val imgElement = card.selectFirst("img")
                val imgSrc = imgElement?.attr("src")?.trim()
                val imgDataSrc = imgElement?.attr("data-src")?.trim()
                val imageUrl = if (!imgSrc.isNullOrBlank()) imgSrc else imgDataSrc ?: continue
                
                val timeElement = card.selectFirst("span")
                val time = timeElement?.text()?.trim()
                val displayName = if (!time.isNullOrBlank()) "$gameName ($time)" else gameName
                
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
        
        // Categorias (canais fixos)
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
        println("\n" + "=".repeat(60))
        println("📥 [EmbedTv] Carregando detalhes do canal: $channelId")
        println("=".repeat(60))
        
        // Busca a página principal
        val mainPage = app.get(mainSite).document
        
        // DEBUG: Vamos ver todos os data-channel disponíveis
        val allCards = mainPage.select(".card")
        println("🔍 Total de cards encontrados na página principal: ${allCards.size}")
        println("-".repeat(60))
        
        // Mostrar primeiros 20 cards para debug
        allCards.take(20).forEachIndexed { index, card ->
            val id = card.attr("data-channel")
            val name = card.selectFirst("h3")?.text()?.trim() ?: "sem nome"
            val hasSpan = card.selectFirst("span") != null
            println("   Card $index: data-channel='$id' | nome='$name' | tem horário=$hasSpan")
        }
        println("-".repeat(60))
        
        // Tenta encontrar o card de várias formas
        var channelCard = mainPage.selectFirst(".card[data-channel=\"$channelId\"]")
        
        if (channelCard == null) {
            println("❌ Card não encontrado para data-channel exato '$channelId'")
            
            // Tenta encontrar por texto no nome
            channelCard = mainPage.selectFirst(".card h3:contains($channelId)")?.parent()
            if (channelCard != null) {
                val foundId = channelCard.attr("data-channel")
                println("✅ Encontrado por texto! data-channel='$foundId'")
            } else {
                // Tenta encontrar por qualquer card que contenha o ID no atributo
                channelCard = mainPage.selectFirst(".card[data-channel*=\"$channelId\"]")
                if (channelCard != null) {
                    val foundId = channelCard.attr("data-channel")
                    println("✅ Encontrado por contains! data-channel='$foundId'")
                }
            }
        } else {
            println("✅ Card encontrado com data-channel exato!")
        }
        
        if (channelCard == null) {
            println("❌ Nenhum card encontrado para o canal $channelId")
            println("=".repeat(60))
            
            // Fallback para nome genérico
            return newMovieLoadResponse(
                "Canal $channelId",
                url,
                TvType.Live,
                url
            ) {
                this.posterUrl = "https://embedtv.best/assets/icon.png"
                this.plot = "Assista ao vivo no EmbedTv"
            }
        }
        
        // Extrair informações do card
        val nameElement = channelCard.selectFirst("h3")
        val channelName = nameElement?.text()?.trim() ?: "Canal $channelId"
        
        val imgElement = channelCard.selectFirst("img")
        val imgSrc = imgElement?.attr("src")?.trim()
        val imgDataSrc = imgElement?.attr("data-src")?.trim()
        val channelImage = if (!imgSrc.isNullOrBlank()) imgSrc else imgDataSrc ?: "https://embedtv.best/assets/icon.png"
        
        val timeElement = channelCard.selectFirst("span")
        val time = timeElement?.text()?.trim()
        val displayName = if (!time.isNullOrBlank()) "$channelName ($time)" else channelName
        
        println("📺 Nome encontrado: $displayName")
        println("🖼️ Imagem encontrada: $channelImage")
        if (time != null) println("⏰ Horário: $time")
        println("=".repeat(60))

        return newMovieLoadResponse(
            displayName,
            url,
            TvType.Live,
            url
        ) {
            this.posterUrl = fixImageUrl(channelImage)
            this.plot = "Assista $displayName ao vivo no EmbedTv"
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        println("🔍 [EmbedTv] Buscando por: $query")
        val doc = app.get(mainSite).document
        val allCards = doc.select(".card")
        val results = mutableListOf<SearchResponse>()
        
        println("📊 [EmbedTv] Total de ${allCards.size} cards para buscar")
        
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
            val time = timeElement?.text()?.trim()
            val displayName = if (!time.isNullOrBlank()) "$channelName ($time)" else channelName
            
            results.add(
                newLiveSearchResponse(
                    displayName,
                    "$baseUrl/$channelId",
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
        val channelUrl = data.ifEmpty { return false }
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
                println("📦 Tamanho: ${encodedString.length} caracteres")
                
                // Descriptografar URL com XOR
                println("🔓 [PASSO 2] Descriptografando com XOR...")
                val tokenUrl = decryptStreamUrl(encodedString)
                
                if (tokenUrl != null) {
                    println("✅ Token URL obtido: $tokenUrl")
                    
                    if (tokenUrl.endsWith(".txt", ignoreCase = true)) {
                        println("📄 [PASSO 3] Token é um arquivo .txt, buscando playlist...")
                        
                        // Buscar o arquivo .txt
                        val tokenResponse = app.get(tokenUrl, headers = mapOf(
                            "Referer" to baseUrl,
                            "Origin" to baseUrl,
                            "User-Agent" to USER_AGENT
                        )).text
                        
                        println("✅ Playlist obtida (${tokenResponse.length} caracteres)")
                        
                        if (tokenResponse.contains("#EXTM3U")) {
                            println("✅ Playlist HLS válida encontrada!")
                            finalUrl = tokenUrl
                        } else {
                            println("⚠️ Conteúdo não é uma playlist HLS")
                        }
                    } else {
                        finalUrl = tokenUrl
                    }
                }
            }
            
            // Fallback: procurar link direto .m3u8
            if (finalUrl == null) {
                println("⚠️ [FALLBACK] Procurando link direto .m3u8...")
                val directPattern = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")
                finalUrl = directPattern.find(html)?.value
                if (finalUrl != null) {
                    println("✅ Link direto encontrado!")
                }
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
