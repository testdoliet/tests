package com.TopAnimes

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app
import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.nodes.Element

@CloudstreamPlugin
class TopAnimesPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(TopAnimes())
    }
}

class TopAnimes : MainAPI() {
    override var mainUrl = "https://topanimes.net"
    override var name = "TopAnimes"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie, TvType.OVA)
    override val usesWebView = false

    companion object {
        private const val SEARCH_PATH = "/?s="
        private val loadingMutex = Mutex()

        // Função para mapear status
        private fun getStatus(statusText: String): ShowStatus {
            return when {
                statusText.contains("em andamento", ignoreCase = true) ||
                statusText.contains("lançando", ignoreCase = true) ||
                statusText.contains("lançamento", ignoreCase = true) ||
                statusText.contains("ongoing", ignoreCase = true) -> ShowStatus.Ongoing
                
                statusText.contains("concluído", ignoreCase = true) ||
                statusText.contains("completo", ignoreCase = true) ||
                statusText.contains("finalizado", ignoreCase = true) ||
                statusText.contains("completed", ignoreCase = true) -> ShowStatus.Completed
                
                else -> ShowStatus.Completed
            }
        }

        // Categorias fixas (5 abas fixas + 15 gêneros mais comuns)
        private val FIXED_CATEGORIES = listOf(
            "/animes" to "Todos os Animes",
            "/episodio" to "Episódios Recentes",
            "/tipo/legendado" to "Legendados",
            "/tipo/dublado" to "Dublados",
            "/tipo/donghua" to "Donghua"
        )

        // 15 gêneros mais comuns (baseado em sites de anime)
        private val POPULAR_GENRES = listOf(
            "/genero/acao" to "Ação",
            "/genero/aventura" to "Aventura",
            "/genero/comedia" to "Comédia",
            "/genero/drama" to "Drama",
            "/genero/fantasia" to "Fantasia",
            "/genero/romance" to "Romance",
            "/genero/ficcao-cientifica" to "Ficção Científica",
            "/genero/misterio" to "Mistério",
            "/genero/terror" to "Terror",
            "/genero/sobrenatural" to "Sobrenatural",
            "/genero/slice-of-life" to "Slice of Life",
            "/genero/escolar" to "Escolar",
            "/genero/esportes" to "Esportes",
            "/genero/seinen" to "Seinen",
            "/genero/shounen" to "Shounen"
        )

        // Todas as categorias combinadas
        private val ALL_CATEGORIES = FIXED_CATEGORIES + POPULAR_GENRES
    }

    override val mainPage = mainPageOf(
        *ALL_CATEGORIES.map { (path, name) -> 
            "$mainUrl$path" to name 
        }.toTypedArray()
    )

    private fun extractPoster(element: Element): String? {
        return try {
            // No HTML, os posters estão em .poster img com src do TMDB
            val img = element.selectFirst(".poster img")
            if (img != null) {
                // Primeiro tenta src normal
                var src = img.attr("src")
                if (src.isNullOrBlank()) {
                    // Tenta data-src
                    src = img.attr("data-src")
                }
                
                if (!src.isNullOrBlank()) {
                    // O site usa imagens do TMDB como "https://image.tmdb.org/t/p/w185/..."
                    // Vamos garantir que a URL esteja completa
                    return when {
                        src.startsWith("//") -> "https:$src"
                        src.startsWith("/") -> "$mainUrl$src"
                        src.startsWith("http") -> src
                        else -> fixUrl(src)
                    }
                }
            }

            // Fallback: tenta qualquer imagem dentro do elemento
            element.selectFirst("img")?.let { imgElement ->
                var src = imgElement.attr("src") ?: imgElement.attr("data-src")
                if (!src.isNullOrBlank()) {
                    return when {
                        src.startsWith("//") -> "https:$src"
                        src.startsWith("/") -> "$mainUrl$src"
                        src.startsWith("http") -> src
                        else -> fixUrl(src)
                    }
                }
            }

            null
        } catch (e: Exception) {
            null
        }
    }

    private fun extractDetailPoster(document: org.jsoup.nodes.Document): String? {
        return try {
            // Primeiro tenta meta tag og:image
            val metaImage = document.selectFirst("meta[property='og:image']")?.attr("content")
                ?.takeIf { it.isNotBlank() }
                ?.let { fixUrl(it) }
            if (metaImage != null) return metaImage

            // Tenta o poster na página de detalhes
            val posterElement = document.selectFirst(".sheader .poster img, .poster img, .anime-poster img")
            posterElement?.let { img ->
                var src = img.attr("src")
                if (src.isNullOrBlank()) {
                    src = img.attr("data-src")
                }
                
                if (!src.isNullOrBlank()) {
                    return when {
                        src.startsWith("//") -> "https:$src"
                        src.startsWith("/") -> "$mainUrl$src"
                        src.startsWith("http") -> src
                        else -> fixUrl(src)
                    }
                }
            }

            // Fallback para qualquer imagem relevante
            document.selectFirst("img[src*='tmdb.org'], img[src*='/w'], img[alt*='poster'], img[alt*='Poster']")?.let { img ->
                var src = img.attr("src")
                if (src.isNullOrBlank()) {
                    src = img.attr("data-src")
                }
                
                if (!src.isNullOrBlank()) {
                    return when {
                        src.startsWith("//") -> "https:$src"
                        src.startsWith("/") -> "$mainUrl$src"
                        src.startsWith("http") -> src
                        else -> fixUrl(src)
                    }
                }
            }

            null
        } catch (e: Exception) {
            null
        }
    }

    private fun Element.toSearchResponse(): AnimeSearchResponse? {
        // Verifica se é um elemento article com classe .item
        if (!(hasClass("item") || tagName() == "article")) {
            return null
        }

        // Tenta encontrar o link dentro do elemento
        val linkElement = selectFirst("a[href*='/animes/'], a[href*='/filmes/']") ?: return null
        val href = linkElement.attr("href") ?: return null
        
        if (href.isBlank()) return null

        // Extrai título do elemento .data h3
        val titleElement = selectFirst(".data h3, h3") ?: return null
        val rawTitle = titleElement.text().trim()

        if (rawTitle.isBlank()) return null

        // Extrai ano
        val yearElement = selectFirst(".data span:last-child, span:last-child")
        val yearText = yearElement?.text()?.trim()
        val year = extractYear(yearText)

        // Extrai score (remove a badge)
        val scoreElement = selectFirst(".rating")
        val scoreText = scoreElement?.text()?.trim()
        val score = when {
            scoreText == null || scoreText == "N/A" -> null
            else -> scoreText.toFloatOrNull()?.let { Score.from10(it) }
        }

        // Determina se é dublado ou legendado
        val hasExplicitDub = rawTitle.contains("dublado", ignoreCase = true) || 
                            href.contains("dublado", ignoreCase = true)
        val hasExplicitLeg = rawTitle.contains("legendado", ignoreCase = true) || 
                            href.contains("legendado", ignoreCase = true)

        val finalHasDub: Boolean
        val finalHasLeg: Boolean

        when {
            hasExplicitDub && !hasExplicitLeg -> {
                finalHasDub = true
                finalHasLeg = false
            }
            !hasExplicitDub && hasExplicitLeg -> {
                finalHasDub = false
                finalHasLeg = true
            }
            hasExplicitDub && hasExplicitLeg -> {
                finalHasDub = true
                finalHasLeg = true
            }
            else -> {
                finalHasDub = false
                finalHasLeg = true
            }
        }

        val cleanName = extractAnimeName(rawTitle)
        val isMovie = href.contains("/filmes/") || 
                     rawTitle.contains("filme", ignoreCase = true) ||
                     rawTitle.contains("movie", ignoreCase = true)
        val sitePoster = extractPoster(this)

        return newAnimeSearchResponse(cleanName, fixUrl(href)) {
            this.posterUrl = sitePoster
            this.type = if (isMovie) TvType.Movie else TvType.Anime
            this.score = score
            this.year = year

            if (finalHasDub || finalHasLeg) {
                addDubStatus(dubExist = finalHasDub, subExist = finalHasLeg)
            }
        }
    }

    private fun extractAnimeName(fullText: String): String {
        var cleanName = fullText

        val patterns = listOf(
            Regex("(?i)\\(dublado\\)"),
            Regex("(?i)\\(legendado\\)"),
            Regex("(?i)dublado"),
            Regex("(?i)legendado"),
            Regex("(?i)todos os episódios"),
            Regex("\\s*-\\s*$"),
            Regex("\\(\\d{4}\\)"),
            Regex("Episódio \\d+"),
            Regex("\\s*\\[.*?\\]"),
            Regex("Filme"),
            Regex("Movie")
        )

        patterns.forEach { pattern ->
            cleanName = cleanName.replace(pattern, "")
        }

        return cleanName.trim().replace(Regex("\\s+"), " ")
    }

    private fun extractYear(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val yearRegex = Regex("""(\d{4})""")
        val match = yearRegex.find(text)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    private suspend fun detectHasNextPage(document: org.jsoup.nodes.Document, currentPageNum: Int): Boolean {
        return try {
            // Verifica se há paginação
            val pagination = document.select(".pagination")
            if (pagination.isNotEmpty()) {
                // Verifica se há link para próxima página
                val hasNextLink = pagination.select("a[href*='page/${currentPageNum + 1}'], a:contains(Próxima), .arrow_pag").isNotEmpty()
                return hasNextLink
            }
            
            // Verifica se há botões de resppages
            val respPages = document.select(".resppages a")
            if (respPages.isNotEmpty()) {
                return true
            }
            
            false
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return loadingMutex.withLock {
            try {
                val basePath = request.data.removePrefix(mainUrl)
                val sitePageNumber = page + 1

                val pageUrl = if (sitePageNumber == 1) {
                    "$mainUrl$basePath"
                } else {
                    "$mainUrl$basePath/page/$sitePageNumber"
                }

                val document = app.get(pageUrl, timeout = 30).document
                
                // Seletores baseados no HTML
                val elements = document.select("""
                    article.item.tvshows,
                    article.item.movies,
                    .items.full article.item
                """)

                val homeItems = mutableListOf<SearchResponse>()

                elements.forEach { element ->
                    try {
                        val item = element.toSearchResponse()
                        if (item != null) {
                            homeItems.add(item)
                        }
                    } catch (e: Exception) {
                        // Ignora erros individuais
                    }
                }

                val hasNextPage = detectHasNextPage(document, sitePageNumber)

                newHomePageResponse(
                    request.name,
                    homeItems.distinctBy { it.url },
                    hasNext = hasNextPage
                )

            } catch (e: Exception) {
                newHomePageResponse(request.name, emptyList(), false)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 2) return emptyList()

        val searchUrl = "$mainUrl$SEARCH_PATH${query.trim().replace(" ", "+")}"

        return try {
            val document = app.get(searchUrl, timeout = 15).document

            document.select("article.item.tvshows, article.item.movies")
                .mapNotNull { it.toSearchResponse() }
                .distinctBy { it.url }
                .take(30)

        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun extractGenres(document: org.jsoup.nodes.Document): List<String> {
        val genres = mutableListOf<String>()
        
        document.select(".sgeneros a").forEach { element ->
            element.text().trim().takeIf { it.isNotBlank() }?.let { 
                genres.add(it) 
            }
        }

        if (genres.isEmpty()) {
            document.select(".custom_fields:contains(Gênero) span.valor, .animeInfo:contains(Gênero) span")
                .firstOrNull()
                ?.text()
                ?.trim()
                ?.split(",", ";")
                ?.forEach { genre ->
                    genre.trim().takeIf { it.isNotBlank() }?.let { 
                        genres.add(it) 
                    }
                }
        }

        return genres
    }

    private fun extractAudioType(document: org.jsoup.nodes.Document): Pair<Boolean, Boolean> {
        val url = document.location() ?: ""
        val title = document.selectFirst("h1")?.text() ?: ""
        
        val hasDub = url.contains("dublado", ignoreCase = true) || 
                    title.contains("dublado", ignoreCase = true)
        val hasSub = !hasDub || url.contains("legendado", ignoreCase = true) || 
                    title.contains("legendado", ignoreCase = true)
        
        return Pair(hasDub, hasSub)
    }

    private fun extractScoreFromDocument(document: org.jsoup.nodes.Document): Float? {
        val selectors = listOf(
            ".rating-value",
            ".starstruck .rating-value",
            ".mtipo .rating-value",
            "#repimdb strong",
            ".dt_rating_vgs",
            "meta[itemprop='ratingValue']"
        )
        
        for (selector in selectors) {
            val text = document.selectFirst(selector)?.text()?.trim()
            if (!text.isNullOrBlank()) {
                return text.toFloatOrNull()
            }
        }
        
        // Tenta extrair do atributo content
        document.selectFirst("meta[itemprop='ratingValue']")?.attr("content")?.let {
            return it.toFloatOrNull()
        }
        
        return null
    }

    override suspend fun load(url: String): LoadResponse {
        return try {
            val document = app.get(url, timeout = 40).document

            val title = document.selectFirst("h1")?.text()?.trim() 
                ?: document.selectFirst(".data h1")?.text()?.trim() 
                ?: document.selectFirst(".sheader h1")?.text()?.trim()
                ?: "Sem Título"

            val poster = extractDetailPoster(document)
            val background = document.selectFirst("meta[property='og:image']")?.attr("content")
                ?.takeIf { it.isNotBlank() }
                ?.let { fixUrl(it) }

            val synopsis = document.selectFirst(".wp-content p")?.text()?.trim()
                ?: document.selectFirst(".sbox .wp-content")?.text()?.trim()
                ?: document.selectFirst(".synopsis")?.text()?.trim()
                ?: document.selectFirst("[itemprop='description']")?.text()?.trim()
                ?: "Sinopse não disponível."

            val yearText = document.selectFirst(".extra .date")?.text()?.trim()
                ?: document.selectFirst(".custom_fields:contains(Ano) span.valor")?.text()?.trim()
            val year = extractYear(yearText)

            val statusElement = document.selectFirst(".custom_fields:contains(Status) span.valor")
            val statusText = statusElement?.text()?.trim() ?: "Desconhecido"
            val showStatus = getStatus(statusText)

            val genres = extractGenres(document)

            val (hasDub, hasSub) = extractAudioType(document)

            val score = extractScoreFromDocument(document)?.let { Score.from10(it) }

            val isMovie = url.contains("/filmes/") || 
                         title.contains("filme", ignoreCase = true) ||
                         document.selectFirst(".custom_fields:contains(Tipo)")?.text()?.contains("Filme", ignoreCase = true) == true

            val episodes = extractEpisodesFromDocument(document, url)

            val response = newAnimeLoadResponse(
                extractAnimeName(title), 
                url, 
                if (isMovie) TvType.Movie else TvType.Anime
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.year = year
                this.plot = synopsis
                this.tags = genres
                this.score = score
                this.showStatus = showStatus

                if (episodes.isNotEmpty()) {
                    if (hasDub && hasSub) {
                        // Primeiro adiciona os episódios legendados
                        addEpisodes(DubStatus.Subbed, episodes)
                        
                        // Depois duplica para dublado
                        val dubbedEpisodes = episodes.map { episode ->
                            newEpisode(episode.data) {
                                this.name = "${episode.name} (Dublado)"
                                this.season = episode.season
                                this.episode = episode.episode
                                this.posterUrl = episode.posterUrl
                            }
                        }
                        addEpisodes(DubStatus.Dubbed, dubbedEpisodes)
                    } else if (hasDub) {
                        // Apenas dublado
                        addEpisodes(DubStatus.Dubbed, episodes)
                    } else {
                        // Apenas legendado (padrão)
                        addEpisodes(DubStatus.Subbed, episodes)
                    }
                }
            }

            response

        } catch (e: Exception) {
            newAnimeLoadResponse("Erro ao carregar", url, TvType.Anime) {
                this.plot = "Não foi possível carregar esta página: ${e.message}"
            }
        }
    }

    private fun extractEpisodesFromDocument(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()

        // Seletores para episódios
        val episodeElements = document.select("""
            .episodios li,
            .se-a ul.episodios li,
            [class*='episodios'] li,
            .mark-,
            .episode-item,
            li[data-id]
        """)

        if (episodeElements.isEmpty()) {
            return emptyList()
        }

        episodeElements.forEachIndexed { index, element ->
            try {
                val linkElement = element.selectFirst("a")
                val href = linkElement?.attr("href") ?: return@forEachIndexed
                
                if (href.isBlank()) return@forEachIndexed

                var episodeTitle = linkElement.text().trim()
                if (episodeTitle.isBlank()) {
                    episodeTitle = element.selectFirst(".episodiotitle a")?.text()?.trim() ?: ""
                }
                
                if (episodeTitle.isBlank()) {
                    episodeTitle = "Episódio ${index + 1}"
                }

                val episodeNumber = extractEpisodeNumberFromElement(element, href) ?: (index + 1)

                val fixedHref = when {
                    href.startsWith("//") -> "https:$href"
                    href.startsWith("/") -> "$mainUrl$href"
                    !href.startsWith("http") -> "$mainUrl/$href"
                    else -> href
                }

                val episode = newEpisode(fixedHref) {
                    this.name = episodeTitle
                    this.episode = episodeNumber
                    this.season = 1
                }

                episodes.add(episode)

            } catch (e: Exception) {
                // Ignora erros
            }
        }

        return episodes.sortedBy { it.episode }
    }

    private fun extractEpisodeNumberFromElement(element: Element, href: String): Int? {
        // Tenta extrair do elemento .numerando
        val numberElement = element.selectFirst(".numerando, .epnumber")
        
        numberElement?.text()?.let { text ->
            val patterns = listOf(
                Regex("""(\d+)\s*-\s*\d+"""),
                Regex("""\b(\d+)\b"""),
                Regex("""Epis[oó]dio\s*(\d+)""", RegexOption.IGNORE_CASE)
            )
            
            for (pattern in patterns) {
                val match = pattern.find(text)
                if (match != null) {
                    return match.groupValues[1].toIntOrNull()
                }
            }
        }

        // Tenta extrair da URL
        val urlPatterns = listOf(
            Regex("""/episodio/.+?-episodio-(\d+)"""),
            Regex("""episodio-(\d+)"""),
            Regex("""/episodio/(\d+)""")
        )
        
        for (pattern in urlPatterns) {
            val match = pattern.find(href)
            if (match != null) {
                return match.groupValues[1].toIntOrNull()
            }
        }

        return null
    }

    // LoadLinks desativado por enquanto
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return false
    }
}
