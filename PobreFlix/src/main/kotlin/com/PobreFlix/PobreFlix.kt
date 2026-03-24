package com.PobreFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import org.jsoup.nodes.Element

class PobreFlix : MainAPI() {
    override var mainUrl = "https://lospobreflix.site"
    override var name = "PobreFlix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override val usesWebView = true

    companion object {
        private const val SEARCH_PATH = "/pesquisar"
        
        // Apenas as 6 seções principais
        private val MAIN_SECTIONS = listOf(
            "/episodios" to "Novos Episódios",
            "" to "Em Alta",
            "/filmes" to "Filmes",
            "/series" to "Séries",
            "/animes" to "Animes",
            "/doramas" to "Doramas"
        )
        
        /*
        // ============================================================
        // GÊNEROS COMPLETOS (COMENTADOS PARA REFERÊNCIA FUTURA)
        // ============================================================
        
        // Gêneros de Filmes (19)
        private val MOVIE_GENRES = listOf(
            "acao" to "Ação",
            "animacao" to "Animação",
            "aventura" to "Aventura",
            "cinema-tv" to "Cinema TV",
            "comedia" to "Comédia",
            "crime" to "Crime",
            "documentario" to "Documentário",
            "drama" to "Drama",
            "familia" to "Família",
            "fantasia" to "Fantasia",
            "faroeste" to "Faroeste",
            "ficcao-cientifica" to "Ficção Científica",
            "guerra" to "Guerra",
            "historia" to "História",
            "horror" to "Horror",
            "misterio" to "Mistério",
            "musica" to "Música",
            "romance" to "Romance",
            "terror" to "Terror"
        )
        
        // Gêneros de Séries (12)
        private val SERIES_GENRES = listOf(
            "animacao" to "Animação",
            "comedia" to "Comédia",
            "crime" to "Crime",
            "documentario" to "Documentário",
            "dorama" to "Dorama",
            "drama" to "Drama",
            "familia" to "Família",
            "faroeste" to "Faroeste",
            "historia" to "História",
            "misterio" to "Mistério",
            "reality" to "Reality",
            "romance" to "Romance"
        )
        
        // Gêneros de Animes (26)
        private val ANIME_GENRES = listOf(
            "acao" to "Ação",
            "artes-marciais" to "Artes Marciais",
            "animacao" to "Animação",
            "aventura" to "Aventura",
            "comedia" to "Comédia",
            "crime" to "Crime",
            "detetive" to "Detetive",
            "drama" to "Drama",
            "documentario" to "Documentário",
            "escolar" to "Escolar",
            "esportes" to "Esportes",
            "familia" to "Família",
            "fantasia" to "Fantasia",
            "faroeste" to "Faroeste",
            "infantil" to "Infantil",
            "jogo-de-estrategia" to "Jogos de Estratégia",
            "mitologia" to "Mitologia",
            "misterio" to "Mistério",
            "musica" to "Música",
            "reencarnacao" to "Reencarnação",
            "romance" to "Romance",
            "samurai" to "Samurai",
            "sobrenatural" to "Sobrenatural",
            "superpoder" to "Superpoder",
            "suspense" to "Suspense",
            "terror" to "Terror"
        )
        
        // Gêneros de Doramas (8)
        private val DORAMA_GENRES = listOf(
            "comedia" to "Comédia",
            "crime" to "Crime",
            "documentario" to "Documentário",
            "drama" to "Drama",
            "misterio" to "Mistério",
            "familia" to "Família",
            "reality" to "Reality",
            "romance" to "Romance"
        )
        */
    }

