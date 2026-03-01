package com.TopAnimes

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.jsoup.Jsoup
import kotlinx.coroutines.delay
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import android.util.Base64
import java.util.*

object UnifiedExtractor {
    
    // Mapa de itag para qualidade (Blogger/Goyabu)
    private val itagQualityMap = mapOf(
        18 to 360, 22 to 720, 37 to 1080, 59 to 480,
        133 to 240, 134 to 360, 135 to 480, 136 to 720,
        137 to 1080, 160 to 144, 242 to 240, 243 to 360,
        244 to 480, 247 to 720, 248 to 1080
    )

    /**
     * Método principal que tenta todos os extratores em sequência
     */
    suspend fun extractVideoLinks(
        episodeUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("=" * 60)
        println("🚀 UNIFIED EXTRACTOR INICIADO")
        println("📌 Episódio: $name")
        println("🔗 URL: $episodeUrl")
        println("=" * 60)
        
        // Lista de extratores em ordem de tentativa
        val extractors = listOf(
            "AniVideo (Player 1)" to { extractAniVideo(episodeUrl, name, callback) },
            "Blogger (Player 2)" to { extractBlogger(episodeUrl, name, callback) },
            "OdaCDN (/antivirus2/)" to { extractOdaCDN(episodeUrl, name, callback) },
            "ZuPlay (/antivirus3/)" to { extractZuPlay(episodeUrl, name, callback) },
            "FileMoon (filemoon.sx)" to { extractFileMoon(episodeUrl, name, callback) },
            "ChPlay Original (Fallback)" to { extractChPlayOriginal(episodeUrl, name, callback) }
        )
        
        for ((extractorName, extractorFunction) in extractors) {
            println("\n" + "-" * 40)
            println("🔄 Tentando: $extractorName")
            println("-" * 40)
            
            try {
                val success = extractorFunction.invoke()
                if (success) {
                    println("✅ SUCESSO com: $extractorName")
                    return true
                }
            } catch (e: Exception) {
                println("⚠️ Erro em $extractorName: ${e.message}")
            }
            
            println("❌ Falhou: $extractorName")
        }
        
        println("\n" + "=" * 60)
        println("❌ TODOS OS EXTRATORES FALHARAM")
        println("=" * 60)
        return false
    }
    
    // ==================== ANIVIDEO EXTRACTOR (Player 1) ====================
    private suspend fun extractAniVideo(
        episodeUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🎬 ANIVIDEO EXTRACTOR - Player 1 (Direto)")
        
        val response = app.get(
            episodeUrl,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Referer" to "https://topanimes.net/"
            )
        )

        val html = response.text
        val doc = Jsoup.parse(html)
        
        // Procura o iframe do player 1
        val iframe = doc.selectFirst("#source-player-1 iframe, div#source-player-1 iframe, .source-box iframe")
        
        if (iframe == null) {
            println("❌ Iframe do player 1 não encontrado")
            return false
        }
        
        val iframeSrc = iframe.attr("src")
        println("✅ Iframe encontrado: $iframeSrc")
        
        // Extrai o parâmetro 'd' da URL
        val pattern = """[?&]d=([^&]+)""".toRegex()
        val match = pattern.find(iframeSrc) ?: return false
        
        val encodedVideoUrl = match.groupValues[1]
        val videoUrl = java.net.URLDecoder.decode(encodedVideoUrl, "UTF-8")
        println("🎯 URL do vídeo: ${videoUrl.take(100)}...")
        
        val headers = mapOf(
            "Referer" to "https://topanimes.net",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "Accept" to "*/*",
            "Connection" to "keep-alive"
        )
        
        val links = M3u8Helper.generateM3u8(
            source = "AniVideo",
            streamUrl = videoUrl,
            referer = "https://topanimes.net",
            headers = headers
        )
        
        var count = 0
        links.forEach { link ->
            callback.invoke(link)
            count++
            println("✅ Link $count: ${link.quality}p")
        }
        
