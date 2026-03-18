package com.AnimesFlix

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import org.jsoup.nodes.Element

@CloudstreamPlugin
class AnimesFlixProvider : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnimesFlix())
    }
}

class AnimesFlix : MainAPI() {
    override var mainUrl = "https://www.animesflix.site/"
    override var name = "AnimesFlix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)
    override val usesWebView = true

    companion object {
        private const val TAG = "AnimesFlix"
        private const val SEARCH_PATH = "/search/?q="
        private const val EPISODES_PATH = "/episodios"

        // Seletores baseados no HTML
        private const val ANIME_CARD = ".anime-item"
        private const val EPISODE_CARD = ".episode-item"
        private const val POSTER_SELECTOR = "img"
        private const val EPISODE_NUMBER_SELECTOR = ".episode-badge"
        private const val EPISODE_META_SELECTOR = ".episode-meta span"

        private const val ANIME_TITLE = "h1.anime-title-large"
        private const val ANIME_POSTER = ".anime-poster-large img"
        private const val ANIME_SYNOPSIS = ".overview-text"
        private const val ANIME_TAGS = ".tags-container .tag"
        private const val ANIME_METADATA = ".anime-meta-item"
        private const val EPISODE_LIST = ".episodes-list .episode-row"

        private const val RECOMMENDATIONS = ".content-grid .anime-item"
        
        // Seletores de paginação
        private const val PAGINATION_NEXT = ".pagination a:contains(Próxima), .pagination .next"
        private const val PAGINATION_PREV = ".pagination a:contains(Anterior), .pagination .prev"
        private const val PAGINATION_INFO = ".pagination-info"
    }

    // Lista de categorias baseada nos gêneros com mais de 20 animes
    private val mainPageCategories = listOf(
        EPISODES_PATH to "Últimos Episódios",
        "/genero/animes-acao" to "Ação",
        "/genero/animes-aventura" to "Aventura",
        "/genero/animes-comedia" to "Comédia",
        "/genero/animes-drama" to "Drama",
        "/genero/animes-fantasia" to "Fantasia",
        "/genero/animes-ficcao-cientifica" to "Ficção Científica",
        "/genero/animes-horror" to "Horror",
        "/genero/animes-misterio" to "Mistério",
        "/genero/animes-romance" to "Romance",
        "/genero/animes-sci-fi" to "Sci-Fi",
        "/genero/animes-seinen" to "Seinen",
        "/genero/animes-shounen" to "Shounen",
        "/genero/slice-of-life1" to "Slice of life",
        "/genero/sobrenatural1" to "Sobrenatural",
        "/genero/animes-suspense" to "Suspense",
        "/genero/animes-vida-escolar" to "Vida Escolar",
        "/genero/animes" to "Animes",
        "/genero/animes-artes-marciais" to "Artes Marciais",
        "/genero/animes-demonios" to "Demônios",
        "/genero/animes-ecchi" to "Ecchi",
        "/genero/animes-esportes" to "Esporte",
        "/genero/animes-jogos" to "Jogos",
        "/genero/animes-magia" to "Magia",
        "/genero/animes-militar" to "Militar",
        "/genero/animes-psicologico" to "Psicológico",
        "/genero/animes-superpoder" to "Superpoder"
    )

    override val mainPage = mainPageOf(
        *mainPageCategories.map { (path, name) ->
            "$mainUrl${path.removePrefix("/")}" to name
        }.toTypedArray()
    )

    private fun cleanTitle(dirtyTitle: String): String {
        return dirtyTitle
            .replace("(?i)\\s*\\(dublado\\)".toRegex(), "")
            .replace("(?i)\\s*\\(legendado\\)".toRegex(), "")
            .replace("(?i)\\s*dublado\\s*$".toRegex(), "")
            .replace("(?i)\\s*legendado\\s*$".toRegex(), "")
            .replace("(?i)\\s*–\\s*todos os epis[oó]dios".toRegex(), "")
            .replace("\\s+".toRegex(), " ")
            .trim()
            .ifBlank { dirtyTitle }
    }

