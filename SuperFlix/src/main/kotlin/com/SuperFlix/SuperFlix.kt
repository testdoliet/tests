package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat

class SuperFlix : MainAPI() {
    override var mainUrl = "https://superflix21.lol"
    override var name = "SuperFlix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override val usesWebView = true

    override val mainPage = mainPageOf(
        "$mainUrl/lancamentos" to "Lançamentos",
        "$mainUrl/filmes" to "Últimos Filmes",
        "$mainUrl/series" to "Últimas Séries",
        "$mainUrl/animes" to "Últimas Animes"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + if (page > 1) "?page=$page" else ""
        val document = app.get(url).document

        val home = document.select("a.card, div.recs-grid a.rec-card").mapNotNull { element ->
            element.toSearchResult()
        }

        return newHomePageResponse(request.name, home.distinctBy { it.url })
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = attr("title") ?: selectFirst("img")?.attr("alt") ?: return null
        val href = attr("href") ?: return null

        val localPoster = selectFirst("img")?.attr("src")?.let { fixUrl(it) }
        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()

        val badge = selectFirst(".badge-kind")?.text()?.lowercase() ?: ""
        val isAnime = badge.contains("anime") || href.contains("/anime/") ||
                      title.contains("(Anime)", ignoreCase = true)
        val isSerie = badge.contains("série") || badge.contains("serie") ||
                     href.contains("/serie/") ||
                     (!isAnime && (badge.contains("tv") || href.contains("/tv/")))
        val isMovie = !isSerie && !isAnime

        return when {
            isAnime -> {
                newAnimeSearchResponse(cleanTitle, fixUrl(href), TvType.Anime) {
                    this.posterUrl = localPoster
                    this.year = year
                }
            }
            isSerie -> {
                newTvSeriesSearchResponse(cleanTitle, fixUrl(href), TvType.TvSeries) {
                    this.posterUrl = localPoster
                    this.year = year
                }
            }
            else -> {
                newMovieSearchResponse(cleanTitle, fixUrl(href), TvType.Movie) {
                    this.posterUrl = localPoster
                    this.year = year
                }
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/buscar?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(searchUrl).document

        return document.select(".grid .card, a.card").mapNotNull { card ->
            try {
                val title = card.attr("title") ?: card.selectFirst("img")?.attr("alt") ?: return@mapNotNull null
                val href = card.attr("href") ?: return@mapNotNull null

                val poster = card.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
                val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()

                val badge = card.selectFirst(".badge-kind")?.text()?.lowercase() ?: ""
                val isAnime = badge.contains("anime") || href.contains("/anime/") ||
                             title.contains("(Anime)", ignoreCase = true)
                val isSerie = badge.contains("série") || badge.contains("serie") ||
                             href.contains("/serie/") ||
                             (!isAnime && (badge.contains("tv") || href.contains("/tv/")))

                when {
                    isAnime -> newAnimeSearchResponse(cleanTitle, fixUrl(href), TvType.Anime) {
                        this.posterUrl = poster
                        this.year = year
                    }
                    isSerie -> newTvSeriesSearchResponse(cleanTitle, fixUrl(href), TvType.TvSeries) {
                        this.posterUrl = poster
                        this.year = year
                    }
                    else -> newMovieSearchResponse(cleanTitle, fixUrl(href), TvType.Movie) {
                        this.posterUrl = poster
                        this.year = year
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        println("🔍 [DEBUG] Iniciando load para URL: $url")

        val document = app.get(url).document

        val titleElement = document.selectFirst("h1, .title")
        val title = titleElement?.text() ?: return null
        println("🔍 [DEBUG] Título encontrado no site: $title")

        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
        println("🔍 [DEBUG] Título limpo: $cleanTitle | Ano: $year")

        val isAnime = url.contains("/anime/") || title.contains("(Anime)", ignoreCase = true)
        val isSerie = url.contains("/serie/") || url.contains("/tv/") ||
                     (!isAnime && document.selectFirst(".episode-list, .season-list, .seasons") != null)
        println("🔍 [DEBUG] Tipo: ${if (isAnime) "Anime" else if (isSerie) "Série" else "Filme"}")

        val siteRecommendations = extractRecommendationsFromSite(document)

        return createLoadResponseFromSite(document, url, cleanTitle, year, isAnime, isSerie, siteRecommendations)
    }

    private fun extractRecommendationsFromSite(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val recommendations = document.select(".recs-grid .rec-card, .recs-grid a").mapNotNull { element ->
            try {
                val href = element.attr("href") ?: return@mapNotNull null
                if (href.isBlank() || href == "#") return@mapNotNull null

                val imgElement = element.selectFirst("img")
                val title = imgElement?.attr("alt") ?:
                           element.selectFirst(".rec-title")?.text() ?:
                           element.attr("title") ?:
                           return@mapNotNull null

                val poster = imgElement?.attr("src")?.let { fixUrl(it) }
                val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
                val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()

                val isAnime = href.contains("/anime/") || title.contains("(Anime)", ignoreCase = true)
                val isSerie = href.contains("/serie/") || href.contains("/tv/")
                val isMovie = !isSerie && !isAnime

                when {
                    isAnime -> newAnimeSearchResponse(cleanTitle, fixUrl(href), TvType.Anime) {
                        this.posterUrl = poster
                        this.year = year
                    }
                    isSerie -> newTvSeriesSearchResponse(cleanTitle, fixUrl(href), TvType.TvSeries) {
                        this.posterUrl = poster
                        this.year = year
                    }
                    else -> newMovieSearchResponse(cleanTitle, fixUrl(href), TvType.Movie) {
                        this.posterUrl = poster
                        this.year = year
                    }
                }
            } catch (e: Exception) {
                null
            }
        }

        println("🔍 [DEBUG] Recomendações encontradas no site: ${recommendations.size}")
        return recommendations
    }

    private suspend fun createLoadResponseFromSite(
        document: org.jsoup.nodes.Document,
        url: String,
        title: String,
        year: Int?,
        isAnime: Boolean,
        isSerie: Boolean,
        siteRecommendations: List<SearchResponse>
    ): LoadResponse {
        println("🏗️ [DEBUG] Criando resposta com dados do site")

        val ogImage = document.selectFirst("meta[property='og:image']")?.attr("content")
        val poster = ogImage?.let { fixUrl(it) } ?: document.selectFirst("img.poster")?.attr("src")?.let { fixUrl(it) }
        println("🏗️ [DEBUG] Poster do site: $poster")

        val description = document.selectFirst("meta[name='description']")?.attr("content")
        val synopsis = document.selectFirst(".syn, .description, .sinopse, .plot")?.text()
        val plot = (description ?: synopsis)?.trim()
        println("🏗️ [DEBUG] Plot do site: ${plot?.take(50)}...")

        val tags = document.select("a.chip, .chip, .genre, .tags").map { it.text().trim() }
            .takeIf { it.isNotEmpty() && it.any { tag -> tag.isNotBlank() } }
            ?.filter { it.isNotBlank() }
            ?.toList()
        println("🏗️ [DEBUG] Tags do site: $tags")

        val actors = extractActorsFromSite(document)
        println("🏗️ [DEBUG] Atores encontrados: ${actors.size}")

        val duration = extractDurationFromSite(document)
        println("🏗️ [DEBUG] Duração: $duration")

        return if (isAnime || isSerie) {
            println("🏗️ [DEBUG] Criando série/Anime")
            val type = if (isAnime) TvType.Anime else TvType.TvSeries

            // Extrair episódios do site
            val episodes = extractEpisodesFromSite(document, url, isAnime, isSerie)

            newTvSeriesLoadResponse(title, url, type, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                
                if (actors.isNotEmpty()) {
                    addActors(actors)
                }
                
                this.recommendations = siteRecommendations.takeIf { it.isNotEmpty() }
                println("🏗️ [DEBUG] Série criada com ${episodes.size} episódios")
            }
        } else {
            println("🏗️ [DEBUG] Criando filme")
            val playerUrl = findPlayerUrl(document)
            println("🏗️ [DEBUG] Player URL: $playerUrl")

            newMovieLoadResponse(title, url, TvType.Movie, playerUrl ?: url) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.duration = duration
                
                if (actors.isNotEmpty()) {
                    addActors(actors)
                }
                
                this.recommendations = siteRecommendations.takeIf { it.isNotEmpty() }
            }
        }
    }

    private fun extractActorsFromSite(document: org.jsoup.nodes.Document): List<Actor> {
        val actors = mutableListOf<Actor>()
        
        // Tentar extrair atores de várias formas
        val actorElements = document.select(".actors-list .actor, .cast-list li, [class*='actor'], [class*='cast']")
        
        if (actorElements.isNotEmpty()) {
            actorElements.forEach { element ->
                val name = element.text().trim()
                if (name.isNotBlank() && name.length > 1 && !name.contains("...")) {
                    val img = element.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                    actors.add(Actor(name, image = img))
                }
            }
        }
        
        // Tentar extrair do texto se não encontrar elementos específicos
        if (actors.isEmpty()) {
            val text = document.text()
            val actorSections = listOf(
                "Elenco:", "Atores:", "Cast:", "Protagonistas:", "Personagens:"
            )
            
            for (section in actorSections) {
                if (text.contains(section)) {
                    val start = text.indexOf(section) + section.length
                    val end = text.indexOf("\n", start)
                    val actorText = if (end > start) text.substring(start, end) else text.substring(start)
                    
                    actorText.split(",", "•", "|", ";").forEach { actorName ->
                        val name = actorName.trim()
                        if (name.isNotBlank() && name.length > 2) {
                            actors.add(Actor(name))
                        }
                    }
                    break
                }
            }
        }
        
        return actors.distinctBy { it.name }.take(15)
    }

    private fun extractDurationFromSite(document: org.jsoup.nodes.Document): Int? {
        // Tentar extrair duração de várias formas
        val durationElements = document.select("[class*='duration'], [class*='runtime'], .time, .duracao")
        
        for (element in durationElements) {
            val text = element.text().trim()
            if (text.contains("min") || text.contains("h")) {
                // Extrair minutos de formato como "2h 10min" ou "130min"
                val hoursMatch = Regex("(\\d+)\\s*h").find(text)
                val minutesMatch = Regex("(\\d+)\\s*min").find(text)
                
                var totalMinutes = 0
                hoursMatch?.let { totalMinutes += it.groupValues[1].toIntOrNull() ?: 0 * 60 }
                minutesMatch?.let { totalMinutes += it.groupValues[1].toIntOrNull() ?: 0 }
                
                if (totalMinutes > 0) return totalMinutes
            }
            
            // Se for apenas números, assumir minutos
            val numbers = Regex("\\d+").findAll(text).toList()
            if (numbers.size == 1) {
                val minutes = numbers[0].value.toIntOrNull()
                if (minutes != null && minutes in 1..300) return minutes
            }
        }
        
        return null
    }

    private suspend fun extractEpisodesFromSite(
        document: org.jsoup.nodes.Document,
        url: String,
        isAnime: Boolean,
        isSerie: Boolean
    ): List<Episode> {
        println("🔍 [SITE DEBUG] Extraindo episódios do site")
        val episodes = mutableListOf<Episode>()

        // Múltiplos seletores para encontrar episódios
        val selectors = listOf(
            "button.bd-play[data-url]",
            "a.episode-card",
            ".episode-item",
            ".episode-link",
            "[class*='episode']",
            ".ep-list a",
            ".episodes-grid a",
            ".list-episodes a"
        )

        for (selector in selectors) {
            val episodeElements = document.select(selector)
            if (episodeElements.isNotEmpty()) {
                println("🔍 [SITE DEBUG] Encontrados ${episodeElements.size} elementos com seletor: $selector")
                
                episodeElements.forEachIndexed { index, element ->
                    try {
                        val dataUrl = element.attr("data-url") ?: element.attr("href") ?: ""
                        if (dataUrl.isBlank()) {
                            println("⚠️ [SITE DEBUG] Elemento $index sem data-url/href")
                            return@forEachIndexed
                        }

                        val epNumber = extractEpisodeNumber(element, index + 1)
                        val seasonNumber = element.attr("data-season").toIntOrNull() ?: 1

                        val episodeTitle = element.selectFirst(".ep-title, .title, .name")?.text()?.trim()
                            ?: "Episódio $epNumber"
                            
                        val episodeDescription = element.selectFirst(".ep-desc, .description, .sinopse")?.text()?.trim()

                        val episode = newEpisode(fixUrl(dataUrl)) {
                            this.name = episodeTitle
                            this.season = seasonNumber
                            this.episode = epNumber
                            
                            if (!episodeDescription.isNullOrBlank()) {
                                this.description = episodeDescription
                            }
                        }

                        episodes.add(episode)
                    } catch (e: Exception) {
                        println("❌ [SITE DEBUG] Erro ao processar episódio $index: ${e.message}")
                    }
                }
                
                if (episodes.isNotEmpty()) {
                    break // Parar no primeiro seletor que encontrar episódios
                }
            }
        }

        // Ordenar episódios por temporada e episódio
        val sortedEpisodes = episodes.sortedWith(compareBy({ it.season }, { it.episode }))
        
        println("✅ [SITE DEBUG] Total de episódios extraídos do site: ${sortedEpisodes.size}")
        return sortedEpisodes
    }

    private fun extractEpisodeNumber(element: Element, default: Int): Int {
        // Tentar várias formas de extrair o número do episódio
        return element.attr("data-ep").toIntOrNull() ?:
               element.attr("data-episode").toIntOrNull() ?:
               element.selectFirst(".ep-number, .number, .episode-number, .ep")?.text()?.toIntOrNull() ?:
               Regex("Ep\\.?\\s*(\\d+)").find(element.text())?.groupValues?.get(1)?.toIntOrNull() ?:
               Regex("Epis[oó]dio\\s*(\\d+)").find(element.text())?.groupValues?.get(1)?.toIntOrNull() ?:
               Regex("(\\d+)").find(element.text())?.groupValues?.get(1)?.toIntOrNull() ?:
               default
    }

    private fun findPlayerUrl(document: org.jsoup.nodes.Document): String? {
        // Prioridade 1: Botão com data-url
        val playButton = document.selectFirst("button.bd-play[data-url]")
        if (playButton != null) {
            val url = playButton.attr("data-url")
            println("🔍 [DEBUG] Player URL encontrado no botão: $url")
            return fixUrl(url)
        }

        // Prioridade 2: Iframe
        val iframe = document.selectFirst("iframe[src*='fembed'], iframe[src*='filemoon'], iframe[src*='player'], iframe[src*='embed']")
        if (iframe != null) {
            val url = iframe.attr("src")
            println("🔍 [DEBUG] Player URL encontrado no iframe: $url")
            return fixUrl(url)
        }

        // Prioridade 3: Scripts com URL
        val scripts = document.select("script")
        for (script in scripts) {
            val scriptContent = script.html() + script.data()
            val patterns = listOf(
                Regex("src:\\s*['\"]([^'\"]+)['\"]"),
                Regex("url:\\s*['\"]([^'\"]+)['\"]"),
                Regex("file:\\s*['\"]([^'\"]+)['\"]"),
                Regex("https?://[^\"'\s]+m3u8[^\"'\s]*"),
                Regex("https?://[^\"'\s]+mp4[^\"'\s]*")
            )
            
            for (pattern in patterns) {
                val match = pattern.find(scriptContent)
                if (match != null) {
                    val url = match.value
                    println("🔍 [DEBUG] Player URL encontrado no script: $url")
                    return fixUrl(url)
                }
            }
        }

        // Prioridade 4: Link direto
        val videoLink = document.selectFirst("a[href*='.m3u8'], a[href*='.mp4'], a[href*='watch'], a[href*='assistir']")
        if (videoLink != null) {
            val url = videoLink.attr("href")
            println("🔍 [DEBUG] Player URL encontrado no link: $url")
            return fixUrl(url)
        }

        println("⚠️ [DEBUG] Nenhum player URL encontrado")
        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return SuperFlixExtractor.extractVideoLinks(data, mainUrl, name, callback)
    }
}
