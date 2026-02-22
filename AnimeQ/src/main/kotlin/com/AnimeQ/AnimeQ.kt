package com.AnimeQ

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.EnumSet

class AnimeQ : MainAPI() {
    override var mainUrl = "https://animeq.net"
    override var name = "AnimeQ"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    // Usa o mesmo interceptor do AnimesCloud
    private val cloudflareInterceptor = CloudflareKiller()

    companion object {
        // USER-AGENT DESKTOP igual ao AnimesCloud
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        
        private val locker = Mutex()
        private var isInitialized = false
        
        // Página de busca
        private const val SEARCH_PATH = "/?s="
        
        // Página de episódios
        private const val EPISODE_PAGE_ITEM = ".item.se.episodes, article.item.se, div.item.se, .episodes article"
        
        // Página de gêneros/categorias
        private const val GENRE_PAGE_ITEM = ".item.tvshows, .item.movies, article.item, div.item, .post, .anime-item"
        
        // Elementos comuns
        private const val ITEM_TITLE = ".data h3 a, h3 a, .title a, a h3"
        private const val ITEM_POSTER = ".poster img, img.poster, .thumb img, img[src*=.jpg], img[src*=.png]"
        private const val ITEM_LINK = "a[href]"
        private const val EPISODE_SERIE = ".data .serie, .serie, .anime-title"
        private const val ANIME_YEAR = ".data span, .year, span.year, .date"
        private const val ANIME_SCORE = ".rating, .score, .imdb, [class*=rating]"
        
        // Página de detalhes do anime
        private const val DETAIL_TITLE = "h1"
        private const val DETAIL_POSTER = ".poster img, .thumbnail img, img.poster"
        private const val DETAIL_SYNOPSIS = ".wp-content p, .sinopse, .description, .entry-content p"
        private const val DETAIL_GENRES = ".sgeneros a[rel=tag], a[rel=tag], .genres a, .tags a"
        private const val DETAIL_YEAR = ".date, .year, span.date"
        private const val DETAIL_SCORE = ".dt_rating_vgs, .rating, .score"
        private const val EPISODE_LIST = ".episodios li .episodiotitle a, .episode-list a, .episodios a"
        private const val EPISODE_IMAGES = ".episodios li .imagen img, .episode-img img"
        private const val EPISODE_NUMBER = ".episodios li .numerando, .episode-number, .number"
    }

    private var persistedCookies: String? = null

