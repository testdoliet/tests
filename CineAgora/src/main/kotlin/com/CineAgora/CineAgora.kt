package com.CineAgora

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Element
import com.fasterxml.jackson.annotation.JsonProperty
import org.json.JSONObject

class CineAgora : MainAPI() {
    override var mainUrl = "https://cineagora.net"
    override var name = "CineAgora"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val usesWebView = false

    // Configuração TMDB
    private val tmdbImageUrl = "https://image.tmdb.org/t/p"
    private val TMDB_API_KEY = BuildConfig.TMDB_API_KEY
    private val TMDB_ACCESS_TOKEN = BuildConfig.TMDB_ACCESS_TOKEN

    companion object {
        // Seções que estão na página principal (com URLs específicas)
        private val HOME_SECTIONS = listOf(
            "ultimos-filmes" to "Últimos Filmes",
            "ultimas-series" to "Últimas Séries"
        )
        
        // Seções com URLs específicas
        private val SECTION_URLS = mapOf(
            // Links específicos para as seções da home
            "ultimos-filmes" to "https://cineagora.net/filmes-hd-online/",
            "ultimas-series" to "https://cineagora.net/series-online-hd-gratis/",
            // Outras seções
            "filmes-populares" to "https://cineagora.net/filmes-hd-online/filmes-populares-hd/",
            "series-populares" to "https://cineagora.net/series-online-hd-gratis/series-populares-hd/",
            "netflix" to "https://cineagora.net/netflix/",
            "paramount" to "https://cineagora.net/paramount/",
            "disney" to "https://cineagora.net/disney/",
            "apple" to "https://cineagora.net/apple/",
            "hbo" to "https://cineagora.net/hbo/",
            "acao" to "https://cineagora.net/filmes-hd-online/filmes-de-acao-hd/",
            "aventura" to "https://cineagora.net/filmes-hd-online/filmes-de-aventura-gratis/",
            "animacao" to "https://cineagora.net/filmes-hd-online/filmes-de-animacao-online/",
            "biograficos" to "https://cineagora.net/filmes-hd-online/assistir-filmes-biograficos/",
            "comedia" to "https://cineagora.net/filmes-hd-online/comedia-filmes-online/",
            "crime" to "https://cineagora.net/filmes-hd-online/crime-filmes-online/",
            "documentarios" to "https://cineagora.net/filmes-hd-online/documentarios-em-portugues/",
            "esporte" to "https://cineagora.net/filmes-hd-online/filmes-de-esporte-hd/",
            "drama" to "https://cineagora.net/filmes-hd-online/filmes-drama-online-hd/",
            "familia" to "https://cineagora.net/filmes-hd-online/filmes-familia-online/",
            "fantasia" to "https://cineagora.net/filmes-hd-online/filmes-fantasia-magia/",
            "historicos" to "https://cineagora.net/filmes-hd-online/filmes-historicos-hd/",
            "terror" to "https://cineagora.net/filmes-hd-online/filmes-terror-horror/",
            "musicais" to "https://cineagora.net/filmes-hd-online/filmes-musicais-online/",
            "misterio" to "https://cineagora.net/filmes-hd-online/filmes-misterio-suspense/",
            "romanticos" to "https://cineagora.net/filmes-hd-online/filmes-romanticos-online/",
            "suspense" to "https://cineagora.net/filmes-hd-online/filmes-suspense-hd/",
            "sci-fi" to "https://cineagora.net/filmes-hd-online/ficcao-cientifica-hd/",
            "tv" to "https://cineagora.net/filmes-hd-online/filmes-para-tv-hd/",
            "thriller" to "https://cineagora.net/filmes-hd-online/thriller-suspense-online/",
            "guerra" to "https://cineagora.net/filmes-hd-online/filmes-guerra-epicas/",
            "faroeste" to "https://cineagora.net/filmes-hd-online/filmes-faroeste-online/"
        )
    }

    override val mainPage = mainPageOf(
        *HOME_SECTIONS.map { (section, name) -> 
            "home_$section" to name 
        }.toTypedArray(),
        *SECTION_URLS.filterKeys { it !in HOME_SECTIONS.map { it.first } }
                     .map { (section, _) ->
                         "section_$section" to getSectionName(section)
                     }.toTypedArray()
    )

