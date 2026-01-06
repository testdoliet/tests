package com.Superflix.extractors

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.Jsoup

class SuperflixExtractor : ExtractorApi() {
    override val name = "Superflix Player"
    override val mainUrl = "https://superflix1.cloud"
    override val requiresReferer = true
    
    companion object {
        private const val TAG = "SuperflixExtractor"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        println("[$TAG] ========== INICIANDO EXTRACTION ==========")
        println("[$TAG] URL recebida: $url")
        println("[$TAG] Referer: ${referer ?: "null"}")
        println("[$TAG] Main URL: $mainUrl")

        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
            "Referer" to referer ?: mainUrl,
            "Origin" to mainUrl,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8",
            "Accept-Encoding" to "gzip, deflate, br",
            "Connection" to "keep-alive",
            "Upgrade-Insecure-Requests" to "1"
        )

        println("[$TAG] Headers configurados: $headers")

        // ESTRATÉGIA 1: Tentar a URL como HLS direto
        println("[$TAG] === ESTRATÉGIA 1: Testando como HLS direto ===")
        if (url.contains(".m3u8", ignoreCase = true)) {
            println("[$TAG] URL já contém .m3u8 - Tratando como HLS")
            callback(
                ExtractorLink(
                    name,
                    "$name (HLS Direto)",
                    url,
                    referer ?: mainUrl,
                    Qualities.Unknown.value,
                    isM3u8 = true,
                    headers = headers
                )
            )
        } else if (url.contains(".mp4", ignoreCase = true) || url.contains("storage.googleapis.com")) {
            println("[$TAG] URL parece ser MP4 direto")
            callback(
                ExtractorLink(
                    name,
                    "$name (MP4 Direto)",
                    url,
                    referer ?: mainUrl,
                    Qualities.Unknown.value,
                    isM3u8 = false,
                    headers = headers
                )
            )
        }