    private fun extractEpisodeNumber(title: String): Int? {
        return listOf(
            "Epis[oó]dio\\s*(\\d+)".toRegex(RegexOption.IGNORE_CASE),
            "Ep\\.?\\s*(\\d+)".toRegex(RegexOption.IGNORE_CASE),
            "E(\\d+)".toRegex(RegexOption.IGNORE_CASE),
            "\\b(\\d{1,3})\\b".toRegex()
        ).firstNotNullOfOrNull { it.find(title)?.groupValues?.get(1)?.toIntOrNull() }
    }

    private fun extractAnimeTitleFromEpisode(episodeTitle: String): String {
        var clean = episodeTitle
            .replace("(?i)Epis[oó]dio\\s*\\d+".toRegex(), "")
            .replace("(?i)Ep\\.?\\s*\\d+".toRegex(), "")
            .replace("(?i)E\\d+".toRegex(), "")
            .replace("–", "")
            .replace("-", "")
            .replace("(?i)\\s*\\(dublado\\)".toRegex(), "")
            .replace("(?i)\\s*\\(legendado\\)".toRegex(), "")
            .replace("\\s+".toRegex(), " ")
            .trim()

        clean = clean.replace("\\s*\\d+\\s*$".toRegex(), "").trim()

        return clean.ifBlank { "Anime" }
    }

    private fun isDubbed(element: Element): Boolean {
        return element.text().contains("Dublado", true) ||
               element.select(EPISODE_META_SELECTOR).any { it.text().contains("Dublado", true) }
    }

    private fun Element.toEpisodeSearchResponse(): AnimeSearchResponse? {
        val linkElement = selectFirst("a[href*='/series/']") ?: return null
        val href = linkElement.attr("href")
        if (!href.contains("/series/")) return null

        val episodeBadge = selectFirst(EPISODE_NUMBER_SELECTOR)?.text()
        val episodeNumber = episodeBadge?.replace("E", "")?.toIntOrNull() ?: 1

        val titleElement = selectFirst(".episode-title a") ?: selectFirst(".episode-title")
        val rawTitle = titleElement?.text()?.trim() ?: return null
        val animeTitle = extractAnimeTitleFromEpisode(rawTitle)
        val cleanedTitle = cleanTitle(animeTitle)

        val posterUrl = selectFirst(POSTER_SELECTOR)?.attr("src")?.let { fixUrl(it) }
        
        // Verifica se é legendado pelo título
        val isSubbed = rawTitle.contains("Legendado", true)
        val isDubbed = isDubbed(this) && !isSubbed

        val urlWithPoster = if (posterUrl != null) {
            "$href|poster=$posterUrl"
        } else {
            href
        }

        println("$TAG - Episode Search Response - Title: $cleanedTitle, URL: $href, Episode: $episodeNumber, Subbed: $isSubbed")

        return newAnimeSearchResponse(cleanedTitle, fixUrl(urlWithPoster), TvType.Anime) {
            this.posterUrl = posterUrl
            if (isSubbed) {
                addDubStatus(false, episodeNumber) // false = não é dublado (legendado)
            } else {
                addDubStatus(true, episodeNumber) // true = é dublado
            }
        }
    }