    override val mainPage = mainPageOf(
        *MAIN_SECTIONS.map { (path, name) ->
            if (path.isEmpty()) mainUrl to name
            else "$mainUrl$path" to name
        }.toTypedArray()
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        println("=== getMainPage INICIADO ===")
        println("Page: $page")
        println("Request name: ${request.name}")
        println("Request data: ${request.data}")
        
        val url = request.data
        
        // Tratamento especial para a seção "Em Alta"
        if (request.name == "Em Alta") {
            println(">>> Processando seção: Em Alta")
            val document = app.get(url).document
            println("URL carregada: $url")
            println("Título da página: ${document.title()}")
            
            // Na página principal, os cards estão dentro de .swiper-slide article
            val elements = document.select(".swiper-slide article")
            println("Elementos encontrados com '.swiper-slide article': ${elements.size}")
            
            val items = elements.mapNotNull { element ->
                println("--- Processando elemento Em Alta ---")
                element.toSearchResult()
            }
            println("Items processados: ${items.size}")
            println("=== getMainPage FINALIZADO (Em Alta) ===\n")
            
            return newHomePageResponse(request.name, items, hasNext = false)
        }
        
        // Tratamento especial para "Novos Episódios" (horizontal)
        if (request.name == "Novos Episódios") {
            println(">>> Processando seção: Novos Episódios")
            val finalUrl = if (page > 1) {
                val newUrl = if (url.contains("?")) "$url&page=$page" else "$url?page=$page"
                println("Paginação: página $page, URL: $newUrl")
                newUrl
            } else {
                url
            }
            
            val document = app.get(finalUrl).document
            println("URL carregada: $finalUrl")
            println("Título da página: ${document.title()}")
            
            // Na página de episódios, os cards são <article> diretos dentro do grid
            val elements = document.select(".grid article")
            println("Elementos encontrados com '.grid article': ${elements.size}")
            
            val items = elements.mapNotNull { element ->
                println("--- Processando elemento Novos Episódios ---")
                element.toSearchResult()
            }
            println("Items processados: ${items.size}")
            
            val hasNextPage = document.select("a:contains(Próxima), .page-numbers a[href*='page'], .pagination a:contains(Próxima)").isNotEmpty()
            println("Has next page: $hasNextPage")
            println("=== getMainPage FINALIZADO (Novos Episódios) ===\n")
            
            // Retornar como HomePageList para definir isHorizontalImages = true
            return newHomePageResponse(
                list = HomePageList(request.name, items, isHorizontalImages = true),
                hasNext = hasNextPage
            )
        }
        
        // Para as outras seções (Filmes, Séries, Animes, Doramas)
        println(">>> Processando seção genérica: ${request.name}")
        val finalUrl = if (page > 1) {
            val newUrl = if (url.contains("?")) "$url&page=$page" else "$url?page=$page"
            println("Paginação: página $page, URL: $newUrl")
            newUrl
        } else {
            url
        }
        
        val document = app.get(finalUrl).document
        println("URL carregada: $finalUrl")
        println("Título da página: ${document.title()}")
        
        // Seletores para diferentes tipos de página
        val elements = document.select(".grid .group\\/card, .grid article")
        println("Elementos encontrados com '.grid .group/card, .grid article': ${elements.size}")
        
        val items = elements.mapNotNull { element ->
            println("--- Processando elemento genérico ---")
            element.toSearchResult()
        }
        println("Items processados: ${items.size}")
        
        val hasNextPage = document.select("a:contains(Próxima), .page-numbers a[href*='page'], .pagination a:contains(Próxima)").isNotEmpty()
        println("Has next page: $hasNextPage")
        println("=== getMainPage FINALIZADO (Genérico) ===\n")
        
        return newHomePageResponse(request.name, items, hasNext = hasNextPage)
    }
    
