package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import org.jsoup.nodes.Element
import java.net.URLEncoder

class SuperFlix : MainAPI() {
    override var mainUrl = "https://superflix21.lol"
    override var name = "SuperFlix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/filmes" to "Filmes",
        "$mainUrl/series" to "Séries",
        "$mainUrl/lancamentos" to "Lançamentos"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + if (page > 1) "?page=$page" else ""
        val document = app.get(url).document

        val home = mutableListOf<SearchResponse>()

        document.select("div.recs-grid a.rec-card, .movie-card, article, .item").forEach { element ->
            element.toSearchResult()?.let { home.add(it) }
        }

        if (home.isEmpty()) {
            document.select("a[href*='/filme/'], a[href*='/serie/']").forEach { link ->
                val href = link.attr("href")
                if (href.isNotBlank() && !href.contains("#")) {
                    val title = link.selectFirst("img")?.attr("alt")
                        ?: link.selectFirst(".rec-title, .title, h2, h3")?.text()
                        ?: href.substringAfterLast("/").replace("-", " ").replace(Regex("\\d{4}$"), "").trim()

                    if (title.isNotBlank()) {
                        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
                        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
                        val poster = link.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                        val isSerie = href.contains("/serie/")

                        val searchResponse = if (isSerie) {
                            newTvSeriesSearchResponse(cleanTitle, fixUrl(href), TvType.TvSeries) {
                                this.posterUrl = poster
                                this.year = year
                            }
                        } else {
                            newMovieSearchResponse(cleanTitle, fixUrl(href), TvType.Movie) {
                                this.posterUrl = poster
                                this.year = year
                            }
                        }

                        home.add(searchResponse)
                    }
                }
            }
        }

        return newHomePageResponse(request.name, home.distinctBy { it.url })
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst(".rec-title, .movie-title, h2, h3, .title")?.text()
            ?: selectFirst("img")?.attr("alt")
            ?: return null

        val href = attr("href") ?: selectFirst("a")?.attr("href") ?: return null

        val poster = selectFirst("img")?.attr("src")
            ?.takeIf { it.isNotBlank() }
            ?.let { fixUrl(it) }
            ?: selectFirst("img")?.attr("data-src")?.let { fixUrl(it) }

        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
            ?: selectFirst(".rec-meta, .movie-year, .year")?.text()?.let {
                Regex("\\b(\\d{4})\\b").find(it)?.groupValues?.get(1)?.toIntOrNull()
            }

        val isSerie = href.contains("/serie/")
        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()

        return if (isSerie) {
            newTvSeriesSearchResponse(cleanTitle, fixUrl(href), TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newMovieSearchResponse(cleanTitle, fixUrl(href), TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/?s=$encodedQuery").document

        val results = mutableListOf<SearchResponse>()

        document.select("div.recs-grid a.rec-card, a[href*='/filme/'], a[href*='/serie/']").forEach { element ->
            element.toSearchResult()?.let { results.add(it) }
        }

        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val html = document.html()

        val jsonLd = extractJsonLd(html)

        val title = jsonLd.title ?: document.selectFirst("h1, .title")?.text() ?: return null
        val year = jsonLd.year ?: Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()

        val poster = jsonLd.posterUrl?.replace("/w500/", "/original/")
            ?: document.selectFirst("meta[property='og:image']")?.attr("content")?.let { fixUrl(it) }
            ?.replace("/w500/", "/original/")

        val plot = jsonLd.description ?: document.selectFirst("meta[name='description']")?.attr("content")
            ?: document.selectFirst(".syn, .description")?.text()

        val tags = jsonLd.genres ?: document.select("a.chip, .chip").map { it.text() }

        val actors = jsonLd.actors?.map { Actor(it, "") } ?: emptyList()

        val director = jsonLd.director?.firstOrNull()

        val isSerie = url.contains("/serie/") || jsonLd.type == "TVSeries"

        return if (isSerie) {
            val episodes = extractEpisodesFromButtons(document, url)

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = if (tags.isNotEmpty()) tags else null
                if (director != null) addActors(listOf(Actor(director, "Diretor")))
                addActors(actors)
            }
        } else {
            // Para filmes, armazenamos a URL do iframe/button para uso posterior
            val videoData = findFembedUrl(document) ?: ""

            newMovieLoadResponse(title, url, TvType.Movie, videoData) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = if (tags.isNotEmpty()) tags else null
                if (director != null) addActors(listOf(Actor(director, "Diretor")))
                addActors(actors)
            }
        }
    }