    private fun Element.toAnimeSearchResponse(): AnimeSearchResponse? {
        val linkElement = selectFirst("a[href*='/series/']") ?: return null
        val href = linkElement.attr("href")

        val titleElement = selectFirst(".anime-title")
        val rawTitle = titleElement?.text()?.trim() ?: return null
        val cleanedTitle = cleanTitle(rawTitle)

        val posterUrl = selectFirst(POSTER_SELECTOR)?.attr("src")?.let { fixUrl(it) }
        
        // Verifica se é legendado pelo título
        val isSubbed = rawTitle.contains("Legendado", true)
        val isDubbed = isDubbed(this) && !isSubbed

        println("$TAG - Anime Search Response - Title: $cleanedTitle, URL: $href, Subbed: $isSubbed")

        return newAnimeSearchResponse(cleanedTitle, fixUrl(href), TvType.Anime) {
            this.posterUrl = posterUrl
            if (isSubbed) {
                addDubStatus(false, null) // false = não é dublado (legendado)
            } else {
                addDubStatus(true, null) // true = é dublado
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        println("$TAG - getMainPage - Name: ${request.name}, Page: $page, URL: ${request.data}")
        
        val baseUrl = request.data
        
        val url = if (page > 1) {
            if (baseUrl.contains(EPISODES_PATH)) {
                // Para episódios: /episodios/pagina/2
                "$mainUrl$EPISODES_PATH/pagina/$page"
            } else if (baseUrl.contains("/genero/")) {
                // Para gêneros: /genero/animes-acao/pagina/2
                val genrePath = baseUrl.substringAfter("$mainUrl").removeSuffix("/")
                "$mainUrl$genrePath/pagina/$page"
            } else {
                baseUrl
            }
        } else {
            baseUrl
        }
        
        println("$TAG - getMainPage - Final URL: $url")

        try {
            val document = app.get(url).document

            return if (request.name == "Últimos Episódios") {
                // Para Últimos Episódios, usa layout horizontal
                val episodes = document.select(".episodes-grid $EPISODE_CARD")
                    .mapNotNull { it.toEpisodeSearchResponse() }
                    .distinctBy { it.url }

                // Verifica se tem próxima página
                val hasNext = document.select(PAGINATION_NEXT).isNotEmpty()
                
                println("$TAG - Últimos Episódios - Found ${episodes.size} episodes, hasNext: $hasNext")

                newHomePageResponse(
                    list = HomePageList(request.name, episodes, isHorizontalImages = true),
                    hasNext = hasNext
                )
            } else {
                // Para categorias de gêneros
                val items = document.select(".content-grid $ANIME_CARD")
                    .mapNotNull { it.toAnimeSearchResponse() }
                    .distinctBy { it.url }

                // Verifica se tem próxima página
                val hasNext = document.select(PAGINATION_NEXT).isNotEmpty()
                
                println("$TAG - ${request.name} - Found ${items.size} items, hasNext: $hasNext")

                newHomePageResponse(request.name, items, hasNext)
            }
        } catch (e: Exception) {
            println("$TAG - Error in getMainPage: ${e.message}")
            e.printStackTrace()
            return newHomePageResponse(request.name, emptyList(), false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 2) return emptyList()
        
        println("$TAG - Searching for: $query")

        val searchUrl = "$mainUrl$SEARCH_PATH${java.net.URLEncoder.encode(query, "UTF-8")}"
        
        try {
            val document = app.get(searchUrl).document

            val results = mutableListOf<SearchResponse>()

            document.select(".content-grid $ANIME_CARD").mapNotNullTo(results) { it.toAnimeSearchResponse() }
            document.select(".episodes-grid $EPISODE_CARD").mapNotNullTo(results) { it.toEpisodeSearchResponse() }
            
            println("$TAG - Search found ${results.size} results")

            return results.distinctBy { it.url }
        } catch (e: Exception) {
            println("$TAG - Search error: ${e.message}")
            e.printStackTrace()
            return emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        println("$TAG - Loading URL: $url")
        
        val parts = url.split("|poster=")
        val actualUrl = parts[0]
        val thumbPoster = parts.getOrNull(1)?.let { if (it.isNotBlank()) fixUrl(it) else null }

        try {
            val document = app.get(actualUrl).document

            val rawTitle = document.selectFirst(ANIME_TITLE)?.text()?.trim() ?: 
                          document.select("meta[property='og:title']").attr("content") ?: "Sem Título"
            val title = cleanTitle(rawTitle)
            
            println("$TAG - Title: $title")

            // Usa o poster como backdrop também
            val poster = thumbPoster ?: document.selectFirst(ANIME_POSTER)?.attr("src")?.let { fixUrl(it) } ?:
                        document.select("meta[property='og:image']").attr("content")?.let { fixUrl(it) }

            val backdrop = poster // Usa o mesmo poster como backdrop

            val synopsis = document.selectFirst(ANIME_SYNOPSIS)?.text()?.trim() ?:
                          document.select("meta[name='description']").attr("content")

            val tags = document.select(ANIME_TAGS).map { it.text().trim() }

            var duration: Int? = null

            document.select(ANIME_METADATA).forEach { element ->
                val text = element.text()
                when {
                    text.contains("min") -> {
                        duration = Regex("(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
                    }
                }
            }

            val episodeRows = document.select(EPISODE_LIST)
            val episodesList = mutableListOf<Episode>()

            if (episodeRows.isNotEmpty()) {
                episodeRows.forEach { row ->
                    val episodeLink = row.attr("href")
                    val episodeNumber = row.attr("data-episode-number").toIntOrNull() ?:
                                       row.select(".episode-number").text().toIntOrNull()
                    val seasonNumber = row.attr("data-temporada").toIntOrNull() ?: 1

                    if (episodeNumber != null && episodeLink.isNotBlank()) {
                        val episodeName = row.select(".episode-name").text()?.trim() ?: "Episódio $episodeNumber"
                        val isSubbed = row.text().contains("Legendado", true)

                        val episode = newEpisode(fixUrl(episodeLink)) {
                            this.name = episodeName
                            this.season = seasonNumber
                            this.episode = episodeNumber
                            this.posterUrl = poster
                        }
                        episodesList.add(episode)
                    }
                }
            }
            
            println("$TAG - Found ${episodesList.size} episodes")

            val recommendations = document.select(RECOMMENDATIONS).mapNotNull { element ->
                element.toAnimeSearchResponse()
            }.take(20)

            val isMovie = actualUrl.contains("/filmes/") || episodesList.isEmpty()
            
            println("$TAG - isMovie: $isMovie")

            return if (isMovie) {
                newMovieLoadResponse(title, actualUrl, TvType.AnimeMovie, actualUrl) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backdrop
                    this.plot = synopsis
                    this.tags = tags
                    this.duration = duration
                    this.recommendations = recommendations.takeIf { it.isNotEmpty() }
                }
            } else {
                val sortedEpisodes = episodesList.sortedBy { it.episode }

                newAnimeLoadResponse(title, actualUrl, TvType.Anime) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backdrop
                    this.plot = synopsis
                    this.tags = tags
                    this.recommendations = recommendations.takeIf { it.isNotEmpty() }

                    if (sortedEpisodes.isNotEmpty()) {
                        // Agrupa episódios por temporada
                        val episodesBySeason = sortedEpisodes.groupBy { it.season }
                        episodesBySeason.forEach { (season, episodes) ->
                            val isSubbedSeason = episodes.any { it.name?.contains("Legendado", true) == true }
                            addEpisodes(
                                if (isSubbedSeason) DubStatus.Subbed else DubStatus.Dubbed,
                                episodes
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("$TAG - Error loading URL: ${e.message}")
            e.printStackTrace()
            throw e
        }
    }

    override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    val actualUrl = data.split("|poster=")[0]
    
    println("🎬🔗 ========== LOAD LINKS INICIADO ==========")
    println("🎬🔗 URL recebida: $actualUrl")
    println("🎬🔗 =========================================")
    
    return try {
        println("📡🌐 Fazendo requisição para a página do episódio...")
        val response = app.get(actualUrl, headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
            "Referer" to mainUrl
        ))

        println("📡✅ Código de resposta: ${response.code}")
        println("📏📦 Tamanho da resposta: ${response.text.length} bytes")
        
        val document = org.jsoup.Jsoup.parse(response.text)
        
        println("🔍📜 Procurando script com configurações (urlConfig)...")
        val script = document.select("script:containsData(urlConfig)").first()?.data()
        
        if (script == null) {
            println("❌🔍 Script não encontrado na página!")
            println("🔍📄 Primeiros 500 caracteres da página:")
            println(response.text.take(500))
            println("🎬🔗 ========== LOAD LINKS FALHOU ==========")
            return false
        }
        
        println("✅📜 Script encontrado! Tamanho: ${script.length} caracteres")
        println("📝📋 Primeiros 200 caracteres do script:")
        println(script.take(200))
        
        // Extrai PRIMARY_URL (servidor principal)
        val primaryUrl = Regex("PRIMARY_URL\\s*=\\s*\"(.*?)\"").find(script)?.groupValues?.get(1)
            ?: "https://ondemand.towns3.shop"
        
        println("🌐🔗 PRIMARY_URL extraída: $primaryUrl")
        
        // Extrai slug do anime
        val slug = Regex("slug\\s*:\\s*\"(.*?)\"").find(script)?.groupValues?.get(1)
        
        if (slug == null) {
            println("❌🐌 Slug não encontrado no script!")
            println("🔍📋 Procurando por 'slug' no script...")
            val slugLines = script.lines().filter { it.contains("slug") }
            slugLines.forEach { println("   📝 $it") }
            println("🎬🔗 ========== LOAD LINKS FALHOU ==========")
            return false
        }
        
        println("✅🐌 Slug extraído: $slug")
        
        // Extrai tipo (animes ou filmes)
        val tipo = Regex("tipo\\s*:\\s*\"(.*?)\"").find(script)?.groupValues?.get(1) ?: "animes"
        println("🎬📋 Tipo: $tipo")
        
        // Extrai temporada e episódio (para séries)
        val temporada = Regex("temporada\\s*:\\s*(\\d+)").find(script)?.groupValues?.get(1)?.toIntOrNull()
        val episodio = Regex("episodio\\s*:\\s*(\\d+)").find(script)?.groupValues?.get(1)?.toIntOrNull()
        
        println("📅🔢 Temporada: ${temporada ?: "N/A"} | Episódio: ${episodio ?: "N/A"}")
        
        // Headers necessários para o stream
        val streamHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
            "Referer" to "https://www.animesflix.site/",
            "Accept" to "*/*",
            "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
            "Connection" to "keep-alive",
            "Range" to "bytes=0-"
        )
        
        println("📋🔧 Headers configurados:")
        streamHeaders.forEach { (key, value) ->
            println("   📌 $key: $value")
        }
        
        // Constrói a URL do stream com várias variações de slug
        val firstLetter = slug.firstOrNull()?.uppercase() ?: ""
        println("🔤📌 Primeira letra do slug (maiúscula): $firstLetter")
        
        // Lista de possíveis slugs (com e sem legendado/dublado)
        val slugVariations = mutableListOf(
            slug,
            slug.replace("-legendado", ""),
            slug.replace("-dublado", ""),
            slug.replace(Regex("-\\d{4}$"), ""), // remove ano do final
            slug.replace(Regex("-\\d{4}-legendado$"), ""),
            slug.replace(Regex("-\\d{4}-dublado$"), "")
        ).distinct().filter { it.isNotBlank() }
        
        println("🔄🐌 Variações de slug geradas (${slugVariations.size}):")
        slugVariations.forEachIndexed { i, s -> 
            println("   ${i+1}. $s")
        }
        
        val tempNum = temporada?.toString()?.padStart(2, '0') ?: "01"
        val epNum = episodio?.toString()?.padStart(2, '0') ?: "01"
        val timestamp = System.currentTimeMillis()
        
        println("📅🔢 Temporada formatada: $tempNum, Episódio formatado: $epNum")
        println("⏱️🕒 Timestamp: $timestamp")
        
        val streamUrls = mutableListOf<String>()
        
        // Gera URLs para cada variação de slug
        println("🔗🌐 Gerando URLs para teste:")
        for (slugVar in slugVariations) {
            if (slugVar.isBlank()) continue
            
            val letter = slugVar.firstOrNull()?.uppercase() ?: firstLetter
            
            val streamPath = if (tipo == "filmes") {
                "$letter/$slugVar/stream/stream.m3u8"
            } else {
                "$letter/$slugVar/${tempNum}-temporada/$epNum/stream.m3u8"
            }
            
            val fullUrl = "$primaryUrl/$streamPath?nocache=$timestamp"
            streamUrls.add(fullUrl)
            println("   📍 $fullUrl")
        }
        
        println("🎯🔗 Total de URLs para testar: ${streamUrls.size}")
        
        var anySuccess = false
        var testedCount = 0
        
        // Testa cada URL até encontrar uma que funcione
        for (streamUrl in streamUrls) {
            testedCount++
            println("🔍🔄 Testando URL $testedCount/${streamUrls.size}:")
            println("   📎 $streamUrl")
            
            try {
                // Testa se a URL existe com uma requisição HEAD
                println("   📡⏳ Enviando requisição HEAD...")
                val testResponse = app.head(
                    streamUrl,
                    headers = streamHeaders,
                    timeout = 5000,
                    allowRedirects = false
                )
                
                println("   📡✅ Código de resposta HEAD: ${testResponse.code}")
                
                if (testResponse.code == 200 || testResponse.code == 206) {
                    println("   ✅🎉 URL FUNCIONOU! Código ${testResponse.code}")
                    
                    // Mostra headers da resposta
                    println("   📋📨 Headers da resposta:")
                    testResponse.headers.forEach { (key, value) ->
                        println("      📌 $key: $value")
                    }
                    
                    // Adiciona o link direto
                    callback(
                        newExtractorLink(
                            source = "AnimesFlix",
                            name = "AnimesFlix",
                            url = streamUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "https://www.animesflix.site/"
                            this.quality = 1080
                            this.headers = streamHeaders
                        }
                    )
                    
                    println("   ✅📤 Link adicionado ao callback com qualidade 1080p")
                    anySuccess = true
                    break
                } else {
                    println("   ❌ Código ${testResponse.code} - URL não disponível")
                    
                    // Tenta uma requisição GET para ver se o conteúdo é diferente
                    if (testResponse.code == 404) {
                        println("   🔍📄 Testando com GET para ver se há conteúdo...")
                        try {
                            val getResponse = app.get(
                                streamUrl,
                                headers = streamHeaders,
                                timeout = 3000
                            )
                            println("   📡✅ GET retornou código: ${getResponse.code}")
                            if (getResponse.code == 200 || getResponse.code == 206) {
                                println("   ✅🎉 URL FUNCIONOU COM GET! (HEAD falhou)")
                                // Adiciona o link mesmo assim
                                callback(
                                    newExtractorLink(
                                        source = "AnimesFlix",
                                        name = "AnimesFlix",
                                        url = streamUrl,
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        this.referer = "https://www.animesflix.site/"
                                        this.quality = 1080
                                        this.headers = streamHeaders
                                    }
                                )
                                anySuccess = true
                                break
                            }
                        } catch (getError: Exception) {
                            println("   ❌ GET também falhou: ${getError.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                println("   ❌⚠️ Erro ao testar URL: ${e.message}")
                println("   📋🔍 Tipo do erro: ${e.javaClass.simpleName}")
                if (e.cause != null) {
                    println("   🔍 Causa: ${e.cause?.message}")
                }
            }
        }
        
        if (!anySuccess) {
            println("❌😢 Nenhuma URL funcionou após testar $testedCount URLs")
            println("💡📋 Possíveis problemas:")
            println("   • Slug incorreto ou variações insuficientes")
            println("   • Servidor pode estar fora do ar")
            println("   • Headers podem estar incompletos")
            println("   • URL pode precisar de autenticação")
        } else {
            println("✅🎉 Link encontrado com sucesso após $testedCount tentativas!")
        }
        
        println("🎬🔗 ========== LOAD LINKS FINALIZADO ==========")
        anySuccess
        
    } catch (e: Exception) {
        println("💥❌ ERRO FATAL em loadLinks:")
        println("   📋 Mensagem: ${e.message}")
        println("   📋 Tipo: ${e.javaClass.simpleName}")
        println("   📋 Stack trace:")
        e.stackTrace.take(10).forEach { trace ->
            println("      🔍 $trace")
        }
        println("🎬🔗 ========== LOAD LINKS FALHOU ==========")
        false
    }
}
}
