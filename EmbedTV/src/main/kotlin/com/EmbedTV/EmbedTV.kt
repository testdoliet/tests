package com.EmbedTV

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.network.WebViewResolver
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
    private val decryptionKey = "embedtv@123"
    private val blockedChannels = listOf("sexyhot", "playboy")

    private fun fixImageUrl(url: String): String {
        println("🖼️ fixImageUrl: $url")
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
        println("🔓 decryptStreamUrl: string length = ${encodedString.length}")
        return try {
            val decodedBytes = Base64.decode(encodedString, Base64.DEFAULT)
            println("📦 decoded bytes: ${decodedBytes.size}")
            
            val decodedString = String(decodedBytes, Charsets.ISO_8859_1)
            println("📝 decoded: $decodedString")
            
            val reversedString = decodedString.reversed()
            println("🔄 reversed: $reversedString")

            val keyBytes = decryptionKey.toByteArray(Charsets.ISO_8859_1)
            val inputBytes = reversedString.toByteArray(Charsets.ISO_8859_1)
            val outputStream = ByteArrayOutputStream()

            for (i in inputBytes.indices) {
                val keyByte = keyBytes[i % keyBytes.size]
                outputStream.write((inputBytes[i].toInt() xor keyByte.toInt()).toByte().toInt())
            }

            val result = outputStream.toString(Charsets.UTF_8.name())
            println("✅ decrypted: $result")
            result
        } catch (e: Exception) {
            println("❌ decryption failed: ${e.message}")
            null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        println("🏠 getMainPage called - page: $page")
        val doc = app.get(mainSite).document
        val allCategories = mutableListOf<HomePageList>()
        
        val jogosSection = doc.selectFirst(".session.futebol")
        if (jogosSection != null) {
            println("⚽ Jogos section found!")
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

                val jogoUrl = "$baseUrl/$channelId?source=jogos"
                println("🎮 Game found: $displayName - $channelId")

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

        val categories = doc.select(".categorie")
        println("📂 Categories found: ${categories.size}")

        for (category in categories) {
            val titleElement = category.selectFirst(".title") ?: continue
            val categoryTitle = titleElement.text().trim()
            println("📁 Category: $categoryTitle")

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
                println("📺 Channel: $channelName - $channelId")

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
            println("❌ No categories found!")
            throw ErrorLoadingException("Nenhum canal encontrado.")
        }

        println("✅ Returning ${allCategories.size} categories")
        return newHomePageResponse(allCategories, hasNext = false)
    }

    override suspend fun load(url: String): LoadResponse {
        println("📥 load: $url")
        val isFromJogos = url.contains("source=jogos")
        val cleanUrl = url.replace("?source=jogos", "")

        val channelId = cleanUrl.substringAfterLast("/")
        println("🔑 Channel ID: $channelId")
        
        if (channelId in blockedChannels) {
            println("🚫 Blocked channel: $channelId")
            throw ErrorLoadingException("Canal não disponível")
        }

        val mainPage = app.get(mainSite).document
        val card = mainPage.selectFirst(".card[data-channel=\"$channelId\"]")
        
        if (card == null) {
            println("⚠️ Card not found for channel: $channelId")
            return newMovieLoadResponse(
                "Canal $channelId",
                cleanUrl,
                TvType.Live,
                cleanUrl
            ) {
                this.posterUrl = "https://embedtv.best/assets/icon.png"
            }
        }

        val name = card.selectFirst("h3")?.text()?.trim() ?: "Canal $channelId"
        val img = card.selectFirst("img")?.let { img ->
            img.attr("src").ifEmpty { img.attr("data-src") }
        } ?: "https://embedtv.best/assets/icon.png"

        val time = card.selectFirst("span")?.text()?.trim()
        val displayName = if (!time.isNullOrBlank() && isFromJogos) "$name ($time)" else name
        println("✅ Loaded: $displayName")

        return newMovieLoadResponse(
            displayName,
            cleanUrl,
            TvType.Live,
            cleanUrl
        ) {
            this.posterUrl = fixImageUrl(img)
            this.plot = "Assista $displayName ao vivo no EmbedTv"
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        println("🔍 Searching for: $query")
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
            
            println("🔎 Found: $displayName - $channelId")

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

        println("✅ Search results: ${results.size}")
        return results
    }

    override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    println("🔗 loadLinks called")
    println("📡 Data: $data")
    
    val cleanUrl = data.split("?")[0]
    val channelUrl = cleanUrl.ifEmpty { 
        println("❌ Empty channel URL")
        return false 
    }
    
    println("🌐 Channel URL: $channelUrl")

    return try {
        // Headers para a requisição do HTML
        val headers = mapOf(
            "Referer" to baseUrl,
            "Origin" to baseUrl,
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36"
        )
        
        // 1. Baixa o HTML para extrair a URL do stream
        val html = app.get(channelUrl, headers = headers).text
        
        // Extrai a URL do stream do objeto data.stream
        val streamPattern = Regex("""stream:\s*"([^"]+\.txt)"""")
        val streamMatch = streamPattern.find(html)
        
        if (streamMatch != null) {
            val streamUrl = streamMatch.groupValues[1]
            println("✅ Found stream URL: $streamUrl")
            
            // 2. Agora usa WebView para CARREGAR o stream (não apenas capturar)
            // O WebView suporta domínios xn-- que o OkHttp não suporta
            val streamResolver = WebViewResolver(
                interceptUrl = Regex("""\.(ts|m3u8|m4s)$"""), // Intercepta segmentos de vídeo
                additionalUrls = listOf(Regex("""\.(ts|m3u8|m4s)$""")),
                useOkhttp = false,
                timeout = 60_000L
            )
            
            println("🔄 Loading stream URL in WebView...")
            
            // Carrega o stream no WebView - isso vai funcionar porque o WebView suporta xn--
            // O WebViewResolver vai interceptar os segmentos e nos dar a URL base
            val intercepted = app.get(streamUrl, interceptor = streamResolver).url
            
            println("🎯 Intercepted URL: $intercepted")
            
            // A URL interceptada pode ser o próprio stream ou um redirecionamento
            val finalStreamUrl = if (intercepted.isNotEmpty() && intercepted != streamUrl) {
                intercepted
            } else {
                streamUrl
            }
            
            println("✅ Final stream URL: $finalStreamUrl")
            
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
            
            // Tenta gerar o m3u8
            M3u8Helper.generateM3u8(
                name,
                finalStreamUrl,
                baseUrl,
                headers = streamHeaders
            ).forEach(callback)
            
            println("🎉 Success!")
            true
        } else {
            println("❌ Could not find stream URL in HTML")
            false
        }
    } catch (e: Exception) {
        println("💥 Exception: ${e.message}")
        e.printStackTrace()
        false
    }
}
                                  }