    // Headers IDÊNTICOS ao AnimesCloud (DESKTOP)
    private val defaultHeaders: Map<String, String>
        get() = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8",
            "Accept-Encoding" to "gzip, deflate, br",
            "Connection" to "keep-alive",
            "Upgrade-Insecure-Requests" to "1",
            "Sec-Fetch-Dest" to "document",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "same-origin",
            "Sec-Fetch-User" to "?1",
            "Cache-Control" to "max-age=0",
            "Referer" to "$mainUrl/"
        )

    // Headers para imagens (para evitar 403)
    private val imageHeaders: Map<String, String>
        get() = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to mainUrl,
            "Accept" to "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8",
            "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8",
            "Cache-Control" to "no-cache"
        )

    // Função para fixar URL de imagem com headers
    private fun fixImageUrl(url: String?): String? {
        return url?.let {
            if (it.startsWith("http")) {
                // Adiciona headers inline para evitar 403
                it + "|headers=" + imageHeaders.entries.joinToString("&") { 
                    "${it.key}=${it.value}" 
                }
            } else {
                fixUrl(it)
            }
        }
    }

    // Mapeamento de categorias
    private val mainCategories = mapOf(
        "Últimos Episódios" to "$mainUrl/episodio/",
        "Animes Mais Vistos" to "$mainUrl/",
    )

    private val genresMap = mapOf(
        "Ação" to "genre/acao",
        "Aventura" to "genre/aventura", 
        "Animação" to "genre/animacao",
        "Drama" to "genre/drama",
        "Crime" to "genre/crime",
        "Mistério" to "genre/misterio",
        "Fantasia" to "genre/fantasia",
        "Terror" to "genre/terror",
        "Comédia" to "genre/comedia",
        "Romance" to "genre/romance",
        "Sci-Fi" to "genre/ficcao-cientifica",
        "Seinen" to "genre/seinen",
        "Shounen" to "genre/shounen",
        "Ecchi" to "genre/ecchi",
        "Esporte" to "genre/esporte",
        "Sobrenatural" to "genre/sobrenatural",
        "Vida Escolar" to "genre/vida-escolar"
    )

    private val typeMap = mapOf(
        "Legendado" to "tipo/legendado",
        "Dublado" to "tipo/dublado"
    )

    private val specialCategories = mapOf(
        "Filmes" to "filme",
        "Manhwa" to "genre/Manhwa",
        "Donghua" to "genre/Donghua"
    )

    override val mainPage = mainPageOf(
        *mainCategories.map { (name, url) -> url to name }.toTypedArray(),
        *genresMap.map { (genre, slug) -> "$mainUrl/$slug" to genre }.toTypedArray(),
        *typeMap.map { (type, slug) -> "$mainUrl/$slug" to type }.toTypedArray(),
        *specialCategories.map { (cat, slug) -> "$mainUrl/$slug" to cat }.toTypedArray()
    )

    // FUNÇÃO DE REQUEST COM DEBUG
    private suspend fun request(url: String, debugName: String = ""): Document {
        println("🔗 Request to: $url ($debugName)")
        
        // Inicialização
        if (!isInitialized) {
            locker.withLock {
                if (!isInitialized) {
                    try {
                        println("🔄 Inicializando cookies...")
                        val resMain = app.get(
                            mainUrl, 
                            headers = defaultHeaders, 
                            interceptor = cloudflareInterceptor, 
                            timeout = 60
                        )
                        
                        if (resMain.code == 200) {
                            println("✅ Cookies inicializados")
                            val cookieList = mutableListOf<String>()
                            resMain.okhttpResponse.headers("Set-Cookie").forEach { 
                                cookieList.add(it.split(";")[0]) 
                            }
                            resMain.okhttpResponse.request.header("Cookie")?.let { 
                                cookieList.add(it) 
                            }
                            persistedCookies = cookieList.distinct().joinToString("; ")
                            isInitialized = true
                            
                            println("🍪 Cookies persistidos: $persistedCookies")
                        }
                    } catch (e: Exception) {
                        println("❌ Erro inicialização: ${e.message}")
                    }
                }
            }
        }

        // Headers com cookies
        val finalHeaders = defaultHeaders.toMutableMap()
        persistedCookies?.takeIf { it.isNotBlank() }?.let {
            finalHeaders["Cookie"] = it
        }

        println("📡 Fazendo request para: $url")
        val response = app.get(
            url, 
            headers = finalHeaders, 
            interceptor = cloudflareInterceptor,
            timeout = 45
        )
        
        println("📊 Response code: ${response.code}")
        println("📄 Response length: ${response.text.length} chars")
        
        return response.document
    }

    // Funções auxiliares
    private fun cleanTitle(dirtyTitle: String): String {
        return dirtyTitle
            .replace("(?i)\\s*–\\s*todos os epis[oó]dios".toRegex(), "")
            .replace("(?i)\\s*\\(dublado\\)".toRegex(), "")
            .replace("(?i)\\s*\\(legendado\\)".toRegex(), "")
            .replace("(?i)\\s*dublado\\s*$".toRegex(), "")
            .replace("(?i)\\s*legendado\\s*$".toRegex(), "")
            .replace("(?i)\\s*-\\s*epis[oó]dio\\s*\\d+".toRegex(), "")
            .replace("(?i)\\s*–\\s*Epis[oó]dio\\s*\\d+".toRegex(), "")
            .replace("(?i)\\s*epis[oó]dio\\s*\\d+".toRegex(), "")
            .replace("(?i)\\s*Ep\\.\\s*\\d+".toRegex(), "")
            .replace("\\s+".toRegex(), " ")
            .trim()
            .ifBlank { dirtyTitle }
    }

    private fun extractEpisodeNumber(title: String): Int? {
        return listOf(
            "Epis[oó]dio\\s*(\\d+)".toRegex(RegexOption.IGNORE_CASE),
            "Ep\\.?\\s*(\\d+)".toRegex(RegexOption.IGNORE_CASE),
            "E(\\d+)".toRegex(RegexOption.IGNORE_CASE),
            "\\b(\\d{3,})\\b".toRegex(),
            "\\b(\\d{1,3})\\b".toRegex()
        ).firstNotNullOfOrNull { it.find(title)?.groupValues?.get(1)?.toIntOrNull() } ?: 1
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

    private fun isDubbed(title: String): Boolean {
        return title.contains("dublado", true) || 
               title.contains("dublada", true) ||
               title.contains("dublados", true) ||
               title.contains("dubladas", true)
    }

    private fun Element.toEpisodeSearchResponse(): SearchResponse? {
        val href = selectFirst(ITEM_LINK)?.attr("href") ?: return null
        val episodeTitle = selectFirst(ITEM_TITLE)?.text()?.trim() ?: return null
        val animeTitle = extractAnimeTitleFromEpisode(episodeTitle)
        val posterUrl = selectFirst(ITEM_POSTER)?.attr("src")?.let { fixImageUrl(it) }
        val isDubbed = isDubbed(episodeTitle)
        val serieName = selectFirst(EPISODE_SERIE)?.text()?.trim() ?: animeTitle

        val cleanTitle = cleanTitle(serieName)

        return newAnimeSearchResponse(cleanTitle, fixUrl(href)) {
            this.posterUrl = posterUrl
            this.type = TvType.Anime
        }
    }

    private fun Element.toAnimeSearchResponse(): SearchResponse? {
        val href = selectFirst(ITEM_LINK)?.attr("href") ?: return null
        val rawTitle = selectFirst(ITEM_TITLE)?.text()?.trim() ?: return null
        val cleanedTitle = cleanTitle(rawTitle).ifBlank { return null }
        val posterUrl = selectFirst(ITEM_POSTER)?.attr("src")?.let { fixImageUrl(it) }
        val isDubbed = isDubbed(rawTitle)
        val year = selectFirst(ANIME_YEAR)?.text()?.trim()?.toIntOrNull()
        val scoreText = selectFirst(ANIME_SCORE)?.text()?.trim()

        val score = scoreText?.toFloatOrNull()?.let { 
            Score.from10(it)
        }

        val isMovie = href.contains("/filme/") || cleanedTitle.contains("filme", true)
        val type = if (isMovie) TvType.AnimeMovie else TvType.Anime

        return newAnimeSearchResponse(cleanedTitle, fixUrl(href)) {
            this.posterUrl = posterUrl
            this.type = type
            this.year = year
            this.score = score
            if (isDubbed) {
                this.dubStatus = EnumSet.noneOf(DubStatus::class.java).apply {
                    add(DubStatus.Dubbed)
                }
            } else {
                this.dubStatus = EnumSet.noneOf(DubStatus::class.java).apply {
                    add(DubStatus.Subbed)
                }
            }
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val baseUrl = request.data
        var url = baseUrl

        if (page > 1) {
            url = when {
                baseUrl == "$mainUrl/episodio/" -> "$baseUrl/page/$page/"
                baseUrl == "$mainUrl/" -> baseUrl
                baseUrl.contains("/?s=") -> baseUrl.replace("/?s=", "/page/$page/?s=")
                else -> "$baseUrl/page/$page/"
            }
        }

        println("🏠 [MAINPAGE-${request.name}] Carregando página principal - Página: $page, URL base: $baseUrl")
        if (page > 1) println("🏠 [MAINPAGE-${request.name}] URL com paginação: $url")
        
        val document = this.request(url, request.name)

        return when (request.name) {
            "Últimos Episódios" -> {
                val episodeElements = document.select(EPISODE_PAGE_ITEM)
                println("🏠 [MAINPAGE-${request.name}] Encontrados ${episodeElements.size} episódios")
                
                val items = episodeElements
                    .mapNotNull { it.toEpisodeSearchResponse() }
                    .distinctBy { it.url }

                println("🏠 [MAINPAGE-${request.name}] Processados ${items.size} itens")
                newHomePageResponse(
                    list = HomePageList(request.name, items, isHorizontalImages = true),
                    hasNext = episodeElements.isNotEmpty()
                )
            }
            "Animes Mais Vistos" -> {
                val popularItems = mutableListOf<SearchResponse>()

                val sliderItems = document.select("#genre_acao .item.tvshows, #genre_acao .item.movies")
                println("🏠 [MAINPAGE-${request.name}] Encontrados ${sliderItems.size} itens no slider")
                
                popularItems.addAll(sliderItems.take(10).mapNotNull { it.toAnimeSearchResponse() })

                if (popularItems.isEmpty()) {
                    println("🏠 [MAINPAGE-${request.name}] Nenhum item no slider, buscando itens gerais")
                    val allItems = document.select(".item.tvshows, .item.movies")
                        .take(10)
                        .mapNotNull { it.toAnimeSearchResponse() }
                    popularItems.addAll(allItems)
                }

                println("🏠 [MAINPAGE-${request.name}] Processados ${popularItems.size} itens populares")
                newHomePageResponse(
                    list = HomePageList(request.name, popularItems.distinctBy { it.url }, isHorizontalImages = false),
                    hasNext = false
                )
            }
            else -> {
                val isEpisodePage = baseUrl.contains("/episodio/")
                val isGenrePage = baseUrl.contains("/genre/") || 
                                 baseUrl.contains("/tipo/") || 
                                 baseUrl == "$mainUrl/filme/"

                println("🏠 [MAINPAGE-${request.name}] É página de episódio? $isEpisodePage")
                println("🏠 [MAINPAGE-${request.name}] É página de gênero? $isGenrePage")

                val items = if (isEpisodePage) {
                    val elements = document.select(EPISODE_PAGE_ITEM)
                    println("🏠 [MAINPAGE-${request.name}] Encontrados ${elements.size} episódios")
                    elements.mapNotNull { it.toEpisodeSearchResponse() }
                        .distinctBy { it.url }
                } else if (isGenrePage) {
                    val elements = document.select(GENRE_PAGE_ITEM)
                    println("🏠 [MAINPAGE-${request.name}] Encontrados ${elements.size} itens")
                    elements.mapNotNull { it.toAnimeSearchResponse() }
                        .distinctBy { it.url }
                } else {
                    val elements = document.select(".item.tvshows, .item.movies")
                    println("🏠 [MAINPAGE-${request.name}] Encontrados ${elements.size} itens gerais")
                    elements.mapNotNull { it.toAnimeSearchResponse() }
                        .distinctBy { it.url }
                }

                val hasNext = when {
                    isEpisodePage -> document.select(".pagination a").isNotEmpty()
                    isGenrePage -> document.select(".pagination a").isNotEmpty()
                    else -> false
                }

                println("🏠 [MAINPAGE-${request.name}] Tem próxima página? $hasNext")
                newHomePageResponse(
                    list = HomePageList(request.name, items, isHorizontalImages = false),
                    hasNext = hasNext
                )
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 2) return emptyList()

        println("🔍 Pesquisando por: '$query'")
        val document = request("$mainUrl$SEARCH_PATH${query.replace(" ", "+")}", "search_$query")

        val elements = document.select(".item.tvshows, .item.movies, .item.se.episodes, article, .post")
        println("🔎 Encontrados ${elements.size} elementos na pesquisa")
        
        return elements
            .mapNotNull { element ->
                if (element.hasClass("episodes") || element.html().contains("episodio", true)) {
                    element.toEpisodeSearchResponse()
                } else {
                    element.toAnimeSearchResponse()
                }
            }
            .distinctBy { it.url }
            .also { println("✅ Gerados ${it.size} resultados de pesquisa") }
    }

    override suspend fun load(url: String): LoadResponse {
        println("📺 [LOAD] Carregando detalhes de: $url")
        val document = request(url, "details")

        val rawTitle = document.selectFirst(DETAIL_TITLE)?.text()?.trim() ?: "Sem Título"
        val title = cleanTitle(rawTitle)

        val poster = document.selectFirst(DETAIL_POSTER)?.attr("src")?.let { fixImageUrl(it) }
        println("📺 [LOAD] Título: $title")
        println("📺 [LOAD] Poster: $poster")

        var synopsis = "Sinopse não disponível."

        val wpContent = document.selectFirst(".wp-content")
        wpContent?.let { content ->
            val synopsisElements = content.select("p")
            for (element in synopsisElements) {
                val text = element.text()
                if (text.contains("Sinopse:", true)) {
                    synopsis = text.replace("Sinopse:", "").trim()
                    break
                } else if (text.contains("Sinopse", true) && text.length > 50) {
                    synopsis = text.trim()
                    break
                }
            }

            if (synopsis == "Sinopse não disponível." && synopsisElements.isNotEmpty()) {
                for (element in synopsisElements) {
                    val text = element.text().trim()
                    if (text.length > 50 && !text.contains("Título Alternativo") && 
                        !text.contains("Ano de Lançamento")) {
                        synopsis = text
                        break
                    }
                }
            }
        }
        println("📺 [LOAD] Sinopse: ${synopsis.take(100)}...")

        val genres = document.select(DETAIL_GENRES)
            .mapNotNull { it.text().trim() }
            .filter { !it.contains("Letra") && !it.contains("tipo") }
        println("📺 [LOAD] Gêneros: $genres")

        var year: Int? = null
        val yearText = document.selectFirst(DETAIL_YEAR)?.text()?.trim()
        if (yearText != null) {
            val yearMatch = "\\b(\\d{4})\\b".toRegex().find(yearText)
            year = yearMatch?.groupValues?.get(1)?.toIntOrNull()
        }
        println("📺 [LOAD] Ano: $year")

        var score: Score? = null
        val scoreText = document.selectFirst(DETAIL_SCORE)?.text()?.trim()
        if (scoreText != null) {
            val scoreValue = scoreText.toFloatOrNull()
            score = scoreValue?.let { Score.from10(it) }
            println("📺 [LOAD] Nota: $scoreValue")
        } else {
            println("📺 [LOAD] Nota: não disponível")
        }

        val isDubbed = rawTitle.contains("dublado", true) || url.contains("dublado", true)
        val isMovie = url.contains("/filme/") || rawTitle.contains("filme", true)
        println("📺 [LOAD] É filme? $isMovie")
        println("📺 [LOAD] É dublado? $isDubbed")

        val episodesList = if (!isMovie) {
            val episodeElements = document.select(EPISODE_LIST)
            val episodeImages = document.select(EPISODE_IMAGES)
            val episodeNumbers = document.select(EPISODE_NUMBER)

            println("📺 [LOAD] Encontrados ${episodeElements.size} episódios")
            
            episodeElements.mapIndexed { index, element ->
                val episodeTitle = element.text().trim()
                val episodeUrl = element.attr("href")

                var epNumber = extractEpisodeNumber(episodeTitle) ?: (index + 1)

                if (index < episodeNumbers.size) {
                    val numberText = episodeNumbers[index].text().trim()
                    val numberMatch = "\\d+".toRegex().findAll(numberText).lastOrNull()
                    numberMatch?.let {
                        val extractedNumber = it.value.toIntOrNull()
                        if (extractedNumber != null) {
                            epNumber = extractedNumber
                        }
                    }
                }

                var episodePoster: String? = null
                if (index < episodeImages.size) {
                    episodePoster = episodeImages[index].attr("src")?.let { fixImageUrl(it) }
                }

                newEpisode(episodeUrl) {
                    this.name = "Episódio $epNumber"
                    this.episode = epNumber
                    this.posterUrl = episodePoster ?: poster
                }
            }.sortedBy { it.episode }
        } else {
            println("📺 [LOAD] É filme, criando episódio único")
            listOf(newEpisode(url) {
                this.name = "Filme Completo"
                this.episode = 1
                this.posterUrl = poster
            })
        }

        val showStatus = if (isMovie || episodesList.size >= 50) {
            ShowStatus.Completed
        } else {
            ShowStatus.Ongoing
        }
        println("📺 [LOAD] Status: $showStatus")
        println("📺 [LOAD] Total de episódios: ${episodesList.size}")

        return newAnimeLoadResponse(title, url, if (isMovie) TvType.AnimeMovie else TvType.Anime) {
            this.posterUrl = poster
            this.year = year
            this.plot = synopsis
            this.tags = genres
            this.score = score
            this.showStatus = showStatus

            if (episodesList.isNotEmpty()) {
                addEpisodes(if (isDubbed) DubStatus.Dubbed else DubStatus.Subbed, episodesList)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Chama o extractor original do AnimeQ
        return AnimeQVideoExtractor.extractVideoLinks(data, callback = callback)
    }
}
