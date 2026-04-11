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
    override var name = "EmbedTV"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = false
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Live)

    private val mainSite = "https://embedtv.cv"
    private val baseUrl = "https://www4.embedtv.cv"
    private val blockedChannels = listOf("sexyhot", "playboy")

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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(mainSite).document
        val allCategories = mutableListOf<HomePageList>()
        
        // Seção de Jogos de Hoje
        val jogosSection = doc.selectFirst(".session.futebol")
        if (jogosSection != null) {
            val jogosCards = jogosSection.select(".card")

            val jogosList = mutableListOf<SearchResponse>()
            for (card in jogosCards) {
                val channelId = card.attr("data-channel")
                if (channelId.isBlank() || channelId in blockedChannels) continue

                val nameElement = card.selectFirst("h3") ?: continue
                val gameName = nameElement.text().trim()

                val imgElement = card.selectFirst("img") ?: continue
                val imageUrl = imgElement.attr("src").ifEmpty { imgElement.attr("data-src") }

                val timeElement = card.selectFirst("span")
                val time = timeElement?.text()?.trim()
                val displayName = if (!time.isNullOrBlank()) "$gameName ($time)" else gameName

                // Adiciona ?source=jogos para identificar que veio da seção de jogos
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

        // Demais categorias de canais
        val categories = doc.select(".categorie")

        for (category in categories) {
            val titleElement = category.selectFirst(".title") ?: continue
            val categoryTitle = titleElement.text().trim()

            val channelList = mutableListOf<SearchResponse>()
            val cards = category.select(".card")

            for (card in cards) {
                val channelId = card.attr("data-channel")
                if (channelId.isBlank() || channelId in blockedChannels) continue

                val nameElement = card.selectFirst("h3") ?: continue
                val channelName = nameElement.text().trim()

                val imgElement = card.selectFirst("img") ?: continue
                val imageUrl = imgElement.attr("src").ifEmpty { imgElement.attr("data-src") }

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
                allCategories.add(HomePageList(categoryTitle, channelList, isHorizontalImages = true))
            }
        }

        if (allCategories.isEmpty()) {
            throw ErrorLoadingException("Nenhum canal encontrado.")
        }

        return newHomePageResponse(allCategories, hasNext = false)
    }

    override suspend fun load(url: String): LoadResponse {
        val isFromJogos = url.contains("source=jogos")
        val cleanUrl = url.replace("?source=jogos", "")

        val channelId = cleanUrl.substringAfterLast("/")
        if (channelId in blockedChannels) {
            throw ErrorLoadingException("Canal não disponível")
        }

        val mainPage = app.get(mainSite).document
        val card = mainPage.selectFirst(".card[data-channel=\"$channelId\"]")
        
        if (card == null) {
            return newMovieLoadResponse(
                "Canal $channelId",
                cleanUrl,
                TvType.Live,
                cleanUrl
            ) {
                this.posterUrl = "https://embedtv.best/assets/icon.png"
            }
        }

        val channelName = card.selectFirst("h3")?.text()?.trim() ?: "Canal $channelId"
        val img = card.selectFirst("img")?.let { img ->
            img.attr("src").ifEmpty { img.attr("data-src") }
        } ?: "https://embedtv.best/assets/icon.png"

        val time = card.selectFirst("span")?.text()?.trim()
        
        // Título diferente dependendo de onde veio o clique
        val displayTitle = if (isFromJogos && !time.isNullOrBlank()) {
            // Veio da seção "Jogos de Hoje" - mostra o JOGO
            val gameName = card.selectFirst("h3")?.text()?.trim() ?: channelName
            "$gameName ($time)"
        } else {
            // Veio da lista normal de canais - mostra o CANAL
            channelName
        }
        
        // Imagem diferente dependendo de onde veio o clique
        val displayImage = if (isFromJogos && !time.isNullOrBlank()) {
            // Para jogos, usa a imagem do jogo (que está no data-src do card)
            val imgElement = card.selectFirst("img")
            imgElement?.attr("data-src")?.ifEmpty { imgElement.attr("src") } ?: img
        } else {
            // Para canais normais, usa a imagem do canal
            img
        }
        
        // Descrição diferente dependendo de onde veio o clique
        val plot = if (isFromJogos && !time.isNullOrBlank()) {
            val gameName = card.selectFirst("h3")?.text()?.trim() ?: channelName
            "📺 $channelName\n⚽ $gameName\n🕐 $time"
        } else {
            "Assista $channelName ao vivo no EmbedTv"
        }

        return newMovieLoadResponse(
            displayTitle,
            cleanUrl,
            TvType.Live,
            cleanUrl
        ) {
            this.posterUrl = fixImageUrl(displayImage)
            this.plot = plot
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val doc = app.get(mainSite).document
        val allCards = doc.select(".card")
        val results = mutableListOf<SearchResponse>()

        for (card in allCards) {
            val channelId = card.attr("data-channel")
            if (channelId.isBlank() || channelId in blockedChannels) continue

            val nameElement = card.selectFirst("h3") ?: continue
            val channelName = nameElement.text().trim()

            if (!channelName.contains(query, ignoreCase = true)) continue

            val imgElement = card.selectFirst("img") ?: continue
            val imageUrl = imgElement.attr("src").ifEmpty { imgElement.attr("data-src") }

            val timeElement = card.selectFirst("span")
            val hasHorario = timeElement != null

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

        return results
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val cleanUrl = data.split("?")[0]
        val channelUrl = cleanUrl.ifEmpty { return false }

        return try {
            val headers = mapOf(
                "Referer" to baseUrl,
                "Origin" to baseUrl,
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36"
            )
            
            // Baixa o HTML para extrair a URL do stream
            val html = app.get(channelUrl, headers = headers).text
            
            // Extrai a URL do .txt do objeto data.stream
            val streamPattern = Regex("""stream:\s*"([^"]+\.txt)"""")
            val streamMatch = streamPattern.find(html)
            
            if (streamMatch != null) {
                var streamUrl = streamMatch.groupValues[1]
                
                // Remove o xn-- do domínio para o OkHttp aceitar
                streamUrl = streamUrl.replace("xn--d1ma04s8hp12.cloudfront.lat", "d1ma04s8hp12.cloudfront.lat")
                
                val streamHeaders = mapOf(
                    "Accept" to "*/*",
                    "Accept-Language" to "pt-BR,pt;q=0.9",
                    "Connection" to "keep-alive",
                    "Sec-Fetch-Dest" to "empty",
                    "Sec-Fetch-Mode" to "cors",
                    "Sec-Fetch-Site" to "cross-site",
                    "Referer" to baseUrl,
                    "Origin" to baseUrl,
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36"
                )
                
                M3u8Helper.generateM3u8(
                    name,
                    streamUrl,
                    baseUrl,
                    headers = streamHeaders
                ).forEach(callback)
                
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }
}