    private fun Element.toSearchResult(): SearchResponse? {
        println("  >>> toSearchResult INICIADO")
        
        // Busca o link dentro do elemento
        val linkElement = selectFirst("a")
        if (linkElement == null) {
            println("  ERRO: linkElement é null")
            return null
        }
        
        val href = linkElement.attr("href")
        if (href.isBlank()) {
            println("  ERRO: href está em branco")
            return null
        }
        println("  href: $href")
        
        // Busca a imagem dentro do link
        val imgElement = linkElement.selectFirst("img")
        if (imgElement == null) {
            println("  ERRO: imgElement é null")
            return null
        }
        
        // Tenta pegar a URL da imagem: primeiro data-src (lazy loading), depois src
        var poster = imgElement.attr("data-src")
        if (poster.isNullOrBlank()) {
            poster = imgElement.attr("src")
        }
        println("  poster original: $poster")
        
        val fixedPoster = if (!poster.isNullOrBlank()) fixUrl(poster) else null
        println("  poster corrigido: $fixedPoster")
        
        // Busca o título - tenta várias fontes
        var title = imgElement.attr("alt")
        if (title.isNullOrBlank()) {
            title = linkElement.attr("title")
        }
        if (title.isNullOrBlank()) {
            title = selectFirst(".text-sm, .font-bold")?.text()
        }
        if (title.isNullOrBlank()) {
            println("  ERRO: título está em branco")
            return null
        }
        println("  título original: $title")
        
        // Remove a palavra "poster" do título se existir
        title = title.replace(Regex("\\s+poster$", RegexOption.IGNORE_CASE), "").trim()
        println("  título após remover 'poster': $title")
        
        // Extrai ano do título se disponível
        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
        println("  título final: $cleanTitle, ano: $year")
        
        // Determina o tipo baseado na URL
        val isAnime = href.contains("/anime/")
        val isSerie = href.contains("/serie/") || href.contains("/dorama/")
        println("  isAnime: $isAnime, isSerie: $isSerie")
        
        val result = when {
            isAnime -> {
                println("  >>> Criando resposta de ANIME")
                newAnimeSearchResponse(cleanTitle, fixUrl(href), TvType.Anime) {
                    this.posterUrl = fixedPoster
                    this.year = year
                }
            }
            isSerie -> {
                println("  >>> Criando resposta de SÉRIE")
                newTvSeriesSearchResponse(cleanTitle, fixUrl(href), TvType.TvSeries) {
                    this.posterUrl = fixedPoster
                    this.year = year
                }
            }
            else -> {
                println("  >>> Criando resposta de FILME")
                newMovieSearchResponse(cleanTitle, fixUrl(href), TvType.Movie) {
                    this.posterUrl = fixedPoster
                    this.year = year
                }
            }
        }
        
        println("  <<< toSearchResult FINALIZADO com sucesso")
        return result
    }