        // ESTRATÉGIA 2: Fazer requisição e analisar resposta
        println("[$TAG] === ESTRATÉGIA 2: Analisando página do player ===")
        try {
            println("[$TAG] Fazendo requisição GET para: $url")
            val res = app.get(url, headers = headers, referer = referer)
            println("[$TAG] Status code: ${res.code}")
            println("[$TAG] Content-Type: ${res.headers["Content-Type"]}")
            println("[$TAG] Tamanho da resposta: ${res.text.length} caracteres")
            
            // Salvar resposta completa para análise
            val responseText = res.text
            println("[$TAG] Primeiros 500 chars da resposta:\n${responseText.take(500)}...")
            
            // Procurar URLs de vídeo na resposta
            val videoPatterns = listOf(
                Regex("""["'](https?://[^"']+\.(?:m3u8|mp4|mkv|avi|mov)[^"']*)["']"""),
                Regex("""src=["']([^"']+)["']"""),
                Regex("""file["']?\s*:\s*["']([^"']+)["']"""),
                Regex("""source["']?\s*:\s*["']([^"']+)["']"""),
                Regex("""video["']?\s*:\s*["']([^"']+)["']"""),
                Regex("""url["']?\s*:\s*["']([^"']+)["']"""),
                Regex("""(https?://[^"'\s<>]+\.m3u8[^"'\s<>]*)"""),
                Regex("""(https?://[^"'\s<>]+\.mp4[^"'\s<>]*)"""),
                Regex("""storage\.googleapis\.com[^"'\s<>]+""")
            )
            
            println("[$TAG] Procurando padrões de vídeo...")
            val foundUrls = mutableSetOf<String>()
            videoPatterns.forEachIndexed { index, pattern ->
                val matches = pattern.findAll(responseText).toList()
                if (matches.isNotEmpty()) {
                    println("[$TAG] Padrão $index encontrou ${matches.size} matches")
                    matches.forEach { match ->
                        val foundUrl = match.groupValues[1]
                        if (foundUrl.isNotBlank() && !foundUrls.contains(foundUrl)) {
                            foundUrls.add(foundUrl)
                            println("[$TAG] URL encontrada: $foundUrl")
                        }
                    }
                }
            }
            
            // Procurar em variáveis JavaScript
            println("[$TAG] Procurando variáveis JavaScript...")
            val jsPatterns = listOf(
                Regex("""var\s+video\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL),
                Regex("""player\.setup\((\{.*?\})\)""", RegexOption.DOT_MATCHES_ALL),
                Regex("""jwplayer\(\).setup\((\{.*?\})\)""", RegexOption.DOT_MATCHES_ALL),
                Regex("""new\s+Plyr.*?\{.*?source:\s*\{.*?src:\s*["']([^"']+)["']""", RegexOption.DOT_MATCHES_ALL),
                Regex("""source["']?\s*:\s*["']([^"']+)["']"""),
                Regex("""file["']?\s*:\s*["']([^"']+)["']"""),
                Regex("""\{"file"\s*:\s*"([^"]+)""")
            )
            
            jsPatterns.forEachIndexed { index, pattern ->
                val matches = pattern.findAll(responseText).toList()
                if (matches.isNotEmpty()) {
                    println("[$TAG] Padrão JS $index encontrou ${matches.size} matches")
                    matches.forEach { match ->
                        val jsonOrUrl = match.groupValues[1]
                        println("[$TAG] Match JS $index: ${jsonOrUrl.take(100)}...")
                        
                        // Se for JSON, tentar parsear
                        if (jsonOrUrl.startsWith("{") && jsonOrUrl.endsWith("}")) {
                            try {
                                val json = tryParseJson<Map<String, Any>>(jsonOrUrl)
                                println("[$TAG] JSON parseado: $json")
                                json?.let {
                                    val file = it["file"] as? String
                                    val sources = it["sources"] as? List<Map<String, Any>>
                                    val tracks = it["tracks"] as? List<Map<String, Any>>
                                    
                                    file?.let { url ->
                                        println("[$TAG] URL do JSON (file): $url")
                                        if (!foundUrls.contains(url)) {
                                            foundUrls.add(url)
                                        }
                                    }
                                    
                                    sources?.forEach { source ->
                                        val src = source["src"] as? String
                                        src?.let { url ->
                                            println("[$TAG] URL do JSON (source): $url")
                                            if (!foundUrls.contains(url)) {
                                                foundUrls.add(url)
                                            }
                                        }
                                    }
                                    
                                    tracks?.forEach { track ->
                                        val file = track["file"] as? String
                                        file?.let { subtitleUrl ->
                                            println("[$TAG] Legenda encontrada: $subtitleUrl")
                                            subtitleCallback(SubtitleFile("pt", subtitleUrl))
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                println("[$TAG] Erro ao parsear JSON: ${e.message}")
                            }
                        }
                    }
                }
            }
            
            // Processar URLs encontradas
            println("[$TAG] === PROCESSANDO ${foundUrls.size} URLs ENCONTRADAS ===")
            foundUrls.forEach { videoUrl ->
                println("[$TAG] Processando URL: $videoUrl")
                
                when {
                    videoUrl.contains(".m3u8", ignoreCase = true) -> {
                        println("[$TAG] Identificado como HLS (.m3u8)")
                        try {
                            M3u8Helper.generateM3u8(
                                name,
                                videoUrl,
                                referer = referer ?: mainUrl,
                                headers = headers
                            ).forEach { link ->
                                println("[$TAG] Gerado link HLS: ${link.url} (qualidade: ${link.quality})")
                                callback(link)
                            }
                        } catch (e: Exception) {
                            println("[$TAG] Erro ao gerar M3u8: ${e.message}")
                            callback(
                                ExtractorLink(
                                    name,
                                    "$name (HLS)",
                                    videoUrl,
                                    referer ?: mainUrl,
                                    Qualities.Unknown.value,
                                    isM3u8 = true,
                                    headers = headers
                                )
                            )
                        }
                    }
                    videoUrl.contains(".mp4", ignoreCase = true) || 
                    videoUrl.contains("storage.googleapis.com") -> {
                        println("[$TAG] Identificado como MP4 direto")
                        callback(
                            ExtractorLink(
                                name,
                                "$name (MP4)",
                                videoUrl,
                                referer ?: mainUrl,
                                Qualities.P720.value,
                                isM3u8 = false,
                                headers = headers
                            )
                        )
                    }
                    else -> {
                        println("[$TAG] URL não reconhecida, tentando como genérica")
                        callback(
                            ExtractorLink(
                                name,
                                "$name (Genérico)",
                                videoUrl,
                                referer ?: mainUrl,
                                Qualities.Unknown.value,
                                isM3u8 = videoUrl.contains("m3u8"),
                                headers = headers
                            )
                        )
                    }
                }
            }
            
            // Procurar iframes embed
            println("[$TAG] Procurando iframes...")
            val doc = Jsoup.parse(responseText)
            val iframes = doc.select("iframe[src]")
            println("[$TAG] Encontrados ${iframes.size} iframes")
            iframes.forEachIndexed { i, iframe ->
                val src = iframe.attr("src")
                println("[$TAG] Iframe $i: $src")
                if (src.isNotBlank() && !src.startsWith("javascript:")) {
                    val fullUrl = if (src.startsWith("http")) src else "$mainUrl/$src".removeSuffix("/$src")
                    println("[$TAG] Processando iframe como nova URL: $fullUrl")
                    try {
                        getUrl(fullUrl, referer ?: url, subtitleCallback, callback)
                    } catch (e: Exception) {
                        println("[$TAG] Erro ao processar iframe: ${e.message}")
                    }
                }
            }

        } catch (e: Exception) {
            println("[$TAG] ERRO na estratégia 2: ${e.message}")
            e.printStackTrace()
        }

        // ESTRATÉGIA 3: Fallback - testar URLs comuns
        println("[$TAG] === ESTRATÉGIA 3: Fallback URLs comuns ===")
        val commonPatterns = listOf(
            "https://storage.googleapis.com/mediastorage",
            "https://playembedapi.site",
            "https://watch.gxplayer.xyz",
            "https://embed.gxplayer.xyz",
            "https://assistirseriesonline.icu"
        )
        
        commonPatterns.forEach { pattern ->
            if (url.contains(pattern, ignoreCase = false)) {
                println("[$TAG] URL corresponde ao padrão: $pattern")
                callback(
                    ExtractorLink(
                        name,
                        "$name (Fallback $pattern)",
                        url,
                        referer ?: mainUrl,
                        Qualities.Unknown.value,
                        isM3u8 = url.contains("m3u8"),
                        headers = headers
                    )
                )
            }
        }

        // ESTRATÉGIA 4: Tentar padrão comum de APIs
        println("[$TAG] === ESTRATÉGIA 4: Padrões de API ===")
        try {
            // Tentar extrair IDs da URL para construir URL de API
            val episodeId = Regex("""episodio/(\d+)""").find(url)?.groupValues?.get(1)
            val seasonId = Regex("""season[_-]?id=(\d+)""").find(url)?.groupValues?.get(1)
            
            println("[$TAG] Episode ID extraído: ${episodeId ?: "não encontrado"}")
            println("[$TAG] Season ID extraído: ${seasonId ?: "não encontrado"}")
            
            if (episodeId != null) {
                // Tentar API comum
                val apiUrls = listOf(
                    "https://assistirseriesonline.icu/api/player/$episodeId",
                    "https://assistirseriesonline.icu/embed/$episodeId",
                    "https://superflix1.cloud/api/video/$episodeId"
                )
                
                apiUrls.forEach { apiUrl ->
                    println("[$TAG] Testando API: $apiUrl")
                    try {
                        val apiRes = app.get(apiUrl, headers = headers)
                        println("[$TAG] Resposta da API (${apiRes.code}): ${apiRes.text.take(200)}...")
                    } catch (e: Exception) {
                        println("[$TAG] API falhou: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            println("[$TAG] Erro na estratégia 4: ${e.message}")
        }

        println("[$TAG] ========== EXTRACTION FINALIZADA ==========")
        println("[$TAG] Total de URLs processadas: OK")
    }
}