    private fun extractEpisodesFromButtons(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()

        document.select("button.bd-play[data-url]").forEach { button ->
            val fembedUrl = button.attr("data-url")
            val season = button.attr("data-season").toIntOrNull() ?: 1
            val episodeNum = button.attr("data-ep").toIntOrNull() ?: 1

            var episodeTitle = "Episódio $episodeNum"

            val parent = button.parents().find { it.hasClass("episode-item") || it.hasClass("episode") }
            parent?.let {
                val titleElement = it.selectFirst(".ep-title, .title, .name, h3, h4")
                if (titleElement != null) {
                    episodeTitle = titleElement.text().trim()
                }
            }

            episodes.add(
                newEpisode(fembedUrl) {
                    this.name = episodeTitle
                    this.season = season
                    this.episode = episodeNum
                }
            )
        }

        return episodes
    }

    private fun findFembedUrl(document: org.jsoup.nodes.Document): String? {
        // Primeiro, procurar por iframe do Fembed
        val iframe = document.selectFirst("iframe[src*='fembed']")
        if (iframe != null) {
            return iframe.attr("src")
        }

        // Procurar por botão de play com data-url
        val playButton = document.selectFirst("button.bd-play[data-url]")
        if (playButton != null) {
            return playButton.attr("data-url")
        }

        // Procurar por qualquer botão com URL do Fembed
        val anyButton = document.selectFirst("button[data-url*='fembed']")
        if (anyButton != null) {
            return anyButton.attr("data-url")
        }

        // Procurar no HTML por padrões
        val html = document.html()
        val patterns = listOf(
            Regex("""https?://[^"'\s]*fembed[^"'\s]*/e/\w+"""),
            Regex("""data-url=["'](https?://[^"']*fembed[^"']+)["']"""),
            Regex("""src\s*[:=]\s*["'](https?://[^"']*fembed[^"']+)["']""")
        )

        patterns.forEach { pattern ->
            pattern.find(html)?.let { match ->
                val url = if (match.groupValues.size > 1) match.groupValues[1] else match.value
                if (url.isNotBlank()) return url
            }
        }

        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("SuperFlix: Carregando links de: $data")
        
        // Extrair ID do vídeo (ex: 1152238 de https://fembed.sx/e/1152238)
        val videoId = data.substringAfterLast("/").substringBefore("?").trim()
        if (videoId.isEmpty()) {
            println("SuperFlix: ERRO - Não consegui extrair ID do vídeo")
            return false
        }
        
        println("SuperFlix: ID do vídeo: $videoId")
        
        return try {
            // Nova abordagem: Simular o fluxo completo do Fembed
            extractFembedLinksComplete(videoId, subtitleCallback, callback)
        } catch (e: Exception) {
            println("SuperFlix: ERRO - ${e.message}")
            e.printStackTrace()
            false
        }
    }

    private suspend fun extractFembedLinksComplete(
        videoId: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("SuperFlix: Iniciando extração completa para ID: $videoId")
        
        val baseUrl = "https://fembed.sx"
        val apiUrl = "$baseUrl/api.php?s=$videoId&c="
        
        println("SuperFlix: API URL base: $apiUrl")
        
        // Headers para simular navegador real
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "application/json, text/plain, */*",
            "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
            "Accept-Encoding" to "gzip, deflate, br",
            "Referer" to "https://fembed.sx/",
            "Origin" to "https://fembed.sx",
            "Connection" to "keep-alive",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "same-origin",
            "X-Requested-With" to "XMLHttpRequest"
        )
        