    private fun getSectionName(section: String): String {
        return when (section) {
            "ultimos-filmes" -> "Últimos Filmes"
            "ultimas-series" -> "Últimas Séries"
            "filmes-populares" -> "Filmes Populares"
            "series-populares" -> "Séries Populares"
            "netflix" -> "Netflix"
            "paramount" -> "Paramount+"
            "disney" -> "Disney+"
            "apple" -> "Apple TV+"
            "hbo" -> "HBO Max"
            "acao" -> "Ação"
            "aventura" -> "Aventura"
            "animacao" -> "Animação"
            "biograficos" -> "Biográficos"
            "comedia" -> "Comédia"
            "crime" -> "Crime"
            "documentarios" -> "Documentários"
            "esporte" -> "Esporte"
            "drama" -> "Drama"
            "familia" -> "Família"
            "fantasia" -> "Fantasia"
            "historicos" -> "Históricos"
            "terror" -> "Terror"
            "musicais" -> "Musicais"
            "misterio" -> "Mistério"
            "romanticos" -> "Românticos"
            "suspense" -> "Suspense"
            "sci-fi" -> "Sci-Fi"
            "tv" -> "TV"
            "thriller" -> "Thriller"
            "guerra" -> "Guerra"
            "faroeste" -> "Faroeste"
            else -> section.replace("-", " ").split(" ").joinToString(" ") { 
                it.replaceFirstChar { char -> char.uppercase() }
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sectionId = request.data.removePrefix("home_").removePrefix("section_")
        
        // Usar URL específica para cada seção
        val baseUrl = SECTION_URLS[sectionId] ?: mainUrl
        
        // Verificar se a página atual é maior que 1 para adicionar /page/N/
        val url = if (page > 1) {
            // Verificar se a URL base já tem uma barra no final
            val cleanUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
            "$cleanUrl/page/$page/"
        } else {
            baseUrl
        }
        
        val document = try {
            app.get(url).document
        } catch (e: Exception) {
            // Se falhar na paginação, pode ser que a seção não suporte
            if (page > 1) {
                // Retorna lista vazia se não houver mais páginas
                return newHomePageResponse(request.name, emptyList(), false)
            } else {
                throw e
            }
        }
        
        val items = extractSectionItems(document, sectionId)
        
        // Verificar se há botões de paginação para determinar se há mais páginas
        val hasNextPage = checkForNextPage(document, page)
        
        return newHomePageResponse(request.name, items.distinctBy { it.url }, hasNextPage)
    }

    private fun checkForNextPage(document: org.jsoup.nodes.Document, currentPage: Int): Boolean {
        // Verificar botões de paginação
        val pagination = document.select(".pagination, .nav-links, .page-numbers, a[href*='page/']")
        
        // Verificar se há algum link para a próxima página
        val nextPageLinks = pagination.filter { element ->
            val href = element.attr("href")
            val text = element.text().lowercase()
            href.contains("/page/${currentPage + 1}/") || 
            text.contains("próxima") || 
            text.contains("next") ||
            element.hasClass("next") ||
            element.hasClass("next-page")
        }
        
        // Ou verificar se há número da próxima página
        val pageNumbers = document.select(".page-numbers, .page-number, [class*='page']")
            .filter { it.text().matches(Regex("\\d+")) }
            .mapNotNull { it.text().toIntOrNull() }
            .sorted()
        
        // Se houver número maior que a página atual
        if (pageNumbers.any { it > currentPage }) {
            return true
        }
        
        return nextPageLinks.isNotEmpty()
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        
        val searchUrl = mainUrl
        
        try {
            val document = app.post(
                url = searchUrl,
                data = mapOf(
                    "do" to "search",
                    "subaction" to "search",
                    "story" to query
                ),
                referer = searchUrl,
                headers = mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded",
                    "Origin" to mainUrl,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
                )
            ).document
            
            return extractSearchResults(document)
            
        } catch (e: Exception) {
            try {
                val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
                val fallbackUrl = "$mainUrl/?do=search&subaction=search&story=$encodedQuery"
                
                val document = app.get(fallbackUrl).document
                return extractSearchResults(document)
            } catch (e2: Exception) {
                return emptyList()
            }
        }
    }

    private fun extractSearchResults(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val searchItems = document.select(".film-list .content .col-6.col-sm-4.col-md-3.col-lg-2 .item-relative > a.item")
        
        return if (searchItems.isNotEmpty()) {
            searchItems.mapNotNull { it.toSearchResult() }
        } else {
            document.select(".item, .item-relative .item, .poster, .movie-item, .serie-item")
                .mapNotNull { it.toSearchResult() }
        }
    }

    private fun extractSectionItems(document: org.jsoup.nodes.Document, sectionId: String): List<SearchResponse> {
        val items = document.select(".item, .item-relative .item, .poster, .movie-item, .serie-item")
        return items.mapNotNull { it.toSearchResult() }
    }

    private fun extractScoreAdvanced(element: Element): Pair<String?, String?> {
        val selectors = listOf(
            ".item-info-ust .rating" to "Seletor rating principal",
            ".rating" to "Seletor .rating",
            ".score" to "Seletor .score",
            ".item-info + div" to "Próximo ao item-info",
            ".item-footer span" to "No rodapé do item",
            "span:contains(★)" to "Span com estrela",
            "span:contains(/10)" to "Span com /10",
            "[class*='rating']" to "Classe contém 'rating'",
            "[class*='score']" to "Classe contém 'score'",
            ".item-info-ust div" to "Div dentro de item-info-ust",
            "small" to "Tag small",
            "b" to "Tag bold",
            "i" to "Tag italic"
        )

        for ((selector, _) in selectors) {
            val found = element.selectFirst(selector)?.text()?.trim()
            if (!found.isNullOrBlank() && isScoreLike(found)) {
                return found to selector
            }
        }

        element.parent()?.let { parent ->
            for ((selector, _) in selectors) {
                val found = parent.selectFirst(selector)?.text()?.trim()
                if (!found.isNullOrBlank() && isScoreLike(found)) {
                    return found to "parent.$selector"
                }
            }
        }

        val html = element.outerHtml()
        val scoreRegexes = listOf(
            Regex("""(\d+\.\d+|\d+)\s*(?:★|/10|pontos)"""),
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
            text.contains("pontos", ignoreCase = true) -> true
            else -> false
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = this.selectFirst("a")
        val href = linkElement?.attr("href")?.takeIf { it.isNotBlank() } ?: return null
        
        val titleElement = selectFirst(".item-footer .title, .title, .poster-title, h3, h4")
        val title = titleElement?.text()?.trim() ?: return null
        
        val year = selectFirst(".info span:first-child, .year, .date")?.text()?.toIntOrNull()
            ?: Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        
        val cleanTitle = title
            .replace(Regex("\\(\\d{4}\\)"), "")
            .replace(Regex("\\d{4}$"), "")
            .trim()
        
        val imgElement = selectFirst("img.thumbnail, img.poster, img")
        val posterUrl = imgElement?.attr("src")?.let { fixUrl(it) }
        
        val qualityBadge = select(".item-info, .quality, .badge").firstOrNull()?.selectFirst("div:first-child, span")?.text()?.trim()
        
        val languageBadge = select(".item-info, .language, .badge").firstOrNull()?.selectFirst("div:nth-child(2), .lang")?.text()?.trim()
        
        val scoreResult = extractScoreAdvanced(this)
        val scoreText = scoreResult.first
        val score = when {
            scoreText == null || scoreText == "N/A" -> null
            else -> scoreText.toFloatOrNull()?.let { Score.from10(it) }
        }
        
        val lastEpisodeInfo = select(".item-info, .episode, .data").getOrNull(1)?.selectFirst("small, .last-ep")?.text()?.trim()
            ?: selectFirst(".data, .episode-info")?.text()?.trim()
        
        val isSerie = href.contains("/series-") || href.contains("/serie-") || href.contains("/tv-") || 
                      href.contains("/series-online") ||
                      lastEpisodeInfo?.contains(Regex("S\\d+.*E\\d+")) == true ||
                      title.contains(Regex("(?i)(temporada|episódio|season|episode)"))
        
        val quality = when {
            qualityBadge?.contains("HD", ignoreCase = true) == true -> SearchQuality.HD
            qualityBadge?.contains("4K", ignoreCase = true) == true -> SearchQuality.HD
            qualityBadge?.contains("FULLHD", ignoreCase = true) == true -> SearchQuality.HD
            qualityBadge?.contains("TS", ignoreCase = true) == true -> SearchQuality.Cam
            else -> null
        }
        
        return if (isSerie) {
            newTvSeriesSearchResponse(cleanTitle, fixUrl(href)) {
                this.posterUrl = posterUrl
                this.year = year
                this.score = score
                if (quality != null) {
                    this.quality = quality
                }
            }
        } else {
            newMovieSearchResponse(cleanTitle, fixUrl(href)) {
                this.posterUrl = posterUrl
                this.year = year
                this.score = score
                if (quality != null) {
                    this.quality = quality
                }
            }
        }
    }

    // =============================================
    // FUNÇÃO EXTRACT BANNER URL (DO SEU CÓDIGO ORIGINAL)
    // =============================================
    private fun extractBannerUrl(doc: org.jsoup.nodes.Document): String? {
        val bannerSelectors = listOf(
            // Primeiro tentar meta tags do Open Graph
            "meta[property='og:image']",
            "meta[name='twitter:image']",
            
            // Procurar a estrutura <picture> que você mostrou
            "picture img",
            "picture source[media='(max-width: 768px)']",
            
            // Procurar especificamente pela estrutura que você mostrou
            "picture img[alt*='assistir'][title*='Assistir']",
            "picture img[loading='lazy']",
            
            // Outros seletores comuns
            ".cover-img",
            ".banner-img",
            "img.banner",
            ".hero img",
            ".featured-image img",
            ".post-thumbnail img",
            ".single-featured-image img",
            "[class*='banner'] img",
            "[class*='cover'] img",
            ".movie-banner",
            ".series-banner",
            
            // Imagem principal do artigo/post
            ".post-content img",
            ".entry-content img",
            ".article-content img",
            
            // Imagens com alt ou title contendo o título
            "img[title*='Assistir']",
            "img[alt*='assistir']",
            "img[alt*='online']",
            "img[title*='online']"
        )
        
        println("[CineAgora] Procurando banner...")
        
        for (selector in bannerSelectors) {
            val element = doc.selectFirst(selector)
            if (element != null) {
                val url = when {
                    selector.startsWith("meta[") -> element.attr("content")
                    selector.contains("source[") -> element.attr("srcset")
                    else -> element.attr("src")
                }
                
                if (url.isNotBlank()) {
                    val fixedUrl = fixUrl(url)
                    println("[CineAgora] ✅ Banner encontrado com seletor '$selector': $fixedUrl")
                    
                    // Se for uma tag <source>, podemos precisar extrair a primeira URL do srcset
                    if (selector.contains("source[") && url.contains(",")) {
                        val firstUrl = url.substringBefore(",").trim()
                        if (firstUrl.isNotBlank()) {
                            val fixedFirstUrl = fixUrl(firstUrl)
                            println("[CineAgora] ✅ Extraindo primeira URL do srcset: $fixedFirstUrl")
                            return fixedFirstUrl
                        }
                    }
                    
                    return fixedUrl
                }
            }
        }
        
        // Fallback especial para estrutura <picture> específica
        println("[CineAgora] Fallback: procurando estrutura <picture> específica...")
        
        // Procurar por estrutura <picture> com img dentro
        val pictureElements = doc.select("picture")
        for (picture in pictureElements) {
            // Primeiro tentar a tag <img> dentro do picture
            val imgElement = picture.selectFirst("img")
            if (imgElement != null) {
                val src = imgElement.attr("src")
                if (src.isNotBlank()) {
                    val fixedUrl = fixUrl(src)
                    println("[CineAgora] ✅ Banner encontrado em <picture> <img>: $fixedUrl")
                    return fixedUrl
                }
            }
            
            // Se não encontrar img, tentar source com srcset
            val sourceElement = picture.selectFirst("source")
            if (sourceElement != null) {
                val srcset = sourceElement.attr("srcset")
                if (srcset.isNotBlank()) {
                    // Pegar a primeira URL do srcset
                    val firstUrl = srcset.split(",").firstOrNull()?.trim()?.substringBefore(" ")?.trim()
                    if (!firstUrl.isNullOrBlank()) {
                        val fixedUrl = fixUrl(firstUrl)
                        println("[CineAgora] ✅ Banner encontrado em <picture> <source>: $fixedUrl")
                        return fixedUrl
                    }
                }
            }
        }
        
        // Fallback 2: Procurar qualquer imagem grande
        println("[CineAgora] Fallback 2: procurando imagens grandes...")
        val allImages = doc.select("img[src]")
        val largeImages = allImages.filter { 
            val src = it.attr("src")
            val width = it.attr("width").toIntOrNull()
            val height = it.attr("height").toIntOrNull()
            
            src.contains("/uploads/posts/") || // Imagens específicas do site
            src.contains(".webp") || // WebP geralmente é de alta qualidade
            (width != null && height != null && width >= 600 && height >= 300) || // Imagens grandes
            src.contains("banner") || // Nome contém banner
            src.contains("cover") || // Nome contém cover
            src.contains("featured") // Nome contém featured
        }
        
        if (largeImages.isNotEmpty()) {
            // Ordenar por tamanho (priorizar imagens maiores)
            val sortedImages = largeImages.sortedByDescending { 
                val width = it.attr("width").toIntOrNull() ?: 0
                val height = it.attr("height").toIntOrNull() ?: 0
                width * height
            }

            for (img in sortedImages.take(3)) { // Verificar as 3 maiores
                val src = img.attr("src")
                if (src.isNotBlank()) {
                    val fixedUrl = fixUrl(src)
                    println("[CineAgora] ✅ Banner encontrado (fallback): $fixedUrl")
                    return fixedUrl
                }
            }
        }
        
        // Fallback 3: Primeira imagem do artigo/conteúdo principal
        println("[CineAgora] Fallback 3: primeira imagem do conteúdo...")
        val contentAreas = doc.select(".post-content, .entry-content, .article-content, .content, main")
        for (content in contentAreas) {
            val firstImg = content.selectFirst("img[src]")
            if (firstImg != null) {
                val src = firstImg.attr("src")
                if (src.isNotBlank()) {
                    val fixedUrl = fixUrl(src)
                    println("[CineAgora] ✅ Banner encontrado no conteúdo: $fixedUrl")
                    return fixedUrl
                }
            }
        }
        
        // Fallback final: Qualquer imagem que não seja ícone/logo pequeno
        println("[CineAgora] Fallback final: qualquer imagem relevante...")
        val relevantImages = allImages.filterNot { 
            val src = it.attr("src")
            src.contains("logo") || 
            src.contains("icon") || 
            src.contains("avatar") || 
            src.contains("favicon") ||
            src.contains("social") ||
            src.endsWith(".ico") ||
            src.length < 20 // URLs muito curtas provavelmente são ícones
        }
        
        if (relevantImages.isNotEmpty()) {
            val img = relevantImages.first()
            val src = img.attr("src")
            if (src.isNotBlank()) {
                val fixedUrl = fixUrl(src)
                println("[CineAgora] ⚠️ Banner encontrado (último fallback): $fixedUrl")
                return fixedUrl
            }
        }
        
        println("[CineAgora] ❌ Não encontrou banner")
        return null
    }

    private fun extractYear(doc: org.jsoup.nodes.Document): Int? {
        return doc.selectFirst(".year, .date, time")?.text()?.toIntOrNull()
            ?: Regex("(\\d{4})").find(doc.selectFirst("h1")?.text() ?: "")?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractGenres(doc: org.jsoup.nodes.Document): List<String>? {
        val genres = doc.select(".genres a, .genre a, .category a, a[href*='genero'], a[href*='categoria']")
            .mapNotNull { it.text().trim() }
            .filter { it.isNotBlank() }
        
        return if (genres.isNotEmpty()) genres else null
    }

    // =============================================
    // FUNÇÕES TMDB (DO SEU CÓDIGO ATUAL - QUE FUNCIONA)
    // =============================================
    private suspend fun searchOnTMDB(query: String, year: Int?, isTv: Boolean): TMDBInfo? {
        return try {
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val yearParam = year?.let { "&year=$it" } ?: ""

            val searchUrl = if (isTv) {
                "https://api.themoviedb.org/3/search/tv?api_key=$TMDB_API_KEY&query=$encodedQuery&language=pt-BR$yearParam"
            } else {
                "https://api.themoviedb.org/3/search/movie?api_key=$TMDB_API_KEY&query=$encodedQuery&language=pt-BR$yearParam"
            }

            val headers = mapOf(
                "Authorization" to "Bearer $TMDB_ACCESS_TOKEN",
                "accept" to "application/json"
            )

            val response = app.get(searchUrl, headers = headers, timeout = 10_000)
            if (response.code != 200) return null

            val searchResult = response.parsedSafe<TMDBSearchResponse>() ?: return null
            val result = searchResult.results.firstOrNull() ?: return null

            val details = getTMDBDetails(result.id, isTv) ?: return null

            // Extrair atores como List<Pair<Actor, String?>> para addActors
            val allActors = details.credits?.cast?.take(15)?.mapNotNull { actor ->
                if (actor.name.isNotBlank()) {
                    val actorObj = Actor(
                        name = actor.name,
                        image = actor.profile_path?.let { "$tmdbImageUrl/w185$it" }
                    )
                    
                    // Retornar Pair<Actor, String?> onde String é o papel/personagem
                    Pair(actorObj, actor.character)
                } else null
            }

            val youtubeTrailer = getHighQualityTrailer(details.videos?.results)

            val seasonsEpisodes = if (isTv) {
                getTMDBAllSeasons(result.id)
            } else {
                emptyMap()
            }

            TMDBInfo(
                id = result.id,
                title = if (isTv) result.name else result.title,
                year = if (isTv) {
                    result.first_air_date?.substring(0, 4)?.toIntOrNull()
                } else {
                    result.release_date?.substring(0, 4)?.toIntOrNull()
                },
                posterUrl = result.poster_path?.let { "$tmdbImageUrl/w500$it" },
                backdropUrl = details.backdrop_path?.let { "$tmdbImageUrl/original$it" },
                overview = details.overview,
                genres = details.genres?.map { it.name },
                actors = allActors,
                youtubeTrailer = youtubeTrailer,
                duration = if (!isTv) details.runtime else null,
                seasonsEpisodes = seasonsEpisodes,
                rating = details.vote_average?.takeIf { it > 0 }
            )
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun getTMDBAllSeasons(seriesId: Int): Map<Int, List<TMDBEpisode>> {
        return try {
            val headers = mapOf(
                "Authorization" to "Bearer $TMDB_ACCESS_TOKEN",
                "accept" to "application/json"
            )

            val seriesDetailsUrl = "https://api.themoviedb.org/3/tv/$seriesId?api_key=$TMDB_API_KEY&language=pt-BR"
            val seriesResponse = app.get(seriesDetailsUrl, headers = headers, timeout = 10_000)

            if (seriesResponse.code != 200) {
                return emptyMap()
            }

            val seriesDetails = seriesResponse.parsedSafe<TMDBTVDetailsResponse>() ?: return emptyMap()

            val seasonsEpisodes = mutableMapOf<Int, List<TMDBEpisode>>()

            for (season in seriesDetails.seasons) {
                if (season.season_number > 0) {
                    val seasonNumber = season.season_number

                    val seasonUrl = "https://api.themoviedb.org/3/tv/$seriesId/season/$seasonNumber?api_key=$TMDB_API_KEY&language=pt-BR"
                    val seasonResponse = app.get(seasonUrl, headers = headers, timeout = 10_000)

                    if (seasonResponse.code == 200) {
                        val seasonData = seasonResponse.parsedSafe<TMDBSeasonResponse>()
                        seasonData?.episodes?.let { episodes ->
                            seasonsEpisodes[seasonNumber] = episodes
                        }
                    }
                }
            }

            seasonsEpisodes
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private suspend fun getTMDBDetails(id: Int, isTv: Boolean): TMDBDetailsResponse? {
        return try {
            val headers = mapOf(
                "Authorization" to "Bearer $TMDB_ACCESS_TOKEN",
                "accept" to "application/json"
            )

            val url = if (isTv) {
                "https://api.themoviedb.org/3/tv/$id?api_key=$TMDB_API_KEY&language=pt-BR&append_to_response=credits,videos"
            } else {
                "https://api.themoviedb.org/3/movie/$id?api_key=$TMDB_API_KEY&language=pt-BR&append_to_response=credits,videos"
            }

            val response = app.get(url, headers = headers, timeout = 10_000)

            if (response.code != 200) return null
            response.parsedSafe<TMDBDetailsResponse>()
        } catch (e: Exception) {
            null
        }
    }

    private fun getHighQualityTrailer(videos: List<TMDBVideo>?): String? {
        if (videos.isNullOrEmpty()) return null

        return videos.mapNotNull { video ->
            when {
                video.site == "YouTube" && video.type == "Trailer" && video.official == true ->
                    Triple(video.key, 10, "YouTube Trailer Oficial")
                video.site == "YouTube" && video.type == "Trailer" ->
                    Triple(video.key, 9, "YouTube Trailer")
                video.site == "YouTube" && video.type == "Teaser" && video.official == true ->
                    Triple(video.key, 8, "YouTube Teaser Oficial")
                video.site == "YouTube" && video.type == "Teaser" ->
                    Triple(video.key, 7, "YouTube Teaser")
                else -> null
            }
        }
        ?.sortedByDescending { it.second }
        ?.firstOrNull()
        ?.let { (key, _, _) -> "https://www.youtube.com/watch?v=$key" }
    }

    // =============================================
    // FUNÇÕES DE EXTRAÇÃO DE EPISÓDIOS (DO SEU CÓDIGO ORIGINAL)
    // =============================================

    // FUNÇÃO PARA EXTRAIR SERIES SLUG DA PÁGINA (DO SEU CÓDIGO ORIGINAL)
    private suspend fun extractSeriesSlugFromPage(doc: org.jsoup.nodes.Document, baseUrl: String): String? {
        println("[CineAgora] Procurando seriesSlug na página: $baseUrl")
        
        // ESTRATÉGIA 1: Buscar URL do player/tv em iframes (PRINCIPAL)
        // Primeiro, procurar por iframes que tenham src contendo /tv/
        
        // Procurar iframes com src contendo /tv/
        val iframes = doc.select("iframe[src*='/tv/']")
        println("[CineAgora] Encontrados ${iframes.size} iframes com /tv/")
        
        for ((index, iframe) in iframes.withIndex()) {
            val src = iframe.attr("src")
            println("[CineAgora] Iframe $index src: $src")
            
            // Padrão: https://watch.brplayer.cc/tv/the-day-of-the-jackal
            val tvPattern = Regex("""/tv/([^/?]+)""")
            val tvMatch = tvPattern.find(src)
            if (tvMatch != null) {
                val slug = tvMatch.groupValues[1]
                println("[CineAgora] ✓ Slug encontrado no iframe (/tv/): $slug")
                return slug
            }
        }
        
        // ESTRATÉGIA 2: Procurar qualquer iframe com watch.brplayer.cc
        val allIframes = doc.select("iframe[src*='watch.brplayer.cc']")
        println("[CineAgora] Encontrados ${allIframes.size} iframes com watch.brplayer.cc")
        
        for ((index, iframe) in allIframes.withIndex()) {
            val src = iframe.attr("src")
            println("[CineAgora] Iframe $index completo: $src")
            
            // Tentar extrair slug do padrão /tv/{slug}
            val tvPattern = Regex("""/tv/([^/?]+)""")
            val tvMatch = tvPattern.find(src)
            if (tvMatch != null) {
                val slug = tvMatch.groupValues[1]
                println("[CineAgora] ✓ Slug encontrado em iframe genérico (/tv/): $slug")
                return slug
            }
            
            // Tentar extrair do padrão /watch/{videoId} e converter via API
            val watchPattern = Regex("""/watch/([^/?]+)""")
            val watchMatch = watchPattern.find(src)
            if (watchMatch != null) {
                val videoSlug = watchMatch.groupValues[1]
                println("[CineAgora] Video slug encontrado: $videoSlug")
                
                // Converter videoSlug para seriesSlug via API
                val seriesSlug = getSeriesFromVideoSlug(videoSlug)
                if (seriesSlug.isNotBlank()) {
                    println("[CineAgora] ✓ Series slug obtido da API: $seriesSlug")
                    return seriesSlug
                }
            }
        }
        
        // ESTRATÉGIA 3: Procurar em outros elementos com src
        val elementsWithSrc = doc.select("[src*='watch.brplayer.cc/tv/']")
        println("[CineAgora] Encontrados ${elementsWithSrc.size} elementos com src contendo /tv/")
        
        for (element in elementsWithSrc) {
            val src = element.attr("src")
            println("[CineAgora] Elemento src: $src")
            
            val slug = src.substringAfterLast("/tv/").substringBefore("?").substringBefore("#")
            if (slug.isNotBlank()) {
                println("[CineAgora] ✓ Slug encontrado em elemento: $slug")
                return slug
            }
        }
        
        // ESTRATÉGIA 4: Procurar em scripts por URL /tv/
        val scripts = doc.select("script")
        println("[CineAgora] Analisando ${scripts.size} scripts")
        
        for ((index, script) in scripts.withIndex()) {
            val scriptText = script.html()
            
            // Procurar padrão /tv/{slug} em URLs
            val tvPattern = Regex("""["'](https?://watch\.brplayer\.cc/tv/[^"']+)["']""")
            val matches = tvPattern.findAll(scriptText)
            
            for (match in matches) {
                val url = match.groupValues[1]
                println("[CineAgora] URL /tv/ encontrada em script $index: $url")
                
                val slug = url.substringAfterLast("/tv/").substringBefore("?").substringBefore("#")
                if (slug.isNotBlank()) {
                    println("[CineAgora] ✓ Slug extraído de script: $slug")
                    return slug
                }
            }
            
            // Procurar padrão watch.brplayer.cc/tv/ diretamente
            val directPattern = Regex("""watch\.brplayer\.cc/tv/([^/"']+)""")
            val directMatch = directPattern.find(scriptText)
            if (directMatch != null) {
                val slug = directMatch.groupValues[1]
                println("[CineAgora] ✓ Slug encontrado diretamente em script: $slug")
                return slug
            }
            
            // Procurar por atributo data-src (como no seu exemplo)
            if (scriptText.contains("data-src")) {
                val dataSrcPattern = Regex("""data-src=["']([^"']+)["']""")
                val dataSrcMatches = dataSrcPattern.findAll(scriptText)
                
                for (match in dataSrcMatches) {
                    val dataSrc = match.groupValues[1]
                    if (dataSrc.contains("/tv/")) {
                        println("[CineAgora] data-src encontrado: $dataSrc")
                        val slug = dataSrc.substringAfterLast("/tv/").substringBefore("?").substringBefore("#")
                        if (slug.isNotBlank()) {
                            println("[CineAgora] ✓ Slug extraído de data-src: $slug")
                            return slug
                        }
                    }
                }
            }
        }
        
        // ESTRATÉGIA 5: Procurar no HTML inteiro por padrão /tv/
        val html = doc.html()
        val htmlPattern = Regex("""/tv/([^/"'?&#]+)""")
        val htmlMatches = htmlPattern.findAll(html)
        
        for (match in htmlMatches) {
            val slug = match.groupValues[1]
            if (slug.length > 3 && !slug.contains(".") && !slug.contains(" ")) {
                println("[CineAgora] ✓ Slug encontrado no HTML bruto: $slug")
                return slug
            }
        }
        
        // ESTRATÉGIA 6: Tentar extrair do próprio URL da página do CineAgora
        try {
            val urlSlug = baseUrl
                .substringAfterLast("/")
                .substringAfter("-")
                .substringBefore(".html")
                .trim()
            
            if (urlSlug.isNotBlank() && urlSlug.length > 3) {
                println("[CineAgora] Slug extraído da URL da página: $urlSlug")
                return urlSlug
            }
        } catch (e: Exception) {
            println("[CineAgora] Erro ao extrair slug da URL: ${e.message}")
        }
        
        println("[CineAgora] ❌ Não foi possível encontrar o seriesSlug")
        return null
    }

    private suspend fun getSeriesFromVideoSlug(videoSlug: String): String {
        try {
            val apiUrl = "https://watch.brplayer.cc/get_series_from_video.php?videoSlug=$videoSlug"
            println("[CineAgora] Chamando API para converter videoSlug: $apiUrl")
            
            val response = app.get(apiUrl, timeout = 10)
            if (response.isSuccessful) {
                val seriesSlug = response.text.trim()
                if (seriesSlug.isNotBlank() && seriesSlug != "null") {
                    return seriesSlug
                }
            }
        } catch (e: Exception) {
            println("[CineAgora] Erro ao obter series slug: ${e.message}")
        }
        
        return ""
    }

    // FUNÇÃO PARA BUSCAR EPISÓDIOS DA API (DO SEU CÓDIGO ORIGINAL)
    private suspend fun fetchEpisodesFromApi(seriesSlug: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        val apiUrl = "https://watch.brplayer.cc/fetch_series_data.php?seriesSlug=$seriesSlug"
        println("[CineAgora] Chamando API: $apiUrl")
        
        try {
            // Headers importantes para a API funcionar
            val headers = mapOf(
                "accept" to "application/json, text/javascript, */*; q=0.01",
                "accept-language" to "pt-BR",
                "referer" to "https://watch.brplayer.cc/tv/$seriesSlug",
                "sec-fetch-dest" to "empty",
                "sec-fetch-mode" to "cors",
                "sec-fetch-site" to "same-origin",
                "user-agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
                "x-requested-with" to "XMLHttpRequest"
            )
            
            val response = app.get(apiUrl, headers = headers, timeout = 30)
            
            if (!response.isSuccessful) {
                println("[CineAgora] API retornou erro: ${response.code}")
                return episodes
            }
            
            val jsonText = response.text
            println("[CineAgora] Resposta da API recebida (${jsonText.length} chars)")
            
            if (jsonText.isEmpty() || jsonText == "null") {
                println("[CineAgora] API retornou resposta vazia")
                return episodes
            }
            
            val responseMap: Map<String, Any>? = AppUtils.parseJson(jsonText)
            
            if (responseMap == null) {
                println("[CineAgora] Erro ao parsear JSON da API")
                println("[CineAgora] JSON raw (primeiros 500 chars): ${jsonText.take(500)}")
                return episodes
            }
            
            val seasonsMap = responseMap["seasons"] as? Map<String, List<Map<String, Any>>>
            
            if (seasonsMap == null) {
                println("[CineAgora] Não encontrou 'seasons' no JSON")
                // Tentar estrutura alternativa
                val allEpisodes = responseMap["episodes"] as? List<Map<String, Any>>
                if (allEpisodes != null) {
                    println("[CineAgora] Encontrou estrutura alternativa 'episodes' com ${allEpisodes.size} itens")
                    allEpisodes.forEachIndexed { index, epMap ->
                        try {
                            extractEpisodeFromMap(epMap, 1, index + 1, episodes)
                        } catch (e: Exception) {
                            println("[CineAgora] Erro ao processar episódio alternativo: ${e.message}")
                        }
                    }
                }
                return episodes
            }
            
            println("[CineAgora] API carregada com sucesso. ${seasonsMap.keys.size} temporada(s) encontrada(s)")
            
            seasonsMap.forEach { (seasonStr, episodeList) ->
                val seasonNum = seasonStr.toIntOrNull() ?: 1
                println("[CineAgora] Processando temporada $seasonNum com ${episodeList.size} episódios")
                
                episodeList.forEachIndexed { index, epMap ->
                    try {
                        extractEpisodeFromMap(epMap, seasonNum, index + 1, episodes)
                    } catch (e: Exception) {
                        println("[CineAgora] Erro ao processar episódio ${index + 1}: ${e.message}")
                    }
                }
            }
            
            println("[CineAgora] Total de ${episodes.size} episódios criados a partir da API!")
            
        } catch (e: Exception) {
            println("[CineAgora] Erro na chamada à API: ${e.message}")
            println("[CineAgora] Stack trace: ${e.stackTraceToString()}")
        }
        
        return episodes
    }
    
    private fun extractEpisodeFromMap(epMap: Map<String, Any>, seasonNum: Int, defaultEpisodeNum: Int, episodes: MutableList<Episode>) {
        val videoSlug = epMap["video_slug"] as? String ?: return
        val epNumberStr = epMap["episode_number"] as? String
        val epTitleRaw = epMap["episode_title"] as? String
        
        // Determinar número do episódio
        val epNumber = epNumberStr?.toIntOrNull() ?: defaultEpisodeNum
        
        // Limpar título do episódio
        val episodeTitle = cleanEpisodeTitle(epTitleRaw, seasonNum, epNumber)
        
        // URL final do episódio
        val episodeUrl = "https://watch.brplayer.cc/watch/$videoSlug"
        
        episodes.add(
            newEpisode(episodeUrl) {
                name = episodeTitle
                season = seasonNum
                episode = epNumber
                description = "Temporada $seasonNum • Episódio $epNumber"
            }
        )
        
        println("[CineAgora] Adicionado: Temporada $seasonNum, Episódio $epNumber - $episodeTitle")
    }
    
    // FUNÇÃO PARA LIMPAR TÍTULO DO EPISÓDIO (DO SEU CÓDIGO ORIGINAL)
    private fun cleanEpisodeTitle(rawTitle: String?, seasonNum: Int, episodeNum: Int): String {
        // Sempre retornar "Episódio X" ignorando completamente o título original
        return "Episódio $episodeNum"
    }

    // FUNÇÃO PRINCIPAL PARA EXTRAIR EPISÓDIOS (DO SEU CÓDIGO ORIGINAL)
    private suspend fun extractEpisodes(doc: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        println("[CineAgora] Extraindo episódios para: $baseUrl")
        
        // PRIMEIRO: Tentar extrair o seriesSlug da página
        val seriesSlug = extractSeriesSlugFromPage(doc, baseUrl)
        
        if (seriesSlug != null) {
            println("[CineAgora] Series Slug encontrado: $seriesSlug")
            
            // Buscar episódios da API
            val apiEpisodes = fetchEpisodesFromApi(seriesSlug)
            
            if (apiEpisodes.isNotEmpty()) {
                println("[CineAgora] ${apiEpisodes.size} episódios obtidos da API")
                return apiEpisodes.sortedWith(compareBy({ it.season }, { it.episode }))
            }
            
            println("[CineAgora] API não retornou episódios, tentando fallback HTML")
        } else {
            println("[CineAgora] Não encontrou seriesSlug, tentando fallback HTML")
        }
        
        // SEGUNDO: Fallback para extração direta do HTML
        return extractEpisodesFromHtmlFallback(doc, baseUrl)
    }
    
    private fun extractEpisodesFromHtmlFallback(doc: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        // Tentar extrair do dropdown de episódios
        val episodeDropdown = doc.selectFirst("#episodeDropdown")
        if (episodeDropdown != null) {
            val episodeButtons = episodeDropdown.select("button[data-id]")
            println("[CineAgora] Encontrados ${episodeButtons.size} episódios no dropdown HTML")
            
            episodeButtons.forEachIndexed { index, button ->
                try {
                    val episodeNum = index + 1
                    val videoSlug = button.attr("data-id")
                    val buttonText = button.text().trim()
                    
                    // Extrair número do episódio do texto
                    val epNumberFromText = extractEpisodeNumberFromText(buttonText)
                    val finalEpisodeNum = epNumberFromText ?: episodeNum
                    
                    val episodeTitle = if (buttonText.isNotBlank() && buttonText != "Episódio $finalEpisodeNum") {
                        buttonText
                    } else {
                        "Episódio $finalEpisodeNum"
                    }
                    
                    val episodeUrl = "https://watch.brplayer.cc/watch/$videoSlug"
                    
                    episodes.add(
                        newEpisode(episodeUrl) {
                            name = episodeTitle
                            season = 1
                            episode = finalEpisodeNum
                            description = "Episódio $finalEpisodeNum"
                        }
                    )
                    
                    println("[CineAgora] Adicionado do HTML: $episodeTitle")
                    
                } catch (e: Exception) {
                    println("[CineAgora] Erro ao extrair episódio do HTML: ${e.message}")
                }
            }
        }
        
        // Se não encontrou nada, criar pelo menos um episódio
        if (episodes.isEmpty()) {
            println("[CineAgora] Fallback final: criando episódio básico")
            episodes.add(
                newEpisode(baseUrl) {
                    name = "Episódio 1"
                    season = 1
                    episode = 1
                }
            )
        }
        
        return episodes
    }
    
    private fun extractEpisodeNumberFromText(text: String): Int? {
        try {
            // Padrões comuns: "Episódio 1", "Episódio 01", "E01", "EP 1"
            val patterns = listOf(
                Regex("""Episódio\s+(\d+)""", RegexOption.IGNORE_CASE),
                Regex("""Episode\s+(\d+)""", RegexOption.IGNORE_CASE),
                Regex("""E(\d+)""", RegexOption.IGNORE_CASE),
                Regex("""EP\s*(\d+)""", RegexOption.IGNORE_CASE),
                Regex("""\b(\d{1,3})\b""") // Qualquer número de 1-3 dígitos
            )
            
            for (pattern in patterns) {
                val match = pattern.find(text)
                if (match != null) {
                    return match.groupValues[1].toIntOrNull()
                }
            }
        } catch (e: Exception) {
            // Ignorar erro
        }
        
        return null
    }

    // =============================================
    // FUNÇÃO LOAD PRINCIPAL (MANTENDO SUA LÓGICA ORIGINAL MAS COM TMDB)
    // =============================================
    override suspend fun load(url: String): LoadResponse? {
        println("[CineAgora] Carregando URL: $url")
        
        val doc = app.get(url).document
        
        // 1. Extrair informações básicas
        val bannerUrl = extractBannerUrl(doc)
        val posterUrl = doc.selectFirst("meta[property='og:image']")?.attr("content")?.let { fixUrl(it) }
            ?: doc.selectFirst("#info--box .cover-img")?.attr("src")?.let { fixUrl(it) }
            ?: bannerUrl
        
        println("[CineAgora] Banner URL: $bannerUrl")
        println("[CineAgora] Poster URL: $posterUrl")
        
        val title = doc.selectFirst("h1.title, h1, .title, h2")?.text()?.trim() ?: "Título não encontrado"
        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
        
        // 2. Extrair episódios (DO SEU CÓDIGO ORIGINAL)
        val episodes = extractEpisodes(doc, url)
        
        println("[CineAgora] Total de episódios extraídos: ${episodes.size}")
        
        if (episodes.isEmpty()) {
            println("[CineAgora] Nenhum episódio encontrado")
            return null
        }
        
        // 3. DETERMINAR SE É SÉRIE OU FILME (DO SEU CÓDIGO ORIGINAL)
        val isSerie = url.contains("/series-") || url.contains("/serie-") || url.contains("/tv-") || 
                     url.contains("/series-online") ||
                     doc.select(".player-controls, #episodeDropdown, .seasons").isNotEmpty() ||
                     episodes.size > 1
        
        println("[CineAgora] É série? $isSerie (${episodes.size} episódios)")
        
        // 4. INFORMAÇÕES ADICIONAIS DO SITE
        val yearFromSite = extractYear(doc)
        val plotFromSite = doc.selectFirst(".info-description, .description, .sinopse, .plot")?.text()?.trim()
        val genresFromSite = extractGenres(doc)
        
        // 5. Buscar informações do TMDB
        val tmdbInfo = searchOnTMDB(cleanTitle, yearFromSite, isSerie)
        
        // 6. Recomendações do site
        val recommendations = extractRecommendationsFromSite(doc)
        
        // 7. Encontrar URL do player para filmes
        val playerUrl = if (!isSerie) {
            findPlayerUrl(doc) ?: url
        } else {
            url
        }
        
        // 8. Criar resposta com base nas informações
        return if (isSerie) {
            createSeriesLoadResponse(tmdbInfo, url, doc, episodes, recommendations, plotFromSite, genresFromSite, bannerUrl, posterUrl, yearFromSite)
        } else {
            createMovieLoadResponse(tmdbInfo, playerUrl, doc, recommendations, plotFromSite, genresFromSite, bannerUrl, posterUrl, yearFromSite)
        }
    }

    private fun findPlayerUrl(document: org.jsoup.nodes.Document): String? {
        // Procura por iframes do brplayer
        val iframe = document.selectFirst("iframe[src*='watch.brplayer.cc']")
        if (iframe != null) {
            val src = iframe.attr("src")
            
            // Extrair videoSlug do iframe
            val watchPattern = Regex("""/watch/([^/?]+)""")
            val watchMatch = watchPattern.find(src)
            if (watchMatch != null) {
                val videoSlug = watchMatch.groupValues[1]
                return "https://watch.brplayer.cc/watch/$videoSlug"
            }
        }
        
        return null
    }

    private fun extractRecommendationsFromSite(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val recommendations = document.select(".item, .item-relative .item, .poster, .movie-item, .serie-item")
            .mapNotNull { it.toSearchResult() }
            .take(10)
        
        return recommendations
    }

    // =============================================
    // FUNÇÕES PARA CRIAR LOAD RESPONSE COM TMDB
    // =============================================
    private suspend fun createSeriesLoadResponse(
        tmdbInfo: TMDBInfo?,
        url: String,
        doc: org.jsoup.nodes.Document,
        episodes: List<Episode>,
        siteRecommendations: List<SearchResponse>,
        plotFromSite: String?,
        genresFromSite: List<String>?,
        bannerUrlFromSite: String?,
        posterUrlFromSite: String?,
        yearFromSite: Int?
    ): LoadResponse {
        // Informações do TMDB ou do site
        val title = tmdbInfo?.title ?: doc.selectFirst("h1.title, h1, .title, h2")?.text()?.trim() ?: "Título não encontrado"
        val year = tmdbInfo?.year ?: yearFromSite
        val plot = tmdbInfo?.overview ?: plotFromSite
        val posterUrl = tmdbInfo?.posterUrl ?: posterUrlFromSite
        val backdropUrl = tmdbInfo?.backdropUrl ?: bannerUrlFromSite
        val genres = tmdbInfo?.genres ?: genresFromSite
        val rating = tmdbInfo?.rating?.let { Score.from10(it) }
        
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = posterUrl
            this.backgroundPosterUrl = backdropUrl
            this.year = year
            this.plot = plot
            this.tags = genres
            this.score = rating
            this.recommendations = siteRecommendations.takeIf { it.isNotEmpty() }
            
            // Adicionar atores do TMDB com suas vozes
            tmdbInfo?.actors?.let { actors ->
                addActors(actors)
            }
            
            // Adicionar trailer do TMDB se disponível
            tmdbInfo?.youtubeTrailer?.let { trailerUrl ->
                addTrailer(trailerUrl)
            }
        }
    }

    private suspend fun createMovieLoadResponse(
        tmdbInfo: TMDBInfo?,
        playerUrl: String,
        doc: org.jsoup.nodes.Document,
        siteRecommendations: List<SearchResponse>,
        plotFromSite: String?,
        genresFromSite: List<String>?,
        bannerUrlFromSite: String?,
        posterUrlFromSite: String?,
        yearFromSite: Int?
    ): LoadResponse {
        // Informações do TMDB ou do site
        val title = tmdbInfo?.title ?: doc.selectFirst("h1.title, h1, .title, h2")?.text()?.trim() ?: "Título não encontrado"
        val year = tmdbInfo?.year ?: yearFromSite
        val plot = tmdbInfo?.overview ?: plotFromSite
        val posterUrl = tmdbInfo?.posterUrl ?: posterUrlFromSite
        val backdropUrl = tmdbInfo?.backdropUrl ?: bannerUrlFromSite
        val genres = tmdbInfo?.genres ?: genresFromSite
        val duration = tmdbInfo?.duration
        val rating = tmdbInfo?.rating?.let { Score.from10(it) }
        
        return newMovieLoadResponse(title, playerUrl, TvType.Movie, playerUrl) {
            this.posterUrl = posterUrl
            this.backgroundPosterUrl = backdropUrl
            this.year = year
            this.plot = plot
            this.tags = genres
            this.duration = duration
            this.score = rating
            this.recommendations = siteRecommendations.takeIf { it.isNotEmpty() }
            
            // Adicionar atores do TMDB com suas vozes
            tmdbInfo?.actors?.let { actors ->
                addActors(actors)
            }
            
            // Adicionar trailer do TMDB se disponível
            tmdbInfo?.youtubeTrailer?.let { trailerUrl ->
                addTrailer(trailerUrl)
            }
        }
    }

    // =============================================
    // FUNÇÃO LOADLINKS COM EXTRACTOR
    // =============================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[CineAgora] loadLinks chamado com data: $data")
        println("[CineAgora] isCasting: $isCasting")
        
        if (data.contains("youtube.com") || data.contains("youtu.be")) {
            println("[CineAgora] URL do YouTube ignorada")
            return false
        }
        
        // Usar o extractor CineAgoraExtractor
        return CineAgoraExtractor.extractVideoLinks(data, name, callback)
    }

    // =============================================
    // CLASSES PARA TMDB
    // =============================================
    private data class TMDBInfo(
        val id: Int,
        val title: String?,
        val year: Int?,
        val posterUrl: String?,
        val backdropUrl: String?,
        val overview: String?,
        val genres: List<String>?,
        val actors: List<Pair<Actor, String?>>?,
        val youtubeTrailer: String?,
        val duration: Int?,
        val seasonsEpisodes: Map<Int, List<TMDBEpisode>> = emptyMap(),
        val rating: Double? = null
    )

    private data class TMDBSearchResponse(
        @JsonProperty("results") val results: List<TMDBResult>
    )

    private data class TMDBResult(
        @JsonProperty("id") val id: Int,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("release_date") val release_date: String? = null,
        @JsonProperty("first_air_date") val first_air_date: String? = null,
        @JsonProperty("poster_path") val poster_path: String?
    )

    private data class TMDBTVDetailsResponse(
        @JsonProperty("seasons") val seasons: List<TMDBSeasonInfo>
    )

    private data class TMDBSeasonInfo(
        @JsonProperty("season_number") val season_number: Int,
        @JsonProperty("episode_count") val episode_count: Int
    )

    private data class TMDBSeasonResponse(
        @JsonProperty("episodes") val episodes: List<TMDBEpisode>,
        @JsonProperty("air_date") val air_date: String?
    )

    private data class TMDBEpisode(
        @JsonProperty("episode_number") val episode_number: Int,
        @JsonProperty("name") val name: String,
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("still_path") val still_path: String?,
        @JsonProperty("runtime") val runtime: Int?,
        @JsonProperty("air_date") val air_date: String?
    )

    private data class TMDBDetailsResponse(
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("backdrop_path") val backdrop_path: String?,
        @JsonProperty("runtime") val runtime: Int?,
        @JsonProperty("genres") val genres: List<TMDBGenre>?,
        @JsonProperty("credits") val credits: TMDBCredits?,
        @JsonProperty("videos") val videos: TMDBVideos?,
        @JsonProperty("vote_average") val vote_average: Double?
    )

    private data class TMDBGenre(
        @JsonProperty("name") val name: String
    )

    private data class TMDBCredits(
        @JsonProperty("cast") val cast: List<TMDBCast>
    )

    private data class TMDBCast(
        @JsonProperty("name") val name: String,
        @JsonProperty("character") val character: String?,
        @JsonProperty("profile_path") val profile_path: String?
    )

    private data class TMDBVideos(
        @JsonProperty("results") val results: List<TMDBVideo>
    )

    private data class TMDBVideo(
        @JsonProperty("key") val key: String,
        @JsonProperty("site") val site: String,
        @JsonProperty("type") val type: String,
        @JsonProperty("official") val official: Boolean? = false
    )
}
