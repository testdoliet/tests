package com.embedtv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import org.jsoup.nodes.Element

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

    override val mainPage = mainPageOf(
        "$mainSite/" to "Canais"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val homePageList = mutableListOf<HomePageList>()

        try {
            val document = app.get(mainSite).document
            
            // Seção de Jogos de Hoje
            val jogosSection = document.selectFirst(".session.futebol")
            if (jogosSection != null) {
                val jogosList = mutableListOf<SearchResponse>()
                val jogosCards = jogosSection.select(".card")
                
                for (card in jogosCards) {
                    val channelId = card.attr("data-channel")
                    if (channelId.isBlank()) continue
                    
                    val nameElement = card.selectFirst("h3")
                    val channelName = nameElement?.text()
                    if (channelName.isNullOrBlank()) continue
                    
                    val imgElement = card.selectFirst("img")
                    val imgSrc = imgElement?.attr("src")
                    val imgDataSrc = imgElement?.attr("data-src")
                    val imageUrl = if (!imgSrc.isNullOrBlank()) imgSrc else imgDataSrc ?: ""
                    
                    val timeElement = card.selectFirst("span")
                    val time = timeElement?.text() ?: ""
                    
                    val displayName = if (time.isNotBlank()) "$channelName ($time)" else channelName
                    
                    jogosList.add(
                        newLiveSearchResponse(
                            displayName,
                            "$baseUrl/$channelId",
                            TvType.Live
                        ) {
                            this.posterUrl = fixUrl(imageUrl)
                        }
                    )
                }
                
                if (jogosList.isNotEmpty()) {
                    homePageList.add(HomePageList("📺 Jogos de Hoje", jogosList))
                }
            }
            
            // Todas as categorias
            val categories = document.select(".categorie")
            
            for (category in categories) {
                val titleElement = category.selectFirst(".title")
                val categoryTitle = titleElement?.text()
                if (categoryTitle.isNullOrBlank()) continue
                
                val channelList = mutableListOf<SearchResponse>()
                val cards = category.select(".card")
                
                for (card in cards) {
                    val channelId = card.attr("data-channel")
                    if (channelId.isBlank()) continue
                    
                    val nameElement = card.selectFirst("h3")
                    val channelName = nameElement?.text()
                    if (channelName.isNullOrBlank()) continue
                    
                    val imgElement = card.selectFirst("img")
                    val imgSrc = imgElement?.attr("src")
                    val imgDataSrc = imgElement?.attr("data-src")
                    val imageUrl = if (!imgSrc.isNullOrBlank()) imgSrc else imgDataSrc ?: ""
                    
                    channelList.add(
                        newLiveSearchResponse(
                            channelName,
                            "$baseUrl/$channelId",
                            TvType.Live
                        ) {
                            this.posterUrl = fixUrl(imageUrl)
                        }
                    )
                }
                
                if (channelList.isNotEmpty()) {
                    homePageList.add(HomePageList(categoryTitle, channelList))
                }
            }
            
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (homePageList.isEmpty()) {
            throw ErrorLoadingException("Nenhum canal encontrado.")
        }
        
        return HomePageResponse(homePageList)
    }

    override suspend fun load(url: String): LoadResponse {
        val channelId = url.substringAfterLast("/")
        
        var channelName = "Canal $channelId"
        var channelImage = "https://embedtv.best/assets/icon.png"
        
        try {
            val document = app.get(mainSite).document
            val allCards = document.select(".card[data-channel=\"$channelId\"]")
            
            if (allCards.isNotEmpty()) {
                val card = allCards.first()
                
                // Nome do canal
                val nameElement = card.selectFirst("h3")
                val name = nameElement?.text()
                if (!name.isNullOrBlank()) {
                    channelName = name
                }
                
                // Imagem do canal
                val imgElement = card.selectFirst("img")
                if (imgElement != null) {
                    val src = imgElement.attr("src")
                    val dataSrc = imgElement.attr("data-src")
                    val image = if (src.isNotBlank()) src else dataSrc
                    if (image.isNotBlank()) {
                        channelImage = image
                    }
                }
                
                // Horário do jogo
                val timeElement = card.selectFirst("span")
                if (timeElement != null) {
                    val time = timeElement.text()
                    if (time.isNotBlank()) {
                        channelName = "$channelName ($time)"
                    }
                }
            }
        } catch (e: Exception) {
            // Ignora
        }

        return newMovieLoadResponse(
            channelName,
            url,
            TvType.Live,
            url
        ) {
            this.posterUrl = fixUrl(channelImage)
            this.plot = "Assista $channelName ao vivo no EmbedTv"
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val results = mutableListOf<SearchResponse>()
        
        try {
            val document = app.get(mainSite).document
            val allCards = document.select(".card")
            
            for (card in allCards) {
                val channelId = card.attr("data-channel")
                if (channelId.isBlank()) continue
                
                val nameElement = card.selectFirst("h3")
                val channelName = nameElement?.text()
                if (channelName.isNullOrBlank()) continue
                
                if (!channelName.contains(query, ignoreCase = true)) continue
                
                val imgElement = card.selectFirst("img")
                val imgSrc = imgElement?.attr("src")
                val imgDataSrc = imgElement?.attr("data-src")
                val imageUrl = if (!imgSrc.isNullOrBlank()) imgSrc else imgDataSrc ?: ""
                
                val timeElement = card.selectFirst("span")
                val time = timeElement?.text() ?: ""
                
                val displayName = if (time.isNotBlank()) "$channelName ($time)" else channelName
                
                results.add(
                    newLiveSearchResponse(
                        displayName,
                        "$baseUrl/$channelId",
                        TvType.Live
                    ) {
                        this.posterUrl = fixUrl(imageUrl)
                    }
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }

        return results
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
                "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
                "Upgrade-Insecure-Requests" to "1"
            )

            val response = app.get(data, headers = headers)
            val html = response.text
            
            // Procura data.channel.stream
            val dataPattern = Regex("""data\.channel\.stream\s*=\s*"([^"]+\.m3u8[^"]*)""")
            val dataMatch = dataPattern.find(html)
            
            val videoUrl = dataMatch?.groupValues?.get(1) ?: run {
                // Procura link direto .m3u8
                val directPattern = Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""")
                directPattern.find(html)?.groupValues?.get(1)
            }
            
            if (videoUrl != null) {
                val videoHeaders = mapOf(
                    "Referer" to baseUrl,
                    "Origin" to baseUrl,
                    "User-Agent" to headers["User-Agent"]!!
                )
                
                callback.invoke(
                    newExtractorLink("EmbedTv", "EmbedTv [HLS]", videoUrl) {
                        this.referer = baseUrl
                        this.type = ExtractorLinkType.M3U8
                        this.headers = videoHeaders
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            }
            
            return false

        } catch (e: Exception) {
            return false
        }
    }

    private fun fixUrl(url: String): String {
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("http") -> url
            url.startsWith("/") -> "$mainSite$url"
            else -> url
        }
    }
}