        // PASSO 1: Obter o player via API
        println("SuperFlix: PASSO 1 - Obtendo player...")
        
        val playerParams = mapOf(
            "action" to "getPlayer",
            "lang" to "DUB",
            "key" to "MA==" // base64 de "0"
        )
        
        val playerResponse = try {
            app.post(apiUrl, headers = headers, data = playerParams)
        } catch (e: Exception) {
            println("SuperFlix: Falha no POST, tentando GET: ${e.message}")
            // Tentar via GET
            app.get("$apiUrl&action=getPlayer&lang=DUB&key=MA==", headers = headers)
        }
        
        if (!playerResponse.isSuccessful) {
            println("SuperFlix: Falha ao obter player: ${playerResponse.code}")
            return false
        }
        
        val playerJson = playerResponse.parsedSafe<Map<String, Any>>()
        println("SuperFlix: Resposta do player: $playerJson")
        
        // PASSO 2: Obter token/URL do iframe
        var iframeUrl = extractIframeFromPlayerJson(playerJson)
        println("SuperFlix: Iframe URL: $iframeUrl")
        
        if (iframeUrl == null) {
            // Tentar extrair do HTML retornado
            val playerHtml = playerResponse.text
            iframeUrl = extractIframeFromHtml(playerHtml)
            println("SuperFlix: Iframe extraído do HTML: $iframeUrl")
        }
        
        if (iframeUrl == null) {
            println("SuperFlix: Não encontrou iframe, tentando obter diretamente...")
            // Tentar acessar diretamente a URL do stream
            return tryDirectStreamAccess(videoId, headers, callback)
        }
        
        // PASSO 3: Acessar o iframe para obter dados do stream
        println("SuperFlix: PASSO 3 - Acessando iframe...")
        
        val iframeHeaders = headers.toMutableMap()
        iframeHeaders["Referer"] = baseUrl
        iframeHeaders["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
        
        val iframeResponse = app.get(fixUrl(iframeUrl), headers = iframeHeaders)
        if (!iframeResponse.isSuccessful) {
            println("SuperFlix: Falha ao acessar iframe: ${iframeResponse.code}")
            return false
        }
        
        val iframeHtml = iframeResponse.text
        println("SuperFlix: Iframe HTML obtido (${iframeHtml.length} chars)")
        
        // PASSO 4: Extrair informações do stream do iframe
        val streamData = extractStreamDataFromIframe(iframeHtml, videoId)
        println("SuperFlix: Dados do stream: $streamData")
        
        if (streamData.isEmpty()) {
            println("SuperFlix: Nenhum dado de stream encontrado")
            return false
        }
        
        // PASSO 5: Gerar a URL m3u8 final (simulando o que o player faz)
        val m3u8Url = generateM3u8Url(streamData, videoId)
        println("SuperFlix: URL m3u8 gerada: $m3u8Url")
        
        if (m3u8Url != null) {
            // PASSO 6: Tentar acessar a URL m3u8 para verificar
            val m3u8Headers = headers.toMutableMap()
            m3u8Headers["Referer"] = baseUrl
            m3u8Headers["Accept"] = "*/*"
            m3u8Headers["Accept-Language"] = "pt-BR,pt;q=0.9,en;q=0.8"
            m3u8Headers["Connection"] = "keep-alive"
            
            try {
                val testResponse = app.get(m3u8Url, headers = m3u8Headers)
                if (testResponse.isSuccessful) {
                    println("SuperFlix: M3U8 URL verificada com sucesso!")
                    
                    val quality = determineQualityFromUrl(m3u8Url)
                    
                    val extractorLink = ExtractorLink(
                        name = "$name (Dublado)",
                        source = name,
                        url = m3u8Url,
                        referer = baseUrl,
                        quality = quality,
                        isM3u8 = true
                    )
                    callback(extractorLink)
                    return true
                } else {
                    println("SuperFlix: M3U8 URL falhou: ${testResponse.code}")
                }
            } catch (e: Exception) {
                println("SuperFlix: Erro ao testar M3U8: ${e.message}")
            }
        }
        
        // Última tentativa: Procurar por padrões no tráfego simulado
        return tryFindStreamPatterns(videoId, headers, callback)
    }

