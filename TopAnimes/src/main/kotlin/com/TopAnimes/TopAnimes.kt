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
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)
    override val usesWebView = false

    companion object {
        private const val SEARCH_PATH = "/?s="

        private val loadingMutex = Mutex()

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

        private val FIXED_CATEGORIES = listOf(
            "/animes" to "Todos os Animes",
            "/episodio" to "Episódios Recentes",
            "/tipo/legendado" to "Legendados",
            "/tipo/dublado" to "Dublados",
            "/tipo/donghua" to "Donghua"
        )

        private val ALL_RANDOM_CATEGORIES = listOf(
            // Gêneros Populares
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
            
            // Gêneros Demográficos e Estilos
            "/genero/slice-of-life" to "Slice of Life",
            "/genero/escolar" to "Escolar",
            "/genero/esportes" to "Esportes",
            "/genero/artes-marciais" to "Artes Marciais",
            "/genero/militar" to "Militar",
            "/genero/seinen" to "Seinen",
            "/genero/shounen" to "Shounen",
            "/genero/shoujo" to "Shoujo",
            
            // Gêneros por Tema
            "/genero/psicologico" to "Psicológico",
            "/genero/suspense" to "Suspense",
            "/genero/thriller" to "Thriller",
            "/genero/crime" to "Crime",
            "/genero/historico" to "Histórico",
            "/genero/mitologia" to "Mitologia",
            "/genero/musica" to "Música",
            "/genero/gourmet" to "Gourmet"
        )

        private var cachedRandomCategories: List<Pair<String, String>>? = null
        private var cacheTime: Long = 0
        private const val CACHE_DURATION = 300000L

        fun getCombinedCategories(): List<Pair<String, String>> {
            val currentTime = System.currentTimeMillis()
            
            if (cachedRandomCategories != null && (currentTime - cacheTime) < CACHE_DURATION) {
                return FIXED_CATEGORIES + cachedRandomCategories!!
            }
            
            val randomCategories = ALL_RANDOM_CATEGORIES
                .shuffled()
                .take(10)
                .distinctBy { it.first }
            
            cachedRandomCategories = randomCategories
            cacheTime = currentTime
            
            return FIXED_CATEGORIES + randomCategories
        }
    }

    override val mainPage = mainPageOf(
        *getCombinedCategories().map { (path, name) -> 
            "$mainUrl$path" to name 
        }.toTypedArray()
    )

    private fun extractPoster(element: Element): String? {
        return try {
            // Tenta meta tag primeiro
            val metaImage = element.selectFirst("meta[property='og:image']")?.attr("content")
                ?.takeIf { it.isNotBlank() }
                ?.let { fixUrl(it) }
            if (metaImage != null) return metaImage

            // Tenta img com data-src
            val dataSrc = element.selectFirst("img[data-src]")?.attr("data-src")
                ?.takeIf { it.isNotBlank() }
                ?.let { fixUrl(it) }
            if (dataSrc != null) return dataSrc

            // Tenta img com src
            val srcImage = element.selectFirst("img[src]")?.attr("src")
                ?.takeIf { it.isNotBlank() }
                ?.let { fixUrl(it) }
            if (srcImage != null) return srcImage

            // Tenta background image do style
            val style = element.attr("style")
            if (style.contains("background-image")) {
                val bgRegex = Regex("""url\(['"]?([^'"()]+)['"]?\)""")
                val match = bgRegex.find(style)
                match?.groupValues?.get(1)?.let { bgUrl ->
                    return fixUrl(bgUrl)
                }
            }

            null
        } catch (e: Exception) {
            null
        }
    }

    private fun extractDetailPoster(document: org.jsoup.nodes.Document): String? {
        return try {
            // Meta tag primeiro
            val metaImage = document.selectFirst("meta[property='og:image']")?.attr("content")
                ?.takeIf { it.isNotBlank() }
                ?.let { fixUrl(it) }
            if (metaImage != null) return metaImage

            // Seletores específicos do TopAnimes
            val posterSelectors = listOf(
                ".poster img",
                ".sheader .poster img",
                ".cover img",
                "img.poster",
                ".anime-poster img",
                ".sbox img"
            )

            for (selector in posterSelectors) {
                document.selectFirst(selector)?.let { img ->
                    val src = when {
                        img.hasAttr("src") -> img.attr("src")
                        img.hasAttr("data-src") -> img.attr("data-src")
                        else -> null
                    }

                    src?.takeIf { it.isNotBlank() }?.let { 
                        return fixUrl(it)
                    }
                }
            }

            // Tenta qualquer img que possa ser poster
            document.selectFirst("img[src*='/animes/'], img[src*='/covers/'], img[src*='tmdb']")
                ?.attr("src")
                ?.takeIf { it.isNotBlank() }
                ?.let { return fixUrl(it) }

            null
        } catch (e: Exception) {
            null
        }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        return try {
            // Primeiro tenta a tag <a> dentro do elemento
            val linkElement = selectFirst("a")
            val href = linkElement?.attr("href") ?: return null
            
            if (href.isBlank() || (!href.contains("/animes/") && !href.contains("/filmes/"))) {
                return null
            }

            // Extrai título
            val titleElement = selectFirst("h3, .title, .serie, .data h3, .entry-title") 
                ?: linkElement.selectFirst("h3, .title")
                ?: return null
            
            var rawTitle = titleElement.text().trim()
            if (rawTitle.isBlank()) {
                rawTitle = linkElement.attr("title")?.trim() ?: ""
            }
            if (rawTitle.isBlank()) {
                rawTitle = attr("title")?.trim() ?: ""
            }

            if (rawTitle.isBlank()) return null

            // Extrai score
            val scoreResult = extractScoreAdvanced(this)
            val scoreText = scoreResult.first
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

            // Extrai ano
            val yearElement = selectFirst(".year, .date, span:last-child")
            val yearText = yearElement?.text()?.trim()
            val year = extractYear(yearText)

            return newAnimeSearchResponse(cleanName, fixUrl(href)) {
                this.posterUrl = sitePoster
                this.type = if (isMovie) TvType.AnimeMovie else TvType.Anime
                this.score = score
                this.year = year

                if (finalHasDub || finalHasLeg) {
                    addDubStatus(dubExist = finalHasDub, subExist = finalHasLeg)
                }
            }

        } catch (e: Exception) {
            null
        }
    }

    private fun extractScoreAdvanced(element: Element): Pair<String?, String?> {
        val selectors = listOf(
            ".rating",
            ".score",
            ".data .rating",
            ".data .score",
            "span:contains(★)",
            "span:contains(/10)",
            "[class*='rating']",
            "[class*='score']",
            ".dt_rating_vgs"
        )

        for (selector in selectors) {
            val found = element.selectFirst(selector)?.text()?.trim()
            if (!found.isNullOrBlank() && isScoreLike(found)) {
                return found to selector
            }
        }

        element.parent()?.let { parent ->
            for (selector in selectors) {
                val found = parent.selectFirst(selector)?.text()?.trim()
                if (!found.isNullOrBlank() && isScoreLike(found)) {
                    return found to "parent.$selector"
                }
            }
        }

        val html = element.outerHtml()
        val scoreRegexes = listOf(
            Regex("""(\d+\.\d+|\d+)\s*(?:★|/10)"""),
            Regex("""class="[^"]*(?:rating|score)[^"]*">([^<]+)""")
        )

        for (regex in scoreRegexes) {
            val match = regex.find(html)
            if (match != null) {
                val found = match.groupValues[1].trim()
                if (isScoreLike(found)) {
                    return found to "regex"
                }
            }
        }

        return null to null
    }

    private fun isScoreLike(text: String): Boolean {
        return when {
            text.equals("N/A", ignoreCase = true) -> true
            text.matches(Regex("""^\d+(\.\d+)?$""")) -> true
            text.matches(Regex("""^\d+(\.\d+)?/10$""")) -> true
            text.contains("★") -> true
            else -> false
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
            val pagination = document.select(".pagination, .page-numbers, .resppages")
            if (pagination.isNotEmpty()) {
                val nextPageLink = pagination.select("a[href*='page/${currentPageNum + 1}'], a:contains('Próxima'), a:contains('›'), .next")
                return nextPageLink.isNotEmpty()
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

                val document = app.get(pageUrl, timeout = 20).document
                
                // Seletores otimizados para TopAnimes
                val elements = document.select("""
                    a[href*="/animes/"],
                    a[href*="/filmes/"],
                    article a,
                    .items a,
                    .episodes a
                """).take(40) // Limita a 40 itens para performance

                val homeItems = mutableListOf<SearchResponse>()

                elements.forEach { element ->
                    try {
                        val item = element.toSearchResponse()
                        if (item != null && !homeItems.any { it.url == item.url }) {
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

            document.select("a[href*='/animes/'], a[href*='/filmes/']")
                .mapNotNull { it.toSearchResponse() }
                .distinctBy { it.url }
                .take(30)

        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun extractGenres(document: org.jsoup.nodes.Document): List<String> {
        val genres = mutableListOf<String>()
        
        document.select(".sgeneros a, .generos a, .animeInfo a[href*='/genero/']").forEach { element ->
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
                    title.contains("dublado", ignoreCase = true) ||
                    document.selectFirst(".sgeneros")?.text()?.contains("dublado", ignoreCase = true) == true
        val hasSub = !hasDub || url.contains("legendado", ignoreCase = true) || 
                    title.contains("legendado", ignoreCase = true) ||
                    document.selectFirst(".sgeneros")?.text()?.contains("legendado", ignoreCase = true) == true
        
        return Pair(hasDub, hasSub)
    }

    private fun extractScoreFromDocument(document: org.jsoup.nodes.Document): Float? {
        val selectors = listOf(
            ".rating-value",
            ".dt_rating_vgs strong",
            "#repimdb strong",
            ".mtipo .rating-value",
            "meta[itemprop='ratingValue']"
        )
        
        for (selector in selectors) {
            val text = document.selectFirst(selector)?.text()?.trim()
            if (!text.isNullOrBlank()) {
                return text.toFloatOrNull()
            }
        }
        
        document.selectFirst("meta[itemprop='ratingValue']")?.attr("content")?.let {
            return it.toFloatOrNull()
        }
        
        return null
    }

    override suspend fun load(url: String): LoadResponse {
        return try {
            val document = app.get(url, timeout = 30).document

            val title = document.selectFirst("h1")?.text()?.trim() 
                ?: document.selectFirst(".data h1")?.text()?.trim() 
                ?: document.selectFirst(".sheader h1")?.text()?.trim()
                ?: "Sem Título"

            val poster = extractDetailPoster(document)
            val background = poster

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
                if (isMovie) TvType.AnimeMovie else TvType.Anime
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.year = year
                this.plot = synopsis
                this.tags = genres
                this.score = score
                this.showStatus = showStatus

                if (episodes.isNotEmpty()) {
                    // Verifica se tem dublado e legendado
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
                this.plot = "Não foi possível carregar esta página."
            }
        }
    }

    private fun extractEpisodesFromDocument(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()

        // Seletores otimizados para TopAnimes
        val episodeElements = document.select("""
            .episodios li,
            .se-a ul li,
            .episode-item,
            [class*='episodios'] li,
            li[data-id],
            .mark-
        """).take(100) // Limita a 100 episódios

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
                // Ignora episódios com erro
            }
        }

        return episodes.sortedBy { it.episode }.take(100) // Garante máximo 100 episódios
    }

    private fun extractEpisodeNumberFromElement(element: Element, href: String): Int? {
        // Tenta extrair do elemento .numerando
        val numberElement = element.selectFirst(".numerando, .epnumber, .num")
        
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

        // Tenta extrair da classe mark-
        val classAttr = element.attr("class")
        val markMatch = Regex("""mark-(\d+)""").find(classAttr)
        if (markMatch != null) {
            return markMatch.groupValues[1].toIntOrNull()
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return false
    }
}