    override suspend fun search(query: String): List<SearchResponse> {
        println("=== search INICIADO ===")
        println("Query: $query")
        
        val searchUrl = "$mainUrl$SEARCH_PATH?s=${java.net.URLEncoder.encode(query, "UTF-8")}"
        println("URL de busca: $searchUrl")
        
        val document = app.get(searchUrl).document
        println("Título da página de busca: ${document.title()}")
        
        val elements = document.select(".grid .group\\/card, .grid article, .swiper-slide article")
        println("Elementos encontrados na busca: ${elements.size}")
        
        val results = elements.mapNotNull { card ->
            try {
                card.toSearchResult()
            } catch (e: Exception) {
                println("ERRO ao processar resultado de busca: ${e.message}")
                null
            }
        }
        
        println("Resultados processados: ${results.size}")
        println("=== search FINALIZADO ===\n")
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        println("=== load INICIADO ===")
        println("URL: $url")
        
        val document = app.get(url).document
        println("Título da página: ${document.title()}")

        val titleElement = document.selectFirst("h1, .text-3xl.text-lead.font-bold")
        val title = titleElement?.text() ?: return null
        println("Título encontrado: $title")

        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
        println("Título limpo: $cleanTitle, ano: $year")

        val isAnime = url.contains("/anime/") || title.contains("(Anime)", ignoreCase = true)
        val isSerie = url.contains("/serie/") || url.contains("/dorama/") || 
                     (!isAnime && document.selectFirst("#episodes-list, .season-dropdown, .episode-card") != null)
        println("isAnime: $isAnime, isSerie: $isSerie")

        val ogImage = document.selectFirst("meta[property='og:image']")?.attr("content")
        val poster = ogImage?.let { fixUrl(it) }
        println("Poster: $poster")

        val synopsis = document.selectFirst(".text-slate-700.dark\\:text-slate-200.md\\:text-lg, .text-slate-900\\/90.dark\\:text-slate-100\\/90, meta[name='description']")?.attr("content")?.trim()
        println("Sinopse encontrada: ${synopsis?.take(100)}...")

        val tags = document.select(".flex.flex-wrap.gap-2 a, .px-3.py-1.rounded-full.text-xs.bg-slate-200")
            .map { it.text() }
            .takeIf { it.isNotEmpty() }
        println("Tags: $tags")

        val ratingPercent = document.selectFirst("text[x='18'][y='21']")?.text()?.replace("%", "")?.toFloatOrNull()
        val ratingValue = ratingPercent?.let { it / 10 }
        val score = ratingValue?.let { Score.from10(it) }
        println("Rating: $ratingPercent%, score: $score")

        val backdrop = document.selectFirst(".absolute.left-1\\/2 img, .blur-\\[4px\\] img")?.attr("src")?.let { fixUrl(it) }
        println("Backdrop: $backdrop")

        val durationText = document.selectFirst(".bg-slate-200.dark\\:bg-slate-700.rounded-lg.p-3:contains(min) .font-medium, .inline-flex.items-center.rounded-full.px-3.py-1:contains(min)")?.text()
        val duration = durationText?.let { 
            Regex("(\\d+)\\s*min").find(it)?.groupValues?.get(1)?.toIntOrNull()
        }
        println("Duração: $duration")

        val cast = document.select("#cast-section .swiper-slide .text-sm.font-bold, .swiper-slide .text-sm.font-bold")
            .map { it.text() }
            .takeIf { it.isNotEmpty() }
            ?.map { Actor(name = it) }
        println("Elenco: ${cast?.size} atores")

        val trailerKey = document.selectFirst("script:containsData(window.__trailerKeys)")?.data()?.let { script ->
            Regex("window\\.__trailerKeys\\s*=\\s*\\[\"([^\"]+)\"\\]").find(script)?.groupValues?.get(1)
        }
        println("Trailer key: $trailerKey")

        val siteRecommendations = document.select("#relatedSection .swiper-slide a, .related-swiper .swiper-slide a")
            .mapNotNull { element ->
                try {
                    val href = element.attr("href") ?: return@mapNotNull null
                    val imgElement = element.selectFirst("img")
                    val titleRec = imgElement?.attr("alt") ?: element.selectFirst("h3, .text-white.font-bold")?.text() ?: return@mapNotNull null
                    val posterRec = imgElement?.attr("src")?.let { fixUrl(it) }
                    val yearRec = element.selectFirst(".text-white\\/70.text-xs")?.text()?.toIntOrNull()
                    val cleanTitleRec = titleRec.replace(Regex("\\(\\d{4}\\)"), "").trim()

                    val isAnimeRec = href.contains("/anime/")
                    val isSerieRec = href.contains("/serie/") || href.contains("/dorama/")

                    when {
                        isAnimeRec -> newAnimeSearchResponse(cleanTitleRec, fixUrl(href), TvType.Anime) {
                            this.posterUrl = posterRec
                            this.year = yearRec
                        }
                        isSerieRec -> newTvSeriesSearchResponse(cleanTitleRec, fixUrl(href), TvType.TvSeries) {
                            this.posterUrl = posterRec
                            this.year = yearRec
                        }
                        else -> newMovieSearchResponse(cleanTitleRec, fixUrl(href), TvType.Movie) {
                            this.posterUrl = posterRec
                            this.year = yearRec
                        }
                    }
                } catch (e: Exception) { null }
            }
        println("Recomendações: ${siteRecommendations.size}")

        return if (isAnime || isSerie) {
            val type = if (isAnime) TvType.Anime else TvType.TvSeries
            println("Carregando como Série/Anime, tipo: $type")
            val episodes = extractEpisodesFromSite(document, url, isAnime, isSerie)
            println("Episódios encontrados: ${episodes.size}")

            newTvSeriesLoadResponse(cleanTitle, url, type, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.year = year
                this.plot = synopsis
                this.tags = tags
                this.recommendations = siteRecommendations.takeIf { it.isNotEmpty() }
                this.score = score

                if (cast != null && cast.isNotEmpty()) {
                    addActors(cast)
                }

                if (trailerKey != null) {
                    addTrailer("https://www.youtube.com/watch?v=$trailerKey")
                }
            }
        } else {
            val playerUrl = findPlayerUrl(document)
            println("Carregando como Filme, player URL: $playerUrl")

            newMovieLoadResponse(cleanTitle, url, TvType.Movie, playerUrl ?: url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.year = year
                this.plot = synopsis
                this.tags = tags
                this.duration = duration
                this.recommendations = siteRecommendations.takeIf { it.isNotEmpty() }
                this.score = score

                if (cast != null && cast.isNotEmpty()) {
                    addActors(cast)
                }

                if (trailerKey != null) {
                    addTrailer("https://www.youtube.com/watch?v=$trailerKey")
                }
            }
        }
    }

