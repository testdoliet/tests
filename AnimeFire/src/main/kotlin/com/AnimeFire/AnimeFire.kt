package com.AnimeFire

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

class AnimeFirePlus : MainAPI() {
    override var mainUrl = "https://animefire.plus"
    override var name = "AnimeFire Plus"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie)
    override val usesWebView = false

    companion object {
        private const val SEARCH_PATH = "/pesquisar"
        private const val ALL_EPISODES_SUFFIX = "-todos-os-episodios"
    }

    override val mainPage = mainPageOf(
        "$mainUrl" to "Lançamentos",
        "$mainUrl/animes" to "Animes Populares",
        "$mainUrl/filmes" to "Filmes de Anime"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        
        val homeItems = when (request.name) {
            "Lançamentos" -> extractLatestReleases(document)
            "Animes Populares" -> extractPopularAnimes(document)
            "Filmes de Anime" -> extractAnimeMovies(document)
            else -> emptyList()
        }
        
        return newHomePageResponse(request.name, homeItems)
    }

    private fun extractLatestReleases(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        
        // 1. Seção "Em lançamento" (Carousel Principal)
        val emLancamentoSection = document.selectFirst(".divSection h1.section2:contains(Em lançamento)")
        val lancamentoCarousel = emLancamentoSection?.parent()?.nextElementSibling()?.selectFirst(".owl-carousel-home")
        lancamentoCarousel?.select(".divArticleLancamentos")?.forEach { item ->
            item.toSearchResult()?.let { items.add(it) }
        }
        
        // 2. Seção "Últimos Episódios Adicionados"
        val ultimosEpSection = document.selectFirst(".divSectionUltimosEpsHome h2.section2:contains(Últimos Episódios Adicionados)")
        val episodiosContainer = ultimosEpSection?.parent()?.nextElementSibling()?.selectFirst(".card-group .row")
        episodiosContainer?.select(".divCardUltimosEpsHome")?.forEach { card ->
            card.toEpisodeSearchResult()?.let { items.add(it) }
        }
        
        // 3. Seção "Destaques da semana"
        val destaquesSection = document.selectFirst(".divSection h1.section2:contains(Destaques da semana)")
        val destaquesCarousel = destaquesSection?.parent()?.nextElementSibling()?.selectFirst(".owl-carousel-semana")
        destaquesCarousel?.select(".divArticleLancamentos")?.forEach { item ->
            item.toSearchResult()?.let { items.add(it) }
        }
        
        return items.distinctBy { it.url }
    }

    private fun extractPopularAnimes(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        
        // Seção "Últimos animes adicionados" e outras seções de anime
        document.select(".divSection h1.section2:contains(Últimos animes adicionados)").firstOrNull()?.let { section ->
            val carousel = section.parent()?.nextElementSibling()?.selectFirst(".owl-carousel-l_dia")
            carousel?.select(".divArticleLancamentos")?.forEach { item ->
                item.toSearchResult()?.let { items.add(it) }
            }
        }
        
        // Também incluir animes da seção "Em lançamento"
        val emLancamentoSection = document.selectFirst(".divSection h1.section2:contains(Em lançamento)")
        val lancamentoCarousel = emLancamentoSection?.parent()?.nextElementSibling()?.selectFirst(".owl-carousel-home")
        lancamentoCarousel?.select(".divArticleLancamentos")?.forEach { item ->
            item.toSearchResult()?.let { items.add(it) }
        }
        
        return items.distinctBy { it.url }
    }

    private fun extractAnimeMovies(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        
        // Procurar por filmes nas várias seções
        document.select(".divArticleLancamentos").forEach { item ->
            val titleElement = item.selectFirst("h3.animeTitle")
            val title = titleElement?.text()?.trim() ?: ""
            val href = item.selectFirst("a.item")?.attr("href") ?: ""
            
            if (href.contains("/filmes/") || title.contains("Movie", ignoreCase = true)) {
                item.toSearchResult()?.let { items.add(it) }
            }
        }
        
        return items.distinctBy { it.url }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = selectFirst("a.item") ?: return null
        val href = link.attr("href") ?: return null
        if (href.isBlank()) return null
        
        val titleElement = selectFirst("h3.animeTitle") ?: return null
        val title = titleElement.text().trim()
        
        // Extrair imagem (lazy loading)
        val imgElement = selectFirst("img.imgAnimes, img.owl-lazy")
        val poster = when {
            imgElement?.hasAttr("data-src") == true -> imgElement.attr("data-src")
            imgElement?.hasAttr("src") == true -> imgElement.attr("src")
            else -> selectFirst("img")?.attr("src")
        } ?: return null
        
        // Extrair score
        val scoreElement = selectFirst("span.horaUltimosEps")
        val scoreText = scoreElement?.text()
        val score = if (scoreText != null && scoreText != "N/A") {
            scoreText.toFloatOrNull()
        } else {
            null
        }
        
        // Extrair classificação etária
        val ageRatingElement = selectFirst(".text-blockCapaAnimeTags span")
        val ageRating = ageRatingElement?.text()?.trim()
        
        // Extrair ranking para destaques
        val rankingElement = selectFirst("span.numbTopTen")
        val ranking = rankingElement?.text()?.toIntOrNull()
        
        val cleanTitle = title.replace(Regex("(?i)(dublado|legendado|todos os episódios|\\(\\d{4}\\))$"), "").trim()
        
        val isMovie = href.contains("/filmes/") || title.contains("Movie", ignoreCase = true)
        
        return if (isMovie) {
            newMovieSearchResponse(cleanTitle, fixUrl(href), TvType.Movie) {
                this.posterUrl = fixUrl(poster)
                this.rating = score?.div(10)
            }
        } else {
            newAnimeSearchResponse(cleanTitle, fixUrl(href), TvType.Anime) {
                this.posterUrl = fixUrl(poster)
                this.rating = score?.div(10)
            }
        }
    }

    private fun Element.toEpisodeSearchResult(): SearchResponse? {
        val link = selectFirst("article.card a") ?: return null
        val href = link.attr("href") ?: return null
        if (href.isBlank()) return null
        
        val titleElement = selectFirst("h3.animeTitle") ?: return null
        val title = titleElement.text().trim()
        
        // Extrair número do episódio
        val epNumberElement = selectFirst(".numEp")
        val epNumber = epNumberElement?.text()?.toIntOrNull()
        
        // Extrair imagem do episódio
        val imgElement = selectFirst("img.imgAnimesUltimosEps, img.transitioning_src")
        val poster = when {
            imgElement?.hasAttr("data-src") == true -> imgElement.attr("data-src")
            imgElement?.hasAttr("src") == true -> imgElement.attr("src")
            else -> selectFirst("img")?.attr("src")
        } ?: return null
        
        // Extrair data/hora do episódio
        val dateElement = selectFirst(".ep-dateModified")
        val dateTimestamp = dateElement?.attr("data-date-modified")?.toLongOrNull()
        
        val cleanTitle = if (epNumber != null) {
            "${title} - Episódio $epNumber"
        } else {
            title
        }
        
        return newAnimeSearchResponse(cleanTitle, fixUrl(href), TvType.Anime) {
            this.posterUrl = fixUrl(poster)
            this.episode = epNumber
            
            // Definir data de adição se disponível
            dateTimestamp?.let {
                try {
                    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
                    val date = sdf.parse(it.toString())
                    date?.time?.let { time -> this.year = (time / (1000L * 60 * 60 * 24 * 365) + 1970).toInt() }
                } catch (e: Exception) {
                    // Ignorar erro de parsing de data
                }
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl$SEARCH_PATH/${URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(searchUrl).document

        return document.select("article.containerAnimes a.item").mapNotNull { element ->
            try {
                element.toSearchResultFromSearch()
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun Element.toSearchResultFromSearch(): SearchResponse? {
        val titleElement = selectFirst("h3.animeTitle") ?: return null
        val title = titleElement.text().trim()
        val href = attr("href") ?: return null

        val imgElement = selectFirst("img.imgAnimes")
        val poster = when {
            imgElement?.hasAttr("data-src") == true -> imgElement.attr("data-src")
            imgElement?.hasAttr("src") == true -> imgElement.attr("src")
            else -> selectFirst("img")?.attr("src")
        } ?: ""

        val cleanTitle = title.replace(Regex("(?i)(dublado|legendado|todos os episódios|\\(\\d{4}\\))$"), "").trim()

        val isMovie = href.contains("/filmes/") || title.contains("Movie", ignoreCase = true)

        return if (isMovie) {
            newMovieSearchResponse(cleanTitle, fixUrl(href), TvType.Movie) {
                this.posterUrl = if (poster.isNotEmpty()) fixUrl(poster) else null
            }
        } else {
            newAnimeSearchResponse(cleanTitle, fixUrl(href), TvType.Anime) {
                this.posterUrl = if (poster.isNotEmpty()) fixUrl(poster) else null
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        // Container principal
        val mainContainer = document.selectFirst("div.col-lg-9.text-white.divDivAnimeInfo") ?: return null

        // 1. Título Principal
        val titleElement = mainContainer.selectFirst(".div_anime_names h1.quicksand400") ?: 
                          mainContainer.selectFirst(".main_div_anime_info h1") ?: return null
        val title = titleElement.text().trim()

        // 2. Títulos Alternativos
        val altTitles = mainContainer.select(".div_anime_names h6.text-gray").map { it.text().trim() }

        // 3. Imagem Principal (Poster)
        val posterDiv = mainContainer.selectFirst(".sub_animepage_img, .divImgAnimePageInfo")
        val posterImg = posterDiv?.selectFirst("img.transitioning_src")
        val posterUrl = when {
            posterImg?.hasAttr("src") == true -> posterImg.attr("src")
            posterImg?.hasAttr("data-src") == true -> posterImg.attr("data-src")
            else -> null
        }

        // 4. Classificação Etária
        val ageRatingElement = mainContainer.selectFirst(".animeInfo .spanGenerosLink:first-child")
        val ageRating = ageRatingElement?.text()?.trim()

        // 5. Gêneros
        val genreElements = mainContainer.select(".animeInfo .spanGenerosLink")
        val genres = if (genreElements.size > 1) {
            genreElements.drop(1).map { it.text().trim() }
        } else {
            genreElements.map { it.text().trim() }
        }

        // 6. Informações Gerais
        val infoElements = mainContainer.select(".divAnimePageInfo .animeInfo")
        val infoMap = mutableMapOf<String, String>()
        
        infoElements.forEach { element ->
            val keyElement = element.selectFirst("b")
            val valueElement = element.selectFirst("span.spanAnimeInfo")
            
            if (keyElement != null && valueElement != null) {
                val key = keyElement.text().replace(":", "").trim()
                val value = valueElement.text().trim()
                infoMap[key] = value
            }
        }

        // Extrair informações específicas
        val season = infoMap["Temporada"]
        val studio = infoMap["Estúdios"]
        val status = infoMap["Status"]
        val audio = infoMap["Audio"]
        val yearText = infoMap["Ano"]
        val year = yearText?.toIntOrNull()

        // 7. Sinopse
        val synopsisElement = mainContainer.selectFirst(".divSinopse span.spanAnimeInfo")
        val plot = synopsisElement?.text()?.trim()

        // 8. Score e Votos
        val scoreElement = document.selectFirst("#anime_score")
        val votesElement = document.selectFirst("#anime_votos")
        val score = scoreElement?.text()?.trim()?.takeIf { it != "N/A" }?.toFloatOrNull()
        val votes = votesElement?.text()?.trim()

        // 9. Lista de Episódios
        val episodes = mutableListOf<Episode>()
        val videoListSection = mainContainer.selectFirst("section.mt-3.mb-2")
        val episodeElements = videoListSection?.select(".div_video_list a.lEp") ?: emptyList()
        
        episodeElements.forEach { episodeElement ->
            try {
                val episodeText = episodeElement.text().trim()
                val episodeHref = episodeElement.attr("href") ?: return@forEach
                
                // Extrair número do episódio
                val episodeNumber = Regex("Epis[oó]dio\\s*(\\d+)").find(episodeText)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("Ep\\.?\\s*(\\d+)").find(episodeText)?.groupValues?.get(1)?.toIntOrNull()
                    ?: 0
                
                // Extrair título do episódio
                val episodeName = episodeText.replace(Regex("\\s*-\\s*Epis[oó]dio\\s*\\d+.*"), "").trim()
                
                if (episodeHref.isNotBlank()) {
                    episodes.add(
                        newEpisode(fixUrl(episodeHref)) {
                            this.name = if (episodeName.isNotBlank() && episodeName != title) episodeName else "Episódio $episodeNumber"
                            this.episode = episodeNumber
                            this.season = 1
                        }
                    )
                }
            } catch (e: Exception) {
                // Ignorar episódios com erro
            }
        }

        // Ordenar episódios
        val sortedEpisodes = episodes.sortedBy { it.episode }

        // Determinar se é filme
        val isMovie = url.contains("/filmes/") || title.contains("Movie", ignoreCase = true) || episodes.isEmpty()

        if (!isMovie) {
            return newTvSeriesLoadResponse(title, url, TvType.Anime, sortedEpisodes) {
                this.posterUrl = posterUrl?.let { fixUrl(it) }
                this.backgroundPosterUrl = posterUrl?.let { fixUrl(it) }
                this.plot = plot
                this.tags = if (genres.isNotEmpty()) genres else null
                this.year = year
                this.rating = score?.div(10)
                
                this.recommendations = extractRecommendations(document)
                
                this.people = listOfNotNull(
                    studio?.let { Actor("Estúdio", it) }
                )
                
                this.status = when (status?.lowercase()) {
                    "em lançamento" -> ShowStatus.Ongoing
                    "concluído" -> ShowStatus.Completed
                    else -> null
                }
            }
        } else {
            val videoLink = if (episodes.isNotEmpty()) {
                episodes.first().url
            } else {
                val firstVideo = videoListSection?.selectFirst("a[href*='/animes/'], a[href*='/filmes/']")
                firstVideo?.attr("href") ?: url
            }
            
            return newMovieLoadResponse(title, url, TvType.Movie, fixUrl(videoLink)) {
                this.posterUrl = posterUrl?.let { fixUrl(it) }
                this.backgroundPosterUrl = posterUrl?.let { fixUrl(it) }
                this.plot = plot
                this.tags = if (genres.isNotEmpty()) genres else null
                this.year = year
                this.rating = score?.div(10)
                this.recommendations = extractRecommendations(document)
                
                this.people = listOfNotNull(
                    studio?.let { Actor("Estúdio", it) }
                )
            }
        }
    }

    private fun extractRecommendations(document: org.jsoup.nodes.Document): List<SearchResponse> {
        return document.select(".owl-carousel-anime .divArticleLancamentos a.item").mapNotNull { element ->
            try {
                element.toSearchResult()
            } catch (e: Exception) {
                null
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return extractVideoLinks(data, callback)
    }

    private suspend fun extractVideoLinks(url: String, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            val document = app.get(url).document
            
            // Procurar por iframes
            val iframe = document.selectFirst("iframe[src*='lightspeedst.net'], iframe[src*='lightspeedts.net']")
            
            if (iframe != null) {
                val iframeSrc = iframe.attr("src")
                return extractFromLightspeedUrl(iframeSrc, callback)
            }
            
            // Procurar scripts com URLs
            val html = app.get(url).text
            val mp4Regex = Regex("""["'](https?://[^"']*lightspeed(st|ts)\.net[^"']*\.mp4)["']""")
            val mp4Matches = mp4Regex.findAll(html)
            
            var found = false
            mp4Matches.forEach { match ->
                val videoUrl = match.groupValues[1]
                if (extractFromLightspeedUrl(videoUrl, callback)) {
                    found = true
                }
            }
            
            return found
        } catch (e: Exception) {
            return false
        }
    }
    
    private fun extractFromLightspeedUrl(url: String, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            val regex = Regex("""lightspeed(st|ts)\.net/s(\d+)/mp4/([^/]+)/([^/]+)/(\d+)\.mp4""")
            val match = regex.find(url)
            
            if (match != null) {
                val serverType = match.groupValues[1]
                val season = match.groupValues[2]
                val titlePath = match.groupValues[3]
                val quality = match.groupValues[4]
                val episode = match.groupValues[5]
                
                val qualities = listOf("fhd", "hd", "sd")
                
                qualities.forEach { qual ->
                    val videoUrl = "https://lightspeed${serverType}.net/s${season}/mp4/${titlePath}/${qual}/${episode}.mp4"
                    
                    callback(
                        ExtractorLink(
                            source = name,
                            name = "${qual.uppercase()} - AnimeFire",
                            url = videoUrl,
                            referer = "$mainUrl/",
                            quality = when (qual) {
                                "fhd" -> Qualities.FullHDP.value
                                "hd" -> Qualities.P720.value
                                else -> Qualities.P480.value
                            },
                            isM3u8 = false
                        )
                    )
                }
                
                return true
            }
        } catch (e: Exception) {
            // Ignorar erros
        }
        
        return false
    }
}
