package com.SuperFlix

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper

object SuperFlixExtractor {
    // Constantes para headers e configurações
    private const val WEBVIEW_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    
    // Domínios de anúncios para bloquear
    private val BLOCKED_DOMAINS = listOf(
        "doubleclick.net",
        "googleads.com",
        "adsystem.com",
        "adservice.google",
        "googlesyndication.com",
        "adsrvr.org",
        "adnxs.com",
        "casalemedia.com",
        "amazon-adsystem.com",
        "taboola.com",
        "outbrain.com",
        "onesignal.com",
        "scorecardresearch.com",
        "quantserve.com",
        "zedo.com",
        "exosrv.com",
        "rtbhouse.com",
        "criteo.com"
    )
    
    // Extensões de arquivos de mídia que queremos interceptar
    private val MEDIA_EXTENSIONS = listOf("m3u8", "mp4", "mkv", "mpd", "ts", "m4s", "m4v")
    
    // Extensões para bloquear (anúncios, scripts, etc)
    private val BLOCKED_EXTENSIONS = listOf("js", "css", "png", "jpg", "jpeg", "gif", "webp", "svg", "woff", "woff2", "ttf", "eot", "ico")
    
    suspend fun extractVideoLinks(
        url: String,
        mainUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // Headers específicos para bloquear ads e focar em vídeo
            val headers = mapOf(
                "Referer" to mainUrl,
                "Origin" to mainUrl,
                "User-Agent" to WEBVIEW_USER_AGENT,
                "DNT" to "1", // Do Not Track
                "Accept" to "video/*,*/*;q=0.8", // Prefere vídeo
                "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
                "Accept-Encoding" to "gzip, deflate, br",
                "Sec-Fetch-Dest" to "video",
                "Sec-Fetch-Mode" to "no-cors",
                "Sec-Fetch-Site" to "cross-site",
                "Upgrade-Insecure-Requests" to "1",
                "Cache-Control" to "no-cache",
                "Pragma" to "no-cache"
            )
            
            // Regex para interceptar URLs de vídeo
            val videoRegex = Regex("""\.(${MEDIA_EXTENSIONS.joinToString("|")})(\?[^#\s]*)?$""", RegexOption.IGNORE_CASE)
            
            val streamResolver = object : WebViewResolver(
                interceptUrl = videoRegex,
                useOkhttp = false,
                timeout = 12_000L, // Timeout aumentado um pouco
                maxRequests = 50, // Limite de requisições
                enableJavascript = true, // Pode ser necessário
                enableCookies = false // Desativa cookies para evitar tracking
            ) {
                private var requestCount = 0
                private val maxAllowedRequests = 60
                
                override fun shouldIntercept(url: String): Boolean {
                    requestCount++
                    
                    // Log para debug (remover em produção)
                    // println("[SuperFlix] Request #$requestCount: $url")
                    
                    // Bloqueia após muitas requisições
                    if (requestCount > maxAllowedRequests) {
                        // println("[SuperFlix] Bloqueado: Limite de $maxAllowedRequests requisições atingido")
                        return false
                    }
                    
                    // Verifica domínios bloqueados
                    val lowerUrl = url.lowercase()
                    if (BLOCKED_DOMAINS.any { lowerUrl.contains(it) }) {
                        // println("[SuperFlix] Bloqueado: Domínio de ad detectado")
                        return false
                    }
                    
                    // Verifica extensões bloqueadas
                    if (BLOCKED_EXTENSIONS.any { lowerUrl.endsWith(".$it") || lowerUrl.contains(".$it?") }) {
                        // println("[SuperFlix] Bloqueado: Extensão não-vídeo")
                        return false
                    }
                    
                    // Bloqueia URLs com padrões de anúncios
                    val adPatterns = listOf(
                        "/ads/", "/ad/", "/banner/", "/popup/", "/tracking/",
                        "adserver", "adsystem", "advertising", "analytics",
                        "pixel", "beacon", "tracker", "metrics"
                    )
                    
                    if (adPatterns.any { lowerUrl.contains(it) }) {
                        // println("[SuperFlix] Bloqueado: Padrão de ad detectado")
                        return false
                    }
                    
                    // Só intercepta URLs que contêm padrões de vídeo
                    if (videoRegex.containsMatchIn(url)) {
                        // println("[SuperFlix] Interceptando vídeo: $url")
                        return true
                    }
                    
                    return false
                }
                
                override fun onPageFinished(url: String?) {
                    // Opcional: Pode-se tentar injetar JavaScript para bloquear anúncios
                    // Mas isso é mais complexo e pode quebrar alguns players
                }
            }
            
            // Faz a requisição com headers anti-ads
            val response = app.get(url, headers = headers, interceptor = streamResolver)
            val intercepted = response.url
            
            // println("[SuperFlix] URL interceptada final: $intercepted")
            
            if (intercepted.isNotEmpty() && MEDIA_EXTENSIONS.any { intercepted.contains(".$it") }) {
                // Headers para a requisição do vídeo
                val videoHeaders = mapOf(
                    "Referer" to url,
                    "Origin" to mainUrl,
                    "User-Agent" to WEBVIEW_USER_AGENT,
                    "Accept" to "*/*",
                    "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
                    "Accept-Encoding" to "gzip, deflate, br",
                    "Connection" to "keep-alive",
                    "Sec-Fetch-Dest" to "video",
                    "Sec-Fetch-Mode" to "no-cors",
                    "Sec-Fetch-Site" to "cross-site"
                )
                
                if (intercepted.contains(".m3u8")) {
                    M3u8Helper.generateM3u8(
                        name,
                        intercepted,
                        mainUrl,
                        headers = videoHeaders
                    ).forEach(callback)
                } else {
                    // Para outros formatos (mp4, mkv direto)
                    callback(
                        ExtractorLink(
                            "SuperFlix",
                            "SuperFlix - ${intercepted.substringAfterLast(".").uppercase()}",
                            intercepted,
                            mainUrl,
                            getQualityFromName(intercepted),
                            false,
                            videoHeaders,
                            intercepted.contains(".mpd")
                        )
                    )
                }
                
                // println("[SuperFlix] Sucesso! Vídeo encontrado")
                true
            } else {
                // println("[SuperFlix] Falha: Nenhum vídeo encontrado")
                false
            }
        } catch (e: Exception) {
            // println("[SuperFlix] Erro: ${e.message}")
            e.printStackTrace()
            false
        }
    }
    
    // Função auxiliar para determinar qualidade baseada no URL
    private fun getQualityFromName(url: String): Int {
        return when {
            url.contains("360p") -> 360
            url.contains("480p") -> 480
            url.contains("720p") -> 720
            url.contains("1080p") -> 1080
            url.contains("2160p") -> 2160
            url.contains("4k", ignoreCase = true) -> 2160
            url.contains("hd", ignoreCase = true) -> 720
            url.contains("fullhd", ignoreCase = true) -> 1080
            else -> 720 // Qualidade padrão
        }
    }
}