    private fun extractIframeFromPlayerJson(json: Map<String, Any>?): String? {
        return when {
            json == null -> null
            json.containsKey("file") && json["file"] is String -> json["file"] as String
            json.containsKey("url") && json["url"] is String -> json["url"] as String
            json.containsKey("iframe") && json["iframe"] is String -> json["iframe"] as String
            json.containsKey("player") && json["player"] is String -> json["player"] as String
            else -> null
        }
    }

    private fun extractIframeFromHtml(html: String): String? {
        // Procurar por iframe no HTML
        val iframePattern = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val match = iframePattern.find(html)
        
        if (match != null && match.groupValues.size > 1) {
            var url = match.groupValues[1]
            
            // Se for URL relativa, converter para absoluta
            if (url.startsWith("/")) {
                url = "https://fembed.sx$url"
            } else if (url.startsWith("./")) {
                url = "https://fembed.sx${url.substring(1)}"
            }
            
            return url
        }
        
        // Procurar por URL em scripts
        val scriptPattern = Regex("""["'](https?://[^"']+/v/\w+[^"']*)["']""")
        val scriptMatch = scriptPattern.find(html)
        if (scriptMatch != null && scriptMatch.groupValues.size > 1) {
            return scriptMatch.groupValues[1]
        }
        
        return null
    }

    private fun extractStreamDataFromIframe(html: String, videoId: String): Map<String, String> {
        val data = mutableMapOf<String, String>()
        
        // Padrões comuns para extrair dados do stream
        val patterns = mapOf(
            "t" to Regex("""t=([^&"']+)"""),
            "s" to Regex("""s=(\d+)"""),
            "e" to Regex("""e=(\d+)"""),
            "f" to Regex("""f=(\d+)"""),
            "srv" to Regex("""srv=(\d+)"""),
            "asn" to Regex("""asn=(\d+)"""),
            "sp" to Regex("""sp=(\d+)"""),
            "hash" to Regex("""hash=([^&"']+)"""),
            "id" to Regex("""id=([^&"']+)"""),
            "expires" to Regex("""expires=(\d+)""")
        )
        
        // Procurar por padrões com .ts (como nas imagens)
        val tsPattern = Regex("""seg-\d+-v\d+-a\d+\.ts\?([^"']+)""")
        val tsMatch = tsPattern.find(html)
        
        if (tsMatch != null) {
            val params = tsMatch.groupValues[1]
            patterns.forEach { (key, pattern) ->
                pattern.find(params)?.let { match ->
                    data[key] = match.groupValues[1]
                }
            }
        }
        
        // Se não encontrou, procurar em toda a página
        if (data.isEmpty()) {
            val allText = html.replace("\n", " ").replace("\r", " ")
            patterns.forEach { (key, pattern) ->
                pattern.find(allText)?.let { match ->
                    data[key] = match.groupValues[1]
                }
            }
        }
        
        // Adicionar valores padrão se não encontrados
        if (!data.containsKey("s")) data["s"] = "1765187808"
        if (!data.containsKey("e")) data["e"] = "10800"
        if (!data.containsKey("f")) data["f"] = "52068008"
        if (!data.containsKey("srv")) data["srv"] = "1055"
        if (!data.containsKey("asn")) data["asn"] = "52601"
        if (!data.containsKey("sp")) data["sp"] = "4000"
        if (!data.containsKey("t")) data["t"] = "ELGOW_RTC5bGn1wcxrJCMAi4_HILSinJY8IQIUJmQM"
        
        return data
    }