    private suspend fun extractEpisodesFromSite(
        document: org.jsoup.nodes.Document,
        url: String,
        isAnime: Boolean,
        isSerie: Boolean
    ): List<Episode> {
        println("  >>> extractEpisodesFromSite INICIADO")
        println("  URL: $url, isAnime: $isAnime, isSerie: $isSerie")
        
        val episodes = mutableListOf<Episode>()

        val scriptData = document.selectFirst("script:containsData(window.allEpisodes)")?.data()
        if (scriptData != null) {
            println("  Script com allEpisodes encontrado")
            try {
                val jsonMatch = Regex("window\\.allEpisodes\\s*=\\s*(\\{[^;]+\\})").find(scriptData)
                val jsonString = jsonMatch?.groupValues?.get(1)
                
                if (jsonString != null) {
                    println("  JSON encontrado, tamanho: ${jsonString.length}")
                    val seasonPattern = Regex("\"(\\d+)\":\\s*\\[([^\\]]+)\\]")
                    val seasonMatches = seasonPattern.findAll(jsonString)
                    
                    var episodeCount = 0
                    for (seasonMatch in seasonMatches) {
                        val seasonNum = seasonMatch.groupValues[1].toIntOrNull() ?: 1
                        val episodesJson = seasonMatch.groupValues[2]
                        
                        val episodePattern = Regex("\\{[^}]*\"epi_num\"\\s*:\\s*(\\d+)[^}]*\"title\"\\s*:\\s*\"([^\"]*)\"[^}]*\"thumb_url\"\\s*:\\s*\"([^\"]*)\"[^}]*\"duration\"\\s*:\\s*(\\d+)[^}]*\"air_date\"\\s*:\\s*\"([^\"]*)\"[^}]*\\}")
                        val episodeMatches = episodePattern.findAll(episodesJson)
                        
                        for (epMatch in episodeMatches) {
                            val epNum = epMatch.groupValues[1].toIntOrNull() ?: continue
                            val epTitle = epMatch.groupValues[2].ifEmpty { "Episódio $epNum" }
                            val thumbUrl = epMatch.groupValues[3].takeIf { it.isNotEmpty() }?.let { fixUrl(it) }
                            val durationMin = epMatch.groupValues[4].toIntOrNull()
                            val airDate = epMatch.groupValues[5].takeIf { it.isNotEmpty() }
                            
                            val episodeUrl = "$url/$seasonNum/$epNum"
                            
                            episodes.add(newEpisode(fixUrl(episodeUrl)) {
                                this.name = epTitle
                                this.season = seasonNum
                                this.episode = epNum
                                this.posterUrl = thumbUrl
                                this.description = buildString {
                                    if (durationMin != null && durationMin > 0) append("Duração: ${durationMin}min\n")
                                    if (airDate != null) append("Data: $airDate")
                                }.trim()
                            })
                            episodeCount++
                        }
                    }
                    
                    if (episodes.isNotEmpty()) {
                        println("  Extraídos $episodeCount episódios do JSON")
                        return episodes
                    }
                }
            } catch (e: Exception) {
                println("  ERRO ao processar JSON: ${e.message}")
            }
        }

        val episodeElements = document.select("#episodes-list article, .episode-card, .episode-item")
        println("  Elementos de episódio encontrados: ${episodeElements.size}")
        
        episodeElements.forEachIndexed { index, element ->
            try {
                val link = element.selectFirst("a[href]") ?: return@forEachIndexed
                val dataUrl = link.attr("href")
                if (dataUrl.isBlank()) return@forEachIndexed

                val seasonText = element.selectFirst(".text-lead.shrink-0")?.text() ?: "T1:E${index + 1}"
                val seasonMatch = Regex("T(\\d+):E(\\d+)").find(seasonText)
                val seasonNumber = seasonMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val epNumber = seasonMatch?.groupValues?.get(2)?.toIntOrNull() ?: (index + 1)
                
                val epTitle = element.selectFirst("h2, .truncate")?.text() ?: "Episódio $epNumber"
                val thumb = element.selectFirst("img")?.let { img ->
                    var src = img.attr("data-src")
                    if (src.isNullOrBlank()) src = img.attr("src")
                    if (!src.isNullOrBlank()) fixUrl(src) else null
                }
                val description = element.selectFirst(".line-clamp-2.text-xs")?.text()

                episodes.add(newEpisode(fixUrl(dataUrl)) {
                    this.name = epTitle
                    this.season = seasonNumber
                    this.episode = epNumber
                    this.posterUrl = thumb
                    this.description = description
                })
            } catch (e: Exception) {
                println("  ERRO ao processar elemento de episódio: ${e.message}")
            }
        }
        
        println("  Total de episódios extraídos: ${episodes.size}")
        return episodes
    }

