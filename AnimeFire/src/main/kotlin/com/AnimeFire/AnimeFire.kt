package com.AnimeFire

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Element
import com.fasterxml.jackson.annotation.JsonProperty
import java.text.SimpleDateFormat
import java.net.URLEncoder
import kotlinx.coroutines.delay
import com.fasterxml.jackson.databind.ObjectMapper

class AnimeFire : MainAPI() {
    // URL correta do site
    override var mainUrl = "https://animefire.io"
    override var name = "AnimeFire"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie)
    override val usesWebView = true

    // ============ API ANI.ZIP (GRATUITA) ============
    private val ANIZIP_API_URL = "https://api.ani.zip"
    private val objectMapper = ObjectMapper()

    // ============ CONSTANTES ============
    companion object {
        private const val SEARCH_PATH = "/pesquisar"
        private const val MAX_TRIES = 3
        private const val RETRY_DELAY = 1000L
    }

    // 4 ABAS DA PÁGINA INICIAL
    override val mainPage = mainPageOf(
        "$mainUrl" to "Lançamentos",
        "$mainUrl" to "Destaques da Semana",
        "$mainUrl" to "Últimos Animes Adicionados",
        "$mainUrl" to "Últimos Episódios Adicionados"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        
        val homeItems = when (request.name) {
            "Lançamentos" -> extractLancamentos(document)
            "Destaques da Semana" -> extractDestaquesSemana(document)
            "Últimos Animes Adicionados" -> extractUltimosAnimes(document)
            "Últimos Episódios Adicionados" -> extractUltimosEpisodios(document)
            else -> emptyList()
        }
        
        return newHomePageResponse(request.name, homeItems.distinctBy { it.url })
    }

    // 1. LANÇAMENTOS
    private fun extractLancamentos(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        
        val titleElement = document.selectFirst("h1.section2:contains(Em lançamento)")
        titleElement?.let { title ->
            val carousel = title.parent()?.nextElementSibling()?.selectFirst(".owl-carousel-home")
            carousel?.select(".divArticleLancamentos a.item")?.forEach { item ->
                item.toSearchResult()?.let { items.add(it) }
            }
        }
        
        return items.take(15).distinctBy { it.url }
    }

    // 2. DESTAQUES DA SEMANA
    private fun extractDestaquesSemana(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        
        val titleElement = document.selectFirst("div.divSection:nth-child(4) > h1.section2:contains(Destaques da semana)")
        titleElement?.let { title ->
            val carousel = title.parent()?.nextElementSibling()?.selectFirst(".owl-carousel-semana")
            carousel?.select(".divArticleLancamentos a.item")?.forEach { item ->
                item.toSearchResult()?.let { items.add(it) }
            }
        }
        
        return items.take(15).distinctBy { it.url }
    }

    // 3. ÚLTIMOS ANIMES ADICIONADOS
    private fun extractUltimosAnimes(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        
        val titleElement = document.selectFirst("div.divSection:nth-child(6) > h1.section2:contains(Últimos animes adicionados)")
        titleElement?.let { title ->
            val carousel = title.parent()?.nextElementSibling()?.selectFirst(".owl-carousel-l_dia")
            carousel?.select(".divArticleLancamentos a.item")?.forEach { item ->
                item.toSearchResult()?.let { items.add(it) }
            }
        }
        
        return items.take(15).distinctBy { it.url }
    }

    // 4. ÚLTIMOS EPISÓDIOS ADICIONADOS
    private fun extractUltimosEpisodios(document: org.jsoup.nodes.Document): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        
        val titleElement = document.selectFirst("div.divSectionUltimosEpsHome:nth-child(3) > h2.section2:contains(Últimos episódios adicionados)")
        titleElement?.let { title ->
            val container = title.parent()?.nextElementSibling()?.selectFirst(".row")
            container?.select(".divCardUltimosEpsHome")?.forEach { card ->
                card.toEpisodeSearchResult()?.let { items.add(it) }
            }
        }
        
        return items.take(20).distinctBy { it.url }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = attr("href") ?: return null
        if (href.isBlank()) return null
        
        val titleElement = selectFirst("h3.animeTitle") ?: return null
        val title = titleElement.text().trim()
        
        // CORREÇÃO: Pegar a imagem correta
        val imgElement = selectFirst("img.imgAnimes, img.owl-lazy, img[src*='animes']")
        val poster = when {
            imgElement?.hasAttr("data-src") == true -> imgElement.attr("data-src")
            imgElement?.hasAttr("src") == true -> imgElement.attr("src")
            else -> selectFirst("img:not([src*='logo']):not([src*='Logo'])")?.attr("src")
        } ?: return null
        
        // Filtrar logo do site
        if (poster.contains("logo", ignoreCase = true)) return null
        
        val cleanTitle = title.replace(Regex("(?i)(dublado|legendado|todos os episódios|\\(\\d{4}\\))$"), "").trim()
        
        val isMovie = href.contains("/filmes/") || title.contains("Movie", ignoreCase = true)
        
        return if (isMovie) {
            newMovieSearchResponse(cleanTitle, fixUrl(href), TvType.Movie) {
                this.posterUrl = fixUrl(poster)
            }
        } else {
            newAnimeSearchResponse(cleanTitle, fixUrl(href), TvType.Anime) {
                this.posterUrl = fixUrl(poster)
            }
        }
    }

    private fun Element.toEpisodeSearchResult(): SearchResponse? {
        val link = selectFirst("article.card a") ?: return null
        val href = link.attr("href") ?: return null
        if (href.isBlank()) return null
        
        val titleElement = selectFirst("h3.animeTitle") ?: return null
        val title = titleElement.text().trim()
        
        val epNumberElement = selectFirst(".numEp")
        val epNumber = epNumberElement?.text()?.toIntOrNull() ?: 1
        
        val imgElement = selectFirst("img.imgAnimesUltimosEps, img.transitioning_src, img[src*='animes']")
        val poster = when {
            imgElement?.hasAttr("data-src") == true -> imgElement.attr("data-src")
            imgElement?.hasAttr("src") == true -> imgElement.attr("src")
            else -> selectFirst("img:not([src*='logo'])")?.attr("src")
        } ?: return null
        
        // Filtrar logo do site
        if (poster.contains("logo", ignoreCase = true)) return null
        
        val cleanTitle = "${title} - Episódio $epNumber"
        
        return newAnimeSearchResponse(cleanTitle, fixUrl(href), TvType.Anime) {
            this.posterUrl = fixUrl(poster)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl$SEARCH_PATH/${URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(searchUrl).document

        return document.select("article.containerAnimes a.item").mapNotNull { element ->
            try {
                element.toSearchResult()
            } catch (e: Exception) {
                null
            }
        }.take(30)
    }

    override suspend fun load(url: String): LoadResponse? {
        println("🔍 [DEBUG] AnimeFire: Iniciando load para URL: $url")

        val document = app.get(url).document

        // Título do site
        val titleElement = document.selectFirst("h1.quicksand400, .main_div_anime_info h1, h1") ?: return null
        val title = titleElement.text().trim()
        
        println("🔍 [DEBUG] AnimeFire: Título encontrado: $title")

        // Extrair ano do título
        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
        println("🔍 [DEBUG] AnimeFire: Título limpo: $cleanTitle | Ano: $year")

        // Determinar se é anime ou filme
        val isAnime = url.contains("/animes/") || !url.contains("/filmes/")
        val isMovie = url.contains("/filmes/") || title.contains("Movie", ignoreCase = true)
        val isTv = isAnime && !isMovie
        
        println("🔍 [DEBUG] AnimeFire: Tipo - Anime: $isAnime, Movie: $isMovie, TV: $isTv")

        // Buscar MAL ID pelo nome do anime
        val malId = if (cleanTitle.contains(" - Episódio")) {
            // É um episódio individual, buscar série principal
            val seriesName = cleanTitle.substringBefore(" - Episódio").trim()
            searchMALIdByName(seriesName)
        } else {
            searchMALIdByName(cleanTitle)
        }
        
        println("🔍 [DEBUG] AnimeFire: MAL ID encontrado: $malId")

        // Buscar dados da ani.zip (se tiver MAL ID)
        val aniZipData = if (malId != null) {
            println("🔍 [DEBUG] AnimeFire: Buscando dados da ani.zip...")
            fetchAniZipData(malId)
        } else {
            println("⚠️ [DEBUG] AnimeFire: SEM MAL ID, pulando ani.zip")
            null
        }

        if (aniZipData == null) {
            println("⚠️ [DEBUG] AnimeFire: ani.zip não retornou informações!")
        } else {
            println("✅ [DEBUG] AnimeFire: ani.zip OK!")
            println("✅ [DEBUG] AnimeFire: Títulos: ${aniZipData.titles?.size}")
            println("✅ [DEBUG] AnimeFire: Imagens: ${aniZipData.images?.size}")
            println("✅ [DEBUG] AnimeFire: Episódios: ${aniZipData.episodes?.size}")
        }

        // Extrair recomendações do site
        val siteRecommendations = extractRecommendationsFromSite(document)

        // Criar resposta com dados combinados
        return createCombinedLoadResponse(
            siteDocument = document,
            aniZipData = aniZipData,
            url = url,
            cleanTitle = cleanTitle,
            year = year,
            isAnime = isAnime,
            isMovie = isMovie,
            siteRecommendations = siteRecommendations
        )
    }

    // ============ FUNÇÕES ANI.ZIP ============

    private suspend fun searchMALIdByName(animeName: String): Int? {
        // Buscar MAL ID usando AniList GraphQL (alternativa gratuita)
        return try {
            val cleanName = animeName
                .replace(Regex("(?i)\\s*-\\s*Todos os Episódios"), "")
                .replace(Regex("(?i)\\s*\\(Dublado\\)"), "")
                .replace(Regex("(?i)\\s*\\(Legendado\\)"), "")
                .trim()
            
            val query = """
                query {
                    Page(page: 1, perPage: 5) {
                        media(search: "$cleanName", type: ANIME) {
                            title { romaji english native }
                            idMal
                        }
                    }
                }
            """.trimIndent()
            
            val response = app.post(
                "https://graphql.anilist.co",
                data = mapOf("query" to query),
                headers = mapOf("Content-Type" to "application/json"),
                timeout = 10_000
            )
            
            if (response.code == 200) {
                val json = response.parsedSafe<AniListResponse>()
                json?.data?.Page?.media?.firstOrNull()?.idMal
            } else {
                null
            }
        } catch (e: Exception) {
            println("❌ [MAL] Erro ao buscar MAL ID: ${e.message}")
            null
        }
    }

    private suspend fun fetchAniZipData(malId: Int): AniZipData? {
        for (attempt in 1..MAX_TRIES) {
            try {
                println("🔍 [ANIZIP] Buscando dados para MAL ID: $malId (tentativa $attempt)")
                
                val response = app.get("$ANIZIP_API_URL/mappings?mal_id=$malId", timeout = 10_000)
                
                println("📡 [ANIZIP] Status: ${response.code}")
                
                if (response.code == 200) {
                    val data = response.parsedSafe<AniZipData>()
                    if (data != null) {
                        println("✅ [ANIZIP] Dados obtidos com sucesso!")
                        return data
                    } else {
                        println("❌ [ANIZIP] Falha no parsing JSON")
                    }
                } else if (response.code == 404) {
                    println("❌ [ANIZIP] MAL ID não encontrado na ani.zip")
                    return null
                } else {
                    println("❌ [ANIZIP] Erro HTTP: ${response.code}")
                }
            } catch (e: Exception) {
                println("❌ [ANIZIP] Exception: ${e.message}")
            }
            
            if (attempt < MAX_TRIES) {
                delay(RETRY_DELAY * attempt)
            }
        }
        
        println("❌ [ANIZIP] Todas as tentativas falharam")
        return null
    }

    private fun extractRecommendationsFromSite(document: org.jsoup.nodes.Document): List<SearchResponse> {
        return document.select(".owl-carousel-anime .divArticleLancamentos a.item").mapNotNull { element ->
            try {
                element.toSearchResult()
            } catch (e: Exception) {
                null
            }
        }
    }

    private suspend fun createCombinedLoadResponse(
    siteDocument: org.jsoup.nodes.Document,
    aniZipData: AniZipData?,
    url: String,
    cleanTitle: String,
    year: Int?,
    isAnime: Boolean,
    isMovie: Boolean,
    siteRecommendations: List<SearchResponse>
): LoadResponse {
    
    println("🏗️ [DEBUG] Criando resposta combinada...")
    
    // ============ 1. POSTER DO SITE (PRIORIDADE) ============
    val posterImg = siteDocument.selectFirst(".sub_animepage_img img.transitioning_src")
    val sitePoster = posterImg?.attr("src") ?: posterImg?.attr("data-src")
    
    val fallbackPoster = if (sitePoster.isNullOrBlank()) {
        siteDocument.selectFirst("img[src*='/img/animes/']:not([src*='logo'])")?.attr("src")
    } else {
        sitePoster
    }
    
    val safePosterUrl = fixUrl(fallbackPoster ?: "https://animefire.io/img/lt/nekog.webp")
    
    println("✅ [POSTER] URL final: $safePosterUrl")
    
    // ============ 2. SINOPSE/PLOT DO SITE ============
    val sitePlot = siteDocument.selectFirst("div.divSinopse span.spanAnimeInfo")?.text()?.trim()
    val cleanPlot = sitePlot?.replace(Regex("^Sinopse:\\s*"), "") ?: ""
    
    // ============ 3. TAGS/GÊNEROS DO SITE ============
    val tags = siteDocument.select("a.spanAnimeInfo.spanGeneros")
        .map { it.text().trim() }
        .filter { it.isNotBlank() }
        .takeIf { it.isNotEmpty() }?.toList() ?: emptyList()
    
    // ============ 4. ANO DO SITE ============
    val siteYear = year ?: siteDocument.selectFirst("div.animeInfo:contains(Ano:) span.spanAnimeInfo")
        ?.text()?.trim()?.toIntOrNull()
    
    // ============ 5. DADOS DA ANI.ZIP (APENAS SUPLEMENTARES) ============
    val aniZipTitle = aniZipData?.titles?.values?.firstOrNull()
    val aniZipPoster = aniZipData?.images?.find { 
        it.coverType.equals("Poster", ignoreCase = true) 
    }?.url
    val aniZipBackdrop = aniZipData?.images?.find { 
        it.coverType.equals("Fanart", ignoreCase = true) 
    }?.url
    val aniZipPlot = aniZipData?.episodes?.values?.firstOrNull()?.overview
    
    // ============ 6. DECISÕES FINAIS ============
    val finalTitle = cleanTitle
    val finalPoster = if (sitePoster.isNullOrBlank()) aniZipPoster ?: safePosterUrl else safePosterUrl
    val finalBackdrop = aniZipBackdrop
    val finalPlot = if (cleanPlot.isNotBlank()) cleanPlot else aniZipPlot ?: ""
    val finalYear = siteYear
    val finalTags = if (tags.isNotEmpty()) tags else emptyList()
    
    println("✅ [RESUMO] Título: $finalTitle")
    println("✅ [RESUMO] Poster: $finalPoster")
    println("✅ [RESUMO] Backdrop: $finalBackdrop")
    println("✅ [RESUMO] Plot: ${finalPlot.take(50)}...")
    println("✅ [RESUMO] Ano: $finalYear")
    println("✅ [RESUMO] Tags: $finalTags")
    
    // ============ 7. EPISÓDIOS ============
    val episodes = if (isAnime && !isMovie) {
        extractEpisodesWithAniZipData(
            siteDocument = siteDocument,
            aniZipData = aniZipData,
            url = url
        )
    } else {
        emptyList()
    }
    
    println("✅ [RESUMO] Total episódios: ${episodes.size}")
    
    // ============ 8. CRIAR RESPOSTA FINAL ============
    return if (isAnime && !isMovie) {
        newTvSeriesLoadResponse(finalTitle, url, TvType.Anime, episodes) {
            this.posterUrl = finalPoster
            this.backgroundPosterUrl = finalBackdrop
            this.year = finalYear
            this.plot = finalPlot
            this.tags = finalTags
            this.recommendations = siteRecommendations.takeIf { it.isNotEmpty() }
            // Removido a linha problemática do rating
        }
    } else {
        val playerUrl = findPlayerUrl(siteDocument) ?: url
        
        newMovieLoadResponse(finalTitle, url, TvType.Movie, fixUrl(playerUrl)) {
            this.posterUrl = finalPoster
            this.backgroundPosterUrl = finalBackdrop
            this.year = finalYear
            this.plot = finalPlot
            this.tags = finalTags
            this.recommendations = siteRecommendations.takeIf { it.isNotEmpty() }
        }
    }
}
            
            
    private suspend fun extractEpisodesWithAniZipData(
        siteDocument: org.jsoup.nodes.Document,
        aniZipData: AniZipData?,
        url: String
    ): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        // CORREÇÃO: Usar o seletor correto dos episódios
        val episodeElements = siteDocument.select("a.lEp.epT, a.lEp")
        
        println("🔍 [EPISODES] Elementos encontrados: ${episodeElements.size}")
        
        episodeElements.forEachIndexed { index, element ->
            try {
                val episodeHref = element.attr("href") ?: return@forEachIndexed
                val episodeText = element.text().trim()
                
                // Extrair número do episódio do texto
                val episodeNumber = extractEpisodeNumber(element, index + 1)
                val seasonNumber = 1
                
                println("🔍 [EPISODES] Processando ep $episodeNumber: $episodeText")
                
                // Verificar se temos dados desse episódio na ani.zip
                val aniZipEpisode = aniZipData?.episodes?.get(episodeNumber.toString())
                
                val episode = if (aniZipEpisode != null) {
                    // Episódio com dados da ani.zip
                    newEpisode(fixUrl(episodeHref)) {
                        this.name = aniZipEpisode.title?.values?.firstOrNull() ?: 
                                   episodeText.replace(Regex(".* - "), "").trim()
                        this.season = seasonNumber
                        this.episode = episodeNumber
                        this.posterUrl = aniZipEpisode.image
                        this.description = aniZipEpisode.overview
                        
                        aniZipEpisode.airDateUtc?.let { airDate ->
                            try {
                                val dateFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
                                val date = dateFormatter.parse(airDate)
                                this.date = date.time
                            } catch (e: Exception) {
                                // Ignorar erro de data
                            }
                        }
                    }
                } else {
                    // Episódio apenas com dados do site
                    newEpisode(fixUrl(episodeHref)) {
                        this.name = episodeText.replace(Regex(".* - "), "").trim()
                        this.season = seasonNumber
                        this.episode = episodeNumber
                    }
                }
                
                episodes.add(episode)
            } catch (e: Exception) {
                println("❌ [EPISODES] Erro no ep ${index + 1}: ${e.message}")
            }
        }
        
        println("✅ [EPISODES] Total processados: ${episodes.size}")
        return episodes.sortedBy { it.episode }.distinctBy { it.episode }
    }

    private fun extractEpisodeNumber(element: Element, default: Int): Int {
        // Tenta extrair do texto do link (ex: "One Piece - Episódio 1")
        val text = element.text()
        
        // Padrões para procurar o número do episódio
        val patterns = listOf(
            Regex("Epis[oó]dio\\s*(\\d+)"),
            Regex("Ep\\.?\\s*(\\d+)"),
            Regex("\\b(\\d{1,4})\\b") // Último recurso: pega o primeiro número no texto
        )
        
        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null) {
                return match.groupValues[1].toIntOrNull() ?: default
            }
        }
        
        return default
    }

    private fun findPlayerUrl(document: org.jsoup.nodes.Document): String? {
        // Buscar player principal
        val playButton = document.selectFirst("button[data-url], a[data-url]")
        if (playButton != null) {
            return playButton.attr("data-url")
        }
        
        // Buscar iframe
        val iframe = document.selectFirst("iframe[src]")
        if (iframe != null) {
            return iframe.attr("src")
        }
        
        // Buscar link direto de vídeo
        val videoLink = document.selectFirst("a[href*='.mp4'], a[href*='.m3u8']")
        return videoLink?.attr("href")
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return AnimeFireExtractor.extractVideoLinks(data, mainUrl, name, callback)
    }

    // ============ CLASSES DE DADOS ============

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListResponse(
        @JsonProperty("data") val data: AniListData?
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListData(
        @JsonProperty("Page") val Page: AniListPage?
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListPage(
        @JsonProperty("media") val media: List<AniListMedia>?
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListMedia(
        @JsonProperty("idMal") val idMal: Int?
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniZipData(
        @JsonProperty("titles") val titles: Map<String, String>? = null,
        @JsonProperty("images") val images: List<AniZipImage>? = null,
        @JsonProperty("episodes") val episodes: Map<String, AniZipEpisode>? = null
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniZipImage(
        @JsonProperty("coverType") val coverType: String?,
        @JsonProperty("url") val url: String?
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniZipEpisode(
        @JsonProperty("episode") val episode: String?,
        @JsonProperty("title") val title: Map<String, String>?,
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("image") val image: String?,
        @JsonProperty("runtime") val runtime: Int?,
        @JsonProperty("rating") val rating: String?,
        @JsonProperty("airDateUtc") val airDateUtc: String?
    )
}