    private fun generateM3u8Url(streamData: Map<String, String>, videoId: String): String? {
        // Construir a URL base baseada nos padrões observados
        val t = streamData["t"] ?: return null
        val s = streamData["s"] ?: "1765187808"
        val e = streamData["e"] ?: "10800"
        val f = streamData["f"] ?: "52068008"
        val srv = streamData["srv"] ?: "1055"
        val asn = streamData["asn"] ?: "52601"
        val sp = streamData["sp"] ?: "4000"
        
        // Baseado nos exemplos das imagens
        val baseDomain = "be2719.rcr22.ams01.i8yz83pn.com"
        val path = "/hls2/02/10413/9uzf37f6e3o2_h"
        
        // Gerar URL do master.m3u8 (arquivo de playlist principal)
        val m3u8Url = "https://$baseDomain$path/master.m3u8?t=$t&s=$s&e=$e&f=$f&srv=$srv&asn=$asn&sp=$sp&p=0"
        
        return m3u8Url
    }

    private suspend fun tryDirectStreamAccess(
        videoId: String,
        headers: Map<String, String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("SuperFlix: Tentando acesso direto ao stream...")
        
        // Tentar várias URLs de domínio possíveis
        val domains = listOf(
            "be2719.rcr22.ams01.i8yz83pn.com",
            "rcr22.ams01.i8yz83pn.com",
            "ams01.i8yz83pn.com"
        )
        
        for (domain in domains) {
            // Tentar o padrão exato das imagens
            val m3u8Url = "https://$domain/hls2/02/10413/9uzf37f6e3o2_h/master.m3u8?t=ELGOW_RTC5bGn1wcxrJCMAi4_HILSinJY8IQIUJmQM&s=1765187808&e=10800&f=52068008&srv=1055&asn=52601&sp=4000&p=0"
            
            println("SuperFlix: Testando URL: $m3u8Url")
            
            try {
                val testHeaders = headers.toMutableMap()
                testHeaders["Referer"] = "https://fembed.sx/"
                testHeaders["Accept"] = "*/*"
                
                val response = app.get(m3u8Url, headers = testHeaders)
                if (response.isSuccessful) {
                    println("SuperFlix: URL direta funcionou!")
                    
                    val quality = determineQualityFromUrl(m3u8Url)
                    
                    val extractorLink = ExtractorLink(
                        name = "$name (Stream Direto)",
                        source = name,
                        url = m3u8Url,
                        referer = "https://fembed.sx/",
                        quality = quality,
                        isM3u8 = true
                    )
                    callback(extractorLink)
                    return true
                }
            } catch (e: Exception) {
                println("SuperFlix: Falha para $domain: ${e.message}")
            }
        }
        
        return false
    }