    private fun findPlayerUrl(document: org.jsoup.nodes.Document): String? {
        val iframe = document.selectFirst("iframe[src*='player'], iframe[src*='embed'], iframe[src*='fembed'], iframe[src*='filemoon']")
        if (iframe != null) {
            val url = iframe.attr("src")
            println("Player URL encontrada: $url")
            return url
        }

        val videoLink = document.selectFirst("a[href*='.m3u8'], a[href*='.mp4'], a[href*='watch']")
        val url = videoLink?.attr("href")
        println("Video link encontrado: $url")
        return url
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("=== loadLinks INICIADO ===")
        println("Data: $data")
        
        return try {
            val document = app.get(data).document
            println("Documento carregado")
            
            val iframe = document.selectFirst("iframe[src*='player'], iframe[src*='embed'], iframe[src*='fembed'], iframe[src*='filemoon']")
            if (iframe != null) {
                val playerUrl = iframe.attr("src")
                println("Iframe encontrado: $playerUrl")
                
                val links = M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = playerUrl,
                    referer = playerUrl
                )
                
                if (links.isNotEmpty()) {
                    println("Links M3U8 gerados: ${links.size}")
                    links.forEach { callback(it) }
                    true
                } else {
                    println("Nenhum link M3U8, usando URL direta")
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = playerUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = playerUrl
                            this.quality = 720
                        }
                    )
                    true
                }
            } else {
                val videoUrl = document.selectFirst("video source, source[src]")?.attr("src")
                if (videoUrl != null) {
                    println("Video URL encontrada: $videoUrl")
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = videoUrl,
                            type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else null
                        ) {
                            this.referer = data
                            this.quality = 720
                        }
                    )
                    true
                } else {
                    println("Nenhum iframe ou video encontrado")
                    false
                }
            }
        } catch (e: Exception) {
            println("ERRO em loadLinks: ${e.message}")
            e.printStackTrace()
            false
        }
    }
}