        return count > 0
    }
    
    // ==================== BLOGGER EXTRACTOR (Player 2) ====================
    private suspend fun extractBlogger(
        episodeUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("📺 BLOGGER EXTRACTOR - Player 2 (Complexo)")
        
        val response = app.get(
            episodeUrl,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Referer" to "https://topanimes.net/"
            )
        )

        val html = response.text
        val doc = Jsoup.parse(html)
        
        // 1. Extrai o token do Blogger
        val token = extractBloggerToken(doc) ?: return false
        println("✅ Token encontrado: $token")
        
        // 2. Acessa a URL do blogger
        val bloggerUrl = "https://www.blogger.com/video.g?token=$token"
        
        val bloggerResponse = app.get(
            bloggerUrl,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Referer" to "https://topanimes.net/"
            )
        )
        
        val bloggerHtml = bloggerResponse.text
        
        // 3. Extrai parâmetros da página
        val (f_sid, bl) = extractBloggerParams(bloggerHtml)
        println("✅ Parâmetros: f_sid=$f_sid, bl=$bl")
        
        delay(500)
        
        // 4. Chama a API batch
        val videos = callBloggerBatchApi(token, f_sid, bl)
        
        if (videos.isEmpty()) {
            println("❌ Nenhum vídeo encontrado na API")
            return false
        }
        
        println("✅ ${videos.size} vídeo(s) encontrado(s)")
        
        // 5. Processa cada vídeo
        videos.forEach { (videoUrl, itag) ->
            val quality = itagQualityMap[itag] ?: 360
            val qualityLabel = when(quality) {
                1080 -> "FHD"
                720 -> "HD"
                480 -> "SD"
                else -> "SD"
            }
            
            val urlDecodificada = decodeUrl(videoUrl)
            val urlLimpa = urlDecodificada.replace("\\&", "&")
            val urlFinal = if (urlLimpa.endsWith("\\")) {
                urlLimpa.dropLast(1)
            } else {
                urlLimpa
            }
            
            callback(
                newExtractorLink(
                    source = "Blogger",
                    name = "$name ($qualityLabel)",
                    url = urlFinal,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = "https://www.blogger.com/"
                    this.quality = quality
                    this.headers = mapOf(
                        "Referer" to "https://www.blogger.com/",
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                    )
                }
            )
            println("✅ Link adicionado: $qualityLabel")
        }
        
        return true
    }
    
    // ==================== ODACDN EXTRACTOR (/antivirus2/) ====================
    private suspend fun extractOdaCDN(
        episodeUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔍 ODACDN EXTRACTOR - /antivirus2/")
        
        val episodeResponse = app.get(episodeUrl)
        val html = episodeResponse.text
        
        // Procura URL do player ODACDN
        var playerUrl: String? = null
        
        val odaPatterns = listOf(
            """https?://[^"\s']*/antivirus2/[^"\s']*""".toRegex(),
            """['"]https?://[^'"]*/antivirus2/[^'"]*['"]""".toRegex(),
            """src=['"][^'"]*/antivirus2/[^'"]*['"]""".toRegex()
        )
        
        for (pattern in odaPatterns) {
            val matches = pattern.findAll(html)
            for (match in matches) {
                var foundUrl = match.value
                foundUrl = foundUrl.replace("'", "").replace("\"", "").replace("src=", "")
                
                if (foundUrl.contains("/antivirus2/")) {
                    println("🎯 URL ODACDN: $foundUrl")
                    playerUrl = foundUrl
                    break
                }
            }
            if (playerUrl != null) break
        }
        
        if (playerUrl == null) return false
        
        val finalPlayerUrl = when {
            playerUrl.startsWith("http") -> playerUrl
            playerUrl.startsWith("//") -> "https:$playerUrl"
            playerUrl.startsWith("/") -> "https://topanimes.net$playerUrl"
            playerUrl.startsWith("antivirus2") -> "https://topanimes.net/$playerUrl"
            else -> playerUrl
        }
        
        println("🎮 Player: $finalPlayerUrl")
        
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "Referer" to episodeUrl
        )
        
        val playerResponse = app.get(finalPlayerUrl, headers = headers, timeout = 30)
        val playerHtml = playerResponse.text
        
        val videoLink = extractM3U8FromPlayer(playerHtml)
        
        if (videoLink == null) {
            println("❌ Nenhum link M3U8 encontrado")
            return false
        }
        
        println("🎬 M3U8: ${videoLink.take(100)}...")
        
        val quality = determineQuality(videoLink)
        val qualityLabel = getQualityLabel(quality)
        
        val extractorLink = newExtractorLink(
            source = "OdaCDN",
            name = "$name ($qualityLabel) [HLS]",
            url = videoLink,
            type = ExtractorLinkType.M3U8
        ) {
            this.referer = finalPlayerUrl
            this.quality = quality
            this.headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Referer" to finalPlayerUrl,
                "Origin" to "https://topanimes.net"
            )
        }
        
        callback(extractorLink)
        return true
    }
    
    // ==================== ZUPLAY EXTRACTOR (/antivirus3/) ====================
    private suspend fun extractZuPlay(
        episodeUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔍 ZUPLAY EXTRACTOR - /antivirus3/")
        
        val episodeResponse = app.get(episodeUrl)
        val html = episodeResponse.text
        
        var playerUrl: String? = null
        
        val zuplayPatterns = listOf(
            """https?://[^"\s']*/antivirus3/[^"\s']*""".toRegex(),
            """['"]https?://[^'"]*/antivirus3/[^'"]*['"]""".toRegex(),
            """src=['"][^'"]*/antivirus3/[^'"]*['"]""".toRegex()
        )
        
        for (pattern in zuplayPatterns) {
            val matches = pattern.findAll(html)
            for (match in matches) {
                var foundUrl = match.value
                foundUrl = foundUrl.replace("'", "").replace("\"", "").replace("src=", "")
                
                if (foundUrl.contains("/antivirus3/")) {
                    println("🎯 URL ZUPLAY: $foundUrl")
                    playerUrl = foundUrl
                    break
                }
            }
            if (playerUrl != null) break
        }
        
        if (playerUrl == null) return false
        
        val finalPlayerUrl = when {
            playerUrl.startsWith("http") -> playerUrl
            playerUrl.startsWith("//") -> "https:$playerUrl"
            playerUrl.startsWith("/") -> "https://topanimes.net$playerUrl"
            playerUrl.startsWith("antivirus3") -> "https://topanimes.net/$playerUrl"
            else -> playerUrl
        }
        
        println("🎮 Player: $finalPlayerUrl")
        
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "Referer" to episodeUrl
        )
        
        val playerResponse = app.get(finalPlayerUrl, headers = headers, timeout = 30)
        val playerHtml = playerResponse.text
        
        // Tenta M3U8 primeiro
        var videoLink = extractM3U8FromPlayer(playerHtml)
        
        // Se não achou M3U8, tenta MP4
        if (videoLink == null) {
            videoLink = extractMP4FromPlayer(playerHtml)
        }
        
        if (videoLink == null) return false
        
        println("🎬 Vídeo: ${videoLink.take(100)}...")
        
        val quality = determineQuality(videoLink)
        val qualityLabel = getQualityLabel(quality)
        val isM3U8 = videoLink.contains(".m3u8")
        
        val extractorLink = newExtractorLink(
            source = "ZUPLAY",
            name = "$name ($qualityLabel) [${if (isM3U8) "HLS" else "MP4"}]",
            url = videoLink,
            type = if (isM3U8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        ) {
            this.referer = finalPlayerUrl
            this.quality = quality
            this.headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Referer" to finalPlayerUrl,
                "Origin" to "https://topanimes.net"
            )
        }
        
        callback(extractorLink)
        return true
    }
    
    // ==================== FILEMOON EXTRACTOR ====================
    private suspend fun extractFileMoon(
        episodeUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔍 FILEMOON EXTRACTOR")
        
        val episodeResponse = app.get(episodeUrl)
        val html = episodeResponse.text
        
        // Procura URL do filemoon
        var filemoonUrl: String? = null
        
        val filemoonPattern = """https?://[^"\s']*filemoon\.sx/e/[^"\s']*""".toRegex()
        val matches = filemoonPattern.findAll(html)
        
        matches.forEach { match ->
            val foundUrl = match.value
            if (foundUrl.contains("filemoon.sx/e/")) {
                println("🎯 URL FILEMOON: $foundUrl")
                filemoonUrl = foundUrl
            }
        }
        
        if (filemoonUrl == null) return false
        
        // Extrai ID do vídeo
        val videoId = extractFileMoonVideoId(filemoonUrl!!)
        if (videoId.isEmpty()) return false
        
        println("🆔 ID: $videoId")
        
        // Faz requisição para API
        val apiUrl = "https://9n8o.com/api/videos/$videoId/embed/playback"
        
        val headers = mapOf(
            "accept" to "*/*",
            "content-type" to "application/json",
            "origin" to "https://9n8o.com",
            "referer" to filemoonUrl!!,
            "user-agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
            "x-embed-parent" to filemoonUrl!!
        )
        
        // Gera fingerprint
        val fingerprint = generateFileMoonFingerprint()
        val requestBody = mapOf(
            "fingerprint" to mapOf(
                "token" to fingerprint.first,
                "viewer_id" to fingerprint.second,
                "device_id" to fingerprint.third,
                "confidence" to 0.91
            )
        )
        
        val apiResponse = app.post(
            apiUrl, 
            headers = headers, 
            json = requestBody,
            timeout = 30
        )
        
        if (apiResponse.code != 200) return false
        
        val responseText = apiResponse.text
        
        // Processa resposta (descriptografa)
        val m3u8Url = processFileMoonResponse(responseText)
        if (m3u8Url == null) return false
        
        println("🎬 M3U8: ${m3u8Url.take(100)}...")
        
        createFileMoonLink(m3u8Url, name, filemoonUrl!!, callback)
        return true
    }
    
    // ==================== CHPLAY ORIGINAL (FALLBACK) ====================
    private suspend fun extractChPlayOriginal(
        episodeUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("🔍 CHPLAY ORIGINAL - Fallback (WebView)")
        
        val response = app.get(episodeUrl)
        val html = response.text
        
        val iframePattern = Regex("""id=["']source-player-1["'][^>]*>.*?<iframe[^>]*src=["']([^"']*)["']""", RegexOption.DOT_MATCHES_ALL)
        val iframeMatch = iframePattern.find(html) ?: return false
        
        var iframeUrl = iframeMatch.groupValues[1]
        
        if (iframeUrl.contains("/aviso/?url=")) {
            val urlParamPattern = Regex("""url=([^&]*)""")
            val urlParamMatch = urlParamPattern.find(iframeUrl)
            
            if (urlParamMatch != null) {
                val encodedUrl = urlParamMatch.groupValues[1]
                iframeUrl = java.net.URLDecoder.decode(encodedUrl, "UTF-8")
            }
        }
        
        val finalUrl = when {
            iframeUrl.startsWith("//") -> "https:$iframeUrl"
            iframeUrl.startsWith("/") -> "https://topanimes.net$iframeUrl"
            iframeUrl.startsWith("http") -> iframeUrl
            else -> "https://$iframeUrl"
        }
        
        println("🎯 URL do player: $finalUrl")
        
        val cfMasterPattern = Regex(""".*cf-master.*""")
        
        val resolver = WebViewResolver(
            interceptUrl = cfMasterPattern,
            additionalUrls = listOf(cfMasterPattern),
            useOkhttp = false,
            timeout = 15_000L
        )
        
        val result = app.get(finalUrl, interceptor = resolver)
        val interceptedUrl = result.url
        
        if (interceptedUrl.isNotEmpty() && interceptedUrl != finalUrl && interceptedUrl.contains("cf-master")) {
            println("✅ CF-MASTER encontrado")
            return processCfMaster(interceptedUrl, finalUrl, name, callback)
        }
        
        return false
    }
    
    private suspend fun processCfMaster(
        cfMasterUrl: String,
        referer: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val headers = mapOf(
            "Accept" to "*/*",
            "Referer" to referer,
            "Origin" to "https://png.strp2p.com",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        )
        
        val links = M3u8Helper.generateM3u8(
            source = "ChPlay",
            streamUrl = cfMasterUrl,
            referer = "https://topanimes.net",
            headers = headers
        )
        
        links.forEach(callback)
        return links.isNotEmpty()
    }
    
    // ==================== FUNÇÕES AUXILIARES ====================
    
    // Blogger Helper Functions
    private fun extractBloggerToken(doc: org.jsoup.nodes.Document): String? {
        val iframe = doc.selectFirst("iframe[src*='blogger.com/video.g']")
        if (iframe != null) {
            val src = iframe.attr("src")
            val pattern = """token=([a-zA-Z0-9_\-]+)""".toRegex()
            val match = pattern.find(src)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        
        val pattern = """video\.g\?token=([a-zA-Z0-9_\-]+)""".toRegex()
        doc.select("script").forEach { script ->
            pattern.find(script.html())?.let {
                return it.groupValues[1]
            }
        }
        
        return null
    }
    
    private fun extractBloggerParams(html: String): Pair<String, String> {
        var f_sid = "-7535563745894756252"
        var bl = "boq_bloggeruiserver_20260223.02_p0"
        
        val wizPattern = """window\.WIZ_global_data\s*=\s*\{([^}]+)\}""".toRegex()
        val wizMatch = wizPattern.find(html)
        
        if (wizMatch != null) {
            val wizData = wizMatch.groupValues[1]
            
            val sidPattern = """"FdrFJe":"([^"]+)"""".toRegex()
            sidPattern.find(wizData)?.let {
                f_sid = it.groupValues[1]
            }
            
            val blPattern = """"cfb2h":"([^"]+)"""".toRegex()
            blPattern.find(wizData)?.let {
                bl = it.groupValues[1]
            }
        }
        
        return Pair(f_sid, bl)
    }
    
    private suspend fun callBloggerBatchApi(
        token: String,
        f_sid: String,
        bl: String
    ): List<Pair<String, Int>> {
        
        val apiUrl = "https://www.blogger.com/_/BloggerVideoPlayerUi/data/batchexecute"
        val reqid = (10000..99999).random()
        
        val headers = mapOf(
            "authority" to "www.blogger.com",
            "accept" to "*/*",
            "content-type" to "application/x-www-form-urlencoded;charset=UTF-8",
            "origin" to "https://www.blogger.com",
            "referer" to "https://www.blogger.com/",
            "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
        )
        
        val urlWithParams = "$apiUrl?rpcids=WcwnYd&source-path=%2Fvideo.g&f.sid=$f_sid&bl=$bl&hl=pt-BR&_reqid=$reqid&rt=c"
        
        val body = mapOf(
            "f.req" to "%5B%5B%5B%22WcwnYd%22%2C%22%5B%5C%22$token%5C%22%2C%5C%22%5C%22%2C0%5D%22%2Cnull%2C%22generic%22%5D%5D%5D"
        )
        
        val response = app.post(
            url = urlWithParams,
            headers = headers,
            data = body
        )
        
        return extractBloggerVideoUrls(response.text)
    }
    
    private fun extractBloggerVideoUrls(response: String): List<Pair<String, Int>> {
        val videos = mutableListOf<Pair<String, Int>>()
        
        val jsonData = extractGoogleJson(response)
        val sources = listOf(response, jsonData).distinct()
        
        val patterns = listOf(
            """https?:\\/\\/[^"\\]+?\.googlevideo\.com\\/[^"\\]+?videoplayback[^"\\]*""".toRegex(),
            """https?://[^"'\s]+?\.googlevideo\.com/[^"'\s]+?videoplayback[^"'\s]*""".toRegex()
        )
        
        for (source in sources) {
            for (pattern in patterns) {
                val matches = pattern.findAll(source)
                for (match in matches) {
                    var url = match.value
                    if (!url.startsWith("http")) {
                        url = "https://$url"
                    }
                    url = decodeUrl(url)
                    val itag = extractItagFromUrl(url)
                    if (!videos.any { it.first == url }) {
                        videos.add(Pair(url, itag))
                    }
                }
            }
        }
        
        val qualityOrder = listOf(37, 22, 18, 59)
        return videos
            .distinctBy { it.second }
            .sortedBy { qualityOrder.indexOf(it.second) }
    }
    
    private fun extractGoogleJson(response: String): String {
        try {
            var data = response.replace(Regex("""^\)\]\}'\s*\n?"""), "")
            val pattern = """\[\s*\[\s*"wrb\.fr"\s*,\s*"[^"]*"\s*,\s*"(.+?)"\s*\]""".toRegex(RegexOption.DOT_MATCHES_ALL)
            val match = pattern.find(data)
            
            if (match != null) {
                var jsonStr = match.groupValues[1]
                jsonStr = jsonStr.replace("\\\"", "\"")
                jsonStr = jsonStr.replace("\\\\", "\\")
                jsonStr = decodeUnicodeEscapes(jsonStr)
                return jsonStr
            }
        } catch (e: Exception) {
        }
        return response
    }
    
    // OdaCDN/ZuPlay Helper Functions
    private fun extractM3U8FromPlayer(html: String): String? {
        val patterns = listOf(
            """"file"\s*:\s*"([^"]+)"""".toRegex(),
            """sources\s*:\s*\[\{[^}]*"file"\s*:\s*"([^"]+)"""".toRegex(RegexOption.DOT_MATCHES_ALL),
            """https?://[^"\s]*\.m3u8[^"\s]*""".toRegex(),
            """data-file=["']([^"']+)["']""".toRegex()
        )
        
        patterns.forEach { pattern ->
            val matches = pattern.findAll(html)
            matches.forEach { match ->
                var url = match.groupValues.getOrNull(1) ?: match.value
                url = url.replace("\\/", "/").replace("&amp;", "&")
                if (url.contains(".m3u8")) {
                    return url
                }
            }
        }
        return null
    }
    
    private fun extractMP4FromPlayer(html: String): String? {
        val patterns = listOf(
            """"file"\s*:\s*"([^"]+)"""".toRegex(),
            """https?://[^"\s]*\.mp4[^"\s]*""".toRegex(),
            """data-file=["']([^"']+)["']""".toRegex(),
            """<source[^>]*src=["']([^"']+)["'][^>]*>""".toRegex()
        )
        
        patterns.forEach { pattern ->
            val matches = pattern.findAll(html)
            matches.forEach { match ->
                var url = match.groupValues.getOrNull(1) ?: match.value
                url = url.replace("\\/", "/").replace("&amp;", "&")
                if (url.contains(".mp4") || url.contains("googlevideo")) {
                    return url
                }
            }
        }
        return null
    }
    
    private fun determineQuality(url: String): Int {
        return when {
            url.contains("1080") || url.contains("fhd") -> 1080
            url.contains("720") || url.contains("hd") -> 720
            url.contains("480") -> 480
            url.contains("360") -> 360
            else -> 720
        }
    }
    
    private fun getQualityLabel(quality: Int): String {
        return when {
            quality >= 1080 -> "FHD"
            quality >= 720 -> "HD"
            else -> "SD"
        }
    }
    
    // FileMoon Helper Functions
    private fun extractFileMoonVideoId(url: String): String {
        val pattern = """filemoon\.sx/e/([a-zA-Z0-9]+)""".toRegex()
        val match = pattern.find(url)
        return match?.groupValues?.get(1) ?: ""
    }
    
    private fun generateFileMoonFingerprint(): Triple<String, String, String> {
        val viewerId = UUID.randomUUID().toString().replace("-", "").take(32)
        val deviceId = UUID.randomUUID().toString().replace("-", "").take(32)
        val token = Base64.encodeToString(
            """{"viewer_id":"$viewerId","device_id":"$deviceId","confidence":0.91}""".toByteArray(),
            Base64.URL_SAFE or Base64.NO_PADDING
        )
        return Triple(token, viewerId, deviceId)
    }
    
    private fun processFileMoonResponse(jsonText: String): String? {
        return try {
            val json = JSONObject(jsonText)
            val playback = json.getJSONObject("playback")
            
            val iv = decodeBase64(playback.getString("iv"))
            val payload = decodeBase64(playback.getString("payload"))
            val keyParts = playback.getJSONArray("key_parts")
            val key1 = decodeBase64(keyParts.getString(0))
            val key2 = decodeBase64(keyParts.getString(1))
            val key = key1 + key2
            
            val decrypted = decryptAesGcm(payload, key, iv) ?: return null
            val decryptedText = String(decrypted, Charsets.UTF_8)
            
            val decryptedJson = JSONObject(decryptedText)
            
            if (decryptedJson.has("sources")) {
                val sources = decryptedJson.getJSONArray("sources")
                if (sources.length() > 0) {
                    val source = sources.getJSONObject(0)
                    return source.getString("url")
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }
    
    private fun decodeBase64(base64Str: String): ByteArray {
        val cleanStr = base64Str.trim()
        if (cleanStr.contains('-') || cleanStr.contains('_')) {
            return Base64.decode(cleanStr, Base64.URL_SAFE or Base64.NO_PADDING)
        }
        try {
            return Base64.decode(cleanStr, Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            return Base64.decode(cleanStr, Base64.NO_PADDING)
        }
    }
    
    private fun decryptAesGcm(ciphertext: ByteArray, key: ByteArray, iv: ByteArray): ByteArray? {
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val actualIv = if (iv.size != 12) iv.copyOf(12) else iv
            val spec = GCMParameterSpec(128, actualIv)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), spec)
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            null
        }
    }
    
    private suspend fun createFileMoonLink(
        m3u8Url: String,
        name: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
            "Referer" to referer,
            "Origin" to "https://filemoon.sx"
        )
        
        try {
            val links = M3u8Helper.generateM3u8(
                source = "FileMoon",
                streamUrl = m3u8Url,
                referer = referer,
                headers = headers
            )
            
            if (links.isNotEmpty()) {
                links.forEach { callback(it) }
                return
            }
        } catch (e: Exception) {
        }
        
        val extractorLink = newExtractorLink(
            source = "FileMoon",
            name = "$name [HLS]",
            url = m3u8Url,
            type = ExtractorLinkType.M3U8
        ) {
            this.referer = referer
            this.quality = 720
            this.headers = headers
        }
        
        callback(extractorLink)
    }
    
    // URL Helper Functions
    private fun decodeUrl(url: String): String {
        var decoded = url
        decoded = decodeUnicodeEscapes(decoded)
        decoded = decoded.replace("\\/", "/")
        decoded = decoded.replace("\\\\", "\\")
        decoded = decoded.replace("\\=", "=")
        decoded = decoded.replace("\\&", "&")
        if (decoded.endsWith("\\")) {
            decoded = decoded.dropLast(1)
        }
        decoded = decoded.trim('"')
        return decoded
    }
    
    private fun decodeUnicodeEscapes(text: String): String {
        var result = text
        val pattern = """\\u([0-9a-fA-F]{4})""".toRegex()
        result = pattern.replace(result) { matchResult ->
            val hexCode = matchResult.groupValues[1]
            try {
                hexCode.toInt(16).toChar().toString()
            } catch (e: Exception) {
                "?"
            }
        }
        return result
    }
    
    private fun extractItagFromUrl(url: String): Int {
        val patterns = listOf(
            """itag[=?&](\d+)""".toRegex(),
            """itag%3D(\d+)""".toRegex(),
            """itag\\u003d(\d+)""".toRegex()
        )
        
        for (pattern in patterns) {
            pattern.find(url)?.let {
                return it.groupValues[1].toIntOrNull() ?: 18
            }
        }
        
        return when {
            "itag=22" in url || "itag%3D22" in url -> 22
            "itag=18" in url || "itag%3D18" in url -> 18
            "itag=37" in url || "itag%3D37" in url -> 37
            else -> 18
        }
    }
}
