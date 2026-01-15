package com.CineAgora

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Element
import com.fasterxml.jackson.annotation.JsonProperty
import java.text.SimpleDateFormat

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

    private fun extractBannerUrl(doc: org.jsoup.nodes.Document): String? {
        val bannerSelectors = listOf(
            "meta[property='og:image']",
            "meta[name='twitter:image']",
            "picture img",
            "picture source[media='(max-width: 768px)']",
            "picture img[alt*='assistir'][title*='Assistir']",
            "picture img[loading='lazy']",
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
            ".post-content img",
            ".entry-content img",
            ".article-content img",
            "img[title*='Assistir']",
            "img[alt*='assistir']",
            "img[alt*='online']",
            "img[title*='online']"
        )
        
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
                    
                    if (selector.contains("source[") && url.contains(",")) {
                        val firstUrl = url.substringBefore(",").trim()
                        if (firstUrl.isNotBlank()) {
                            return fixUrl(firstUrl)
                        }
                    }
                    
                    return fixedUrl
                }
            }
        }
        
        val pictureElements = doc.select("picture")
        for (picture in pictureElements) {
            val imgElement = picture.selectFirst("img")
            if (imgElement != null) {
                val src = imgElement.attr("src")
                if (src.isNotBlank()) {
                    return fixUrl(src)
                }
            }
            
            val sourceElement = picture.selectFirst("source")
            if (sourceElement != null) {
                val srcset = sourceElement.attr("srcset")
                if (srcset.isNotBlank()) {
                    val firstUrl = srcset.split(",").firstOrNull()?.trim()?.substringBefore(" ")?.trim()
                    if (!firstUrl.isNullOrBlank()) {
                        return fixUrl(firstUrl)
                    }
                }
            }
        }
        
        val allImages = doc.select("img[src]")
        val largeImages = allImages.filter { 
            val src = it.attr("src")
            val width = it.attr("width").toIntOrNull()
            val height = it.attr("height").toIntOrNull()
            
            src.contains("/uploads/posts/") ||
            src.contains(".webp") ||
            (width != null && height != null && width >= 600 && height >= 300) ||
            src.contains("banner") ||
            src.contains("cover") ||
            src.contains("featured")
        }
        
        if (largeImages.isNotEmpty()) {
            val sortedImages = largeImages.sortedByDescending { 
                val width = it.attr("width").toIntOrNull() ?: 0
                val height = it.attr("height").toIntOrNull() ?: 0
                width * height
            }

            for (img in sortedImages.take(3)) {
                val src = img.attr("src")
                if (src.isNotBlank()) {
                    return fixUrl(src)
                }
            }
        }
        
        val contentAreas = doc.select(".post-content, .entry-content, .article-content, .content, main")
        for (content in contentAreas) {
            val firstImg = content.selectFirst("img[src]")
            if (firstImg != null) {
                val src = firstImg.attr("src")
                if (src.isNotBlank()) {
                    return fixUrl(src)
                }
            }
        }
        
        val relevantImages = allImages.filterNot { 
            val src = it.attr("src")
            src.contains("logo") || 
            src.contains("icon") || 
            src.contains("avatar") || 
            src.contains("favicon") ||
            src.contains("social") ||
            src.endsWith(".ico") ||
            src.length < 20
        }
        
        if (relevantImages.isNotEmpty()) {
            val img = relevantImages.first()
            val src = img.attr("src")
            if (src.isNotBlank()) {
                return fixUrl(src)
            }
        }
        
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
    // FUNÇÃO DE EXTRAÇÃO DE EPISÓDIOS (ESTRATÉGIA DO SUPERFLIX)
    // =============================================
    
    private suspend fun extractEpisodesWithTMDBInfo(
        document: org.jsoup.nodes.Document,
        url: String,
        tmdbInfo: TMDBInfo?,
        isSerie: Boolean
    ): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        println("[CineAgora] Extraindo episódios usando estratégia do SuperFlix")
        
        // Primeiro: buscar episódios da API original do CineAgora (se existir)
        val apiEpisodes = fetchEpisodesFromApi(document, url)
        
        if (apiEpisodes.isNotEmpty()) {
            println("[CineAgora] Encontrados ${apiEpisodes.size} episódios da API")
            
            // Combinar com metadados do TMDB se disponível
            apiEpisodes.forEach { episode ->
                val tmdbEpisode = findTMDBEpisode(tmdbInfo, episode.season ?: 1, episode.episode ?: 1)
                
                val enhancedEpisode = if (tmdbEpisode != null) {
                    // Usar metadados do TMDB
                    val descriptionWithDuration = buildDescriptionWithDuration(
                        tmdbEpisode.overview,
                        tmdbEpisode.runtime
                    )
                    
                    newEpisode(episode.data) {
                        this.name = tmdbEpisode.name
                        this.season = episode.season ?: 1
                        this.episode = episode.episode ?: 1
                        this.posterUrl = tmdbEpisode.still_path?.let { "$tmdbImageUrl/w300$it" }
                        this.description = descriptionWithDuration

                        tmdbEpisode.air_date?.let { airDate ->
                            try {
                                val dateFormatter = SimpleDateFormat("yyyy-MM-dd")
                                val date = dateFormatter.parse(airDate)
                                this.date = date.time
                            } catch (e: Exception) {}
                        }
                    }
                } else {
                    // Usar informações da API
                    episode
                }
                
                episodes.add(enhancedEpisode)
            }
            
            return episodes
        }
        
        // Fallback: extrair episódios diretamente do HTML
        println("[CineAgora] Nenhum episódio da API, tentando HTML...")
        return extractEpisodesFromHtml(document, tmdbInfo)
    }

    private suspend fun fetchEpisodesFromApi(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        try {
            // Extrair seriesSlug da página
            val seriesSlug = extractSeriesSlugFromPage(document, baseUrl)
            
            if (seriesSlug != null) {
                val apiUrl = "https://watch.brplayer.cc/fetch_series_data.php?seriesSlug=$seriesSlug"
                println("[CineAgora] Chamando API: $apiUrl")
                
                val headers = mapOf(
                    "accept" to "application/json, text/javascript, */*; q=0.01",
                    "accept-language" to "pt-BR",
                    "referer" to "https://watch.brplayer.cc/tv/$seriesSlug",
                    "user-agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36",
                    "x-requested-with" to "XMLHttpRequest"
                )
                
                val response = app.get(apiUrl, headers = headers, timeout = 30)
                
                if (response.isSuccessful) {
                    val jsonText = response.text
                    
                    if (jsonText.isNotEmpty() && jsonText != "null") {
                        val responseMap: Map<String, Any>? = AppUtils.parseJson(jsonText)
                        
                        if (responseMap != null) {
                            val seasonsMap = responseMap["seasons"] as? Map<String, List<Map<String, Any>>>
                            
                            if (seasonsMap != null) {
                                seasonsMap.forEach { (seasonStr, episodeList) ->
                                    val seasonNum = seasonStr.toIntOrNull() ?: 1
                                    
                                    episodeList.forEachIndexed { index, epMap ->
                                        try {
                                            val videoSlug = epMap["video_slug"] as? String ?: return@forEachIndexed
                                            val epNumberStr = epMap["episode_number"] as? String
                                            val epNumber = epNumberStr?.toIntOrNull() ?: (index + 1)
                                            
                                            val episodeUrl = "https://watch.brplayer.cc/watch/$videoSlug"
                                            
                                            episodes.add(
                                                newEpisode(episodeUrl) {
                                                    name = "Episódio $epNumber"
                                                    season = seasonNum
                                                    episode = epNumber
                                                }
                                            )
                                        } catch (e: Exception) {
                                            // Ignorar erro
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("[CineAgora] Erro ao buscar episódios da API: ${e.message}")
        }
        
        return episodes
    }

    private fun extractSeriesSlugFromPage(doc: org.jsoup.nodes.Document, baseUrl: String): String? {
        // Procurar iframes com src contendo /tv/
        val iframes = doc.select("iframe[src*='/tv/']")
        
        for (iframe in iframes) {
            val src = iframe.attr("src")
            val tvPattern = Regex("""/tv/([^/?]+)""")
            val tvMatch = tvPattern.find(src)
            if (tvMatch != null) {
                return tvMatch.groupValues[1]
            }
        }
        
        // Procurar em scripts por URL /tv/
        val scripts = doc.select("script")
        for (script in scripts) {
            val scriptText = script.html()
            
            val tvPattern = Regex("""["'](https?://watch\.brplayer\.cc/tv/[^"']+)["']""")
            val matches = tvPattern.findAll(scriptText)
            
            for (match in matches) {
                val url = match.groupValues[1]
                val slug = url.substringAfterLast("/tv/").substringBefore("?").substringBefore("#")
                if (slug.isNotBlank()) {
                    return slug
                }
            }
        }
        
        return null
    }

    private fun extractEpisodesFromHtml(document: org.jsoup.nodes.Document, tmdbInfo: TMDBInfo?): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        // Procurar episódios no dropdown
        val episodeDropdown = document.selectFirst("#episodeDropdown")
        if (episodeDropdown != null) {
            val episodeButtons = episodeDropdown.select("button[data-id]")
            
            episodeButtons.forEachIndexed { index, button ->
                try {
                    val videoSlug = button.attr("data-id")
                    val buttonText = button.text().trim()
                    
                    val epNumber = extractEpisodeNumberFromText(buttonText) ?: (index + 1)
                    val episodeTitle = if (buttonText.isNotBlank() && buttonText != "Episódio $epNumber") {
                        buttonText
                    } else {
                        "Episódio $epNumber"
                    }
                    
                    val episodeUrl = "https://watch.brplayer.cc/watch/$videoSlug"
                    
                    val tmdbEpisode = findTMDBEpisode(tmdbInfo, 1, epNumber)
                    
                    val episode = if (tmdbEpisode != null) {
                        val descriptionWithDuration = buildDescriptionWithDuration(
                            tmdbEpisode.overview,
                            tmdbEpisode.runtime
                        )
                        
                        newEpisode(episodeUrl) {
                            this.name = tmdbEpisode.name
                            this.season = 1
                            this.episode = epNumber
                            this.posterUrl = tmdbEpisode.still_path?.let { "$tmdbImageUrl/w300$it" }
                            this.description = descriptionWithDuration
                        }
                    } else {
                        newEpisode(episodeUrl) {
                            this.name = episodeTitle
                            this.season = 1
                            this.episode = epNumber
                        }
                    }
                    
                    episodes.add(episode)
                    
                } catch (e: Exception) {
                    // Ignorar erro
                }
            }
        }
        
        // Se não encontrou nada, criar pelo menos um episódio
        if (episodes.isEmpty()) {
            episodes.add(
                newEpisode(document.location()) {
                    name = "Episódio 1"
                    season = 1
                    episode = 1
                }
            )
        }
        
        return episodes
    }

    private fun buildDescriptionWithDuration(overview: String?, runtime: Int?): String? {
        return when {
            overview != null && runtime != null && runtime > 0 -> {
                "$overview\n\nDuração: $runtime min"
            }
            overview != null -> {
                overview
            }
            runtime != null && runtime > 0 -> {
                "Duração: $runtime min"
            }
            else -> null
        }
    }

    private fun findTMDBEpisode(tmdbInfo: TMDBInfo?, season: Int, episode: Int): TMDBEpisode? {
        if (tmdbInfo == null) return null

        val episodes = tmdbInfo.seasonsEpisodes[season]
        if (episodes == null) {
            // Tentar encontrar em qualquer temporada se a temporada específica não for encontrada
            for ((_, seasonEpisodes) in tmdbInfo.seasonsEpisodes) {
                val found = seasonEpisodes.find { it.episode_number == episode }
                if (found != null) return found
            }
            return null
        }

        return episodes.find { it.episode_number == episode }
    }

    private fun extractEpisodeNumberFromText(text: String): Int? {
        try {
            val patterns = listOf(
                Regex("""Episódio\s+(\d+)""", RegexOption.IGNORE_CASE),
                Regex("""Episode\s+(\d+)""", RegexOption.IGNORE_CASE),
                Regex("""E(\d+)""", RegexOption.IGNORE_CASE),
                Regex("""EP\s*(\d+)""", RegexOption.IGNORE_CASE),
                Regex("""\b(\d{1,3})\b""")
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
    // FUNÇÃO LOAD PRINCIPAL (COM ESTRATÉGIA DO SUPERFLIX)
    // =============================================
    override suspend fun load(url: String): LoadResponse? {
        println("[CineAgora] Carregando URL: $url")
        
        val doc = app.get(url).document
        
        // 1. Extrair informações básicas
        val bannerUrl = extractBannerUrl(doc)
        val posterUrl = doc.selectFirst("meta[property='og:image']")?.attr("content")?.let { fixUrl(it) }
            ?: doc.selectFirst("#info--box .cover-img")?.attr("src")?.let { fixUrl(it) }
            ?: bannerUrl
        
        val title = doc.selectFirst("h1.title, h1, .title, h2")?.text()?.trim() ?: "Título não encontrado"
        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
        
        // 2. Determinar se é série ou filme
        val isSerie = url.contains("/series-") || url.contains("/serie-") || url.contains("/tv-") || 
                     url.contains("/series-online") ||
                     doc.select(".player-controls, #episodeDropdown, .seasons").isNotEmpty()
        
        println("[CineAgora] É série? $isSerie")
        
        // 3. Buscar informações do TMDB
        val yearFromSite = extractYear(doc)
        val tmdbInfo = searchOnTMDB(cleanTitle, yearFromSite, isSerie)
        
        // 4. Extrair episódios se for série (usando estratégia do SuperFlix)
        val episodes = if (isSerie) {
            extractEpisodesWithTMDBInfo(doc, url, tmdbInfo, true)
        } else {
            emptyList()
        }
        
        println("[CineAgora] Total de episódios extraídos: ${episodes.size}")
        
        // 5. Informações adicionais do site
        val plotFromSite = doc.selectFirst(".info-description, .description, .sinopse, .plot")?.text()?.trim()
        val genresFromSite = extractGenres(doc)
        
        // 6. Recomendações do site
        val recommendations = extractRecommendationsFromSite(doc)
        
        // 7. Encontrar URL do player para filmes
        val playerUrl = if (!isSerie) {
            findPlayerUrl(doc) ?: url
        } else {
            url
        }
        
        // 8. Criar resposta
        return if (isSerie) {
            createSeriesLoadResponse(tmdbInfo, url, episodes, recommendations, plotFromSite, genresFromSite, bannerUrl, posterUrl, yearFromSite)
        } else {
            createMovieLoadResponse(tmdbInfo, playerUrl, recommendations, plotFromSite, genresFromSite, bannerUrl, posterUrl, yearFromSite)
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
        episodes: List<Episode>,
        siteRecommendations: List<SearchResponse>,
        plotFromSite: String?,
        genresFromSite: List<String>?,
        bannerUrlFromSite: String?,
        posterUrlFromSite: String?,
        yearFromSite: Int?
    ): LoadResponse {
        // Informações do TMDB ou do site
        val title = tmdbInfo?.title ?: app.get(url).document.selectFirst("h1.title, h1, .title, h2")?.text()?.trim() ?: "Título não encontrado"
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
        siteRecommendations: List<SearchResponse>,
        plotFromSite: String?,
        genresFromSite: List<String>?,
        bannerUrlFromSite: String?,
        posterUrlFromSite: String?,
        yearFromSite: Int?
    ): LoadResponse {
        val doc = app.get(playerUrl).document
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