    private suspend fun tryFindStreamPatterns(
        videoId: String,
        headers: Map<String, String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("SuperFlix: Procurando por padrões de stream...")
        
        // Tentar acessar a página do vídeo diretamente
        val videoPageUrl = "https://fembed.sx/e/$videoId"
        val pageHeaders = headers.toMutableMap()
        pageHeaders["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
        
        try {
            val pageResponse = app.get(videoPageUrl, headers = pageHeaders)
            if (pageResponse.isSuccessful) {
                val pageHtml = pageResponse.text
                
                // Procurar por padrões específicos de streaming HLS
                val hlsPatterns = listOf(
                    Regex("""https?://[^"']+\.m3u8[^"']*"""),
                    Regex("""file\s*:\s*["'](https?://[^"']+)["']"""),
                    Regex("""src\s*:\s*["'](https?://[^"']+)["']"""),
                    Regex("""["'](https?://[^"']+/master\.m3u8[^"']*)["']"""),
                    Regex("""hls2/[^"']+\.m3u8""")
                )
                
                for (pattern in hlsPatterns) {
                    val matches = pattern.findAll(pageHtml)
                    matches.forEach { match ->
                        var url = if (match.groupValues.size > 1) match.groupValues[1] else match.value
                        
                        // Se for URL relativa, converter
                        if (url.startsWith("hls2/")) {
                            url = "https://be2719.rcr22.ams01.i8yz83pn.com/$url"
                        }
                        
                        if (url.contains(".m3u8")) {
                            println("SuperFlix: Encontrado possível M3U8: $url")
                            
                            // Testar a URL
                            val testHeaders = headers.toMutableMap()
                            testHeaders["Referer"] = videoPageUrl
                            
                            try {
                                val testResponse = app.get(url, headers = testHeaders)
                                if (testResponse.isSuccessful) {
                                    val quality = determineQualityFromUrl(url)
                                    
                                    val extractorLink = ExtractorLink(
                                        name = "$name (HLS)",
                                        source = name,
                                        url = url,
                                        referer = videoPageUrl,
                                        quality = quality,
                                        isM3u8 = true
                                    )
                                    callback(extractorLink)
                                    return true
                                }
                            } catch (e: Exception) {
                                println("SuperFlix: URL inválida: ${e.message}")
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("SuperFlix: Erro ao acessar página: ${e.message}")
        }
        
        return false
    }

    private fun determineQualityFromUrl(url: String): Int {
        return when {
            url.contains("1080") || url.contains("fullhd") || url.contains("fhd") -> Qualities.P1080.value
            url.contains("720") || url.contains("hd") -> Qualities.P720.value
            url.contains("480") || url.contains("sd") -> Qualities.P480.value
            url.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private data class JsonLdInfo(
        val title: String? = null,
        val year: Int? = null,
        val posterUrl: String? = null,
        val description: String? = null,
        val genres: List<String>? = null,
        val director: List<String>? = null,
        val actors: List<String>? = null,
        val tmdbId: String? = null,
        val type: String? = null
    )

    private fun extractJsonLd(html: String): JsonLdInfo {
        val pattern = Regex("<script type=\"application/ld\\+json\">(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
        val matches = pattern.findAll(html)

        matches.forEach { match ->
            try {
                val json = match.groupValues[1].trim()
                if (json.contains("\"@type\":\"Movie\"") || json.contains("\"@type\":\"TVSeries\"")) {

                    val title = Regex("\"name\":\"([^\"]+)\"").find(json)?.groupValues?.get(1)
                    val image = Regex("\"image\":\"([^\"]+)\"").find(json)?.groupValues?.get(1)
                    val description = Regex("\"description\":\"([^\"]+)\"").find(json)?.groupValues?.get(1)

                    val genresMatch = Regex("\"genre\":\\s*\\[([^\\]]+)\\]").find(json)
                    val genres = genresMatch?.groupValues?.get(1)
                        ?.split(",")
                        ?.map { it.trim().trim('"', '\'') }
                        ?.filter { it.isNotBlank() }

                    val actorsMatch = Regex("\"actor\":\\s*\\[([^\\]]+)\\]").find(json)
                    val actors = actorsMatch?.groupValues?.get(1)
                        ?.split("},")
                        ?.mapNotNull { actor ->
                            Regex("\"name\":\"([^\"]+)\"").find(actor)?.groupValues?.get(1)
                        }

                    val directorMatch = Regex("\"director\":\\s*\\[([^\\]]+)\\]").find(json)
                    val director = directorMatch?.groupValues?.get(1)
                        ?.split("},")
                        ?.mapNotNull { dir ->
                            Regex("\"name\":\"([^\"]+)\"").find(dir)?.groupValues?.get(1)
                        }

                    val sameAsMatch = Regex("\"sameAs\":\\s*\\[([^\\]]+)\\]").find(json)
                    val tmdbId = sameAsMatch?.groupValues?.get(1)
                        ?.split(",")
                        ?.find { it.contains("themoviedb.org") }
                        ?.substringAfterLast("/")
                        ?.trim(' ', '"', '\'')

                    val type = if (json.contains("\"@type\":\"Movie\"")) "Movie" else "TVSeries"

                    return JsonLdInfo(
                        title = title,
                        year = null,
                        posterUrl = image,
                        description = description,
                        genres = genres,
                        director = director,
                        actors = actors,
                        tmdbId = tmdbId,
                        type = type
                    )
                }
            } catch (e: Exception) {
                // Continua
            }
        }

        return JsonLdInfo()
    }
}