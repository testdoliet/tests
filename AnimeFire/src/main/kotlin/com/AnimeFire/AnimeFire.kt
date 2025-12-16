package com.AnimeFire

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.text.SimpleDateFormat
import kotlinx.coroutines.delay

class AnimeFire : MainAPI() {
    // URL correta do site
    override var mainUrl = "https://animefire.io"
    override var name = "AnimeFire"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie)
    override val usesWebView = true

    // ============ CONSTANTES ============
    companion object {
        private const val SEARCH_PATH = "/pesquisar"
        private const val MAX_TRIES = 3
        private const val RETRY_DELAY = 1000L
        
        // Proxy TMDB (igual ao seu código)
        private const val TMDB_PROXY_URL = "https://lawliet.euluan1912.workers.dev"
        private const val tmdbImageUrl = "https://image.tmdb.org/t/p"
    }

    // 4 ABAS DA PÁGINA INICIAL
    override val mainPage = mainPageOf(
        "$mainUrl" to "Lançamentos",
        "$mainUrl" to "Destaques da Semana",
        "$mainUrl" to "Últimos Animes Adicionados",
        "$mainUrl" to "Últimos Episódios Adicionados"
    )

    // ============ FUNÇÃO AUXILIAR PARA SEARCH RESPONSE ============
    private fun Element.toSearchResponse(): AnimeSearchResponse? {
        val href = attr("href") ?: return null
        if (href.isBlank()) return null
        
        val titleElement = selectFirst("h3.animeTitle") ?: return null
        val title = titleElement.text().trim()
        
        // Extrair imagem
        val imgElement = selectFirst("img.imgAnimes, img.owl-lazy, img[src*='animes']")
        val poster = when {
            imgElement?.hasAttr("data-src") == true -> imgElement.attr("data-src")
            imgElement?.hasAttr("src") == true -> imgElement.attr("src")
            else -> selectFirst("img:not([src*='logo']):not([src*='Logo'])")?.attr("src")
        } ?: return null
        
        // Filtrar logo do site
        if (poster.contains("logo", ignoreCase = true)) return null
        
        // Limpar título
        val cleanTitle = title.replace(Regex("(?i)(dublado|legendado|todos os episódios|\\(\\d{4}\\))$"), "").trim()
        
        // Verificar se é filme
        val isMovie = href.contains("/filmes/") || title.contains("Movie", ignoreCase = true)
        
        return newAnimeSearchResponse(cleanTitle, fixUrl(href)) {
            this.posterUrl = fixUrl(poster)
            this.type = if (isMovie) TvType.Movie else TvType.Anime
        }
    }

    // ============ FUNÇÕES PARA EXTRACTION ============
    private fun extractFromCarousel(document: org.jsoup.nodes.Document, selector: String): List<AnimeSearchResponse> {
        return document.select(selector).mapNotNull { it.toSearchResponse() }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        
        val homeItems = when (request.name) {
            "Lançamentos" -> 
                extractFromCarousel(document, ".owl-carousel-home .divArticleLancamentos a.item")
            "Destaques da Semana" -> 
                extractFromCarousel(document, ".owl-carousel-semana .divArticleLancamentos a.item")
            "Últimos Animes Adicionados" -> 
                extractFromCarousel(document, ".owl-carousel-l_dia .divArticleLancamentos a.item")
            "Últimos Episódios Adicionados" -> {
                document.select(".divCardUltimosEpsHome").mapNotNull { card ->
                    val link = card.selectFirst("article.card a") ?: return@mapNotNull null
                    val href = link.attr("href") ?: return@mapNotNull null
                    
                    val titleElement = card.selectFirst("h3.animeTitle") ?: return@mapNotNull null
                    val title = titleElement.text().trim()
                    
                    val epNumber = card.selectFirst(".numEp")?.text()?.toIntOrNull() ?: 1
                    
                    val imgElement = card.selectFirst("img.imgAnimesUltimosEps, img[src*='animes']")
                    val poster = when {
                        imgElement?.hasAttr("data-src") == true -> imgElement.attr("data-src")
                        imgElement?.hasAttr("src") == true -> imgElement.attr("src")
                        else -> card.selectFirst("img:not([src*='logo'])")?.attr("src")
                    } ?: return@mapNotNull null
                    
                    val cleanTitle = "${title} - Episódio $epNumber"
                    
                    newAnimeSearchResponse(cleanTitle, fixUrl(href)) {
                        this.posterUrl = fixUrl(poster)
                        this.type = TvType.Anime
                    }
                }
            }
            else -> emptyList()
        }
        
        return newHomePageResponse(request.name, homeItems.distinctBy { it.url }, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl$SEARCH_PATH/${URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(searchUrl).document

        return document.select("article.containerAnimes a.item")
            .mapNotNull { it.toSearchResponse() }
            .take(30)
    }

    override suspend fun load(url: String): LoadResponse {
        println("\n" + "=".repeat(80))
        println("🚀 AnimeFire.load() para URL: $url")
        println("=".repeat(80))
        
        val document = app.get(url).document

        // Título
        val titleElement = document.selectFirst("h1.quicksand400, .main_div_anime_info h1, h1") ?: 
            throw ErrorLoadingException("Não foi possível encontrar o título")
        val rawTitle = titleElement.text().trim()
        
        // Limpar título
        val year = Regex("\\((\\d{4})\\)").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
        val cleanTitle = rawTitle.replace(Regex("\\(\\d{4}\\)"), "").trim()
        
        // Determinar tipo
        val isMovie = url.contains("/filmes/") || rawTitle.contains("Movie", ignoreCase = true)
        val type = if (isMovie) TvType.Movie else TvType.Anime
        
        println("📌 Título: $cleanTitle")
        println("📌 Ano: $year")
        println("📌 Tipo: ${if (isMovie) "Movie" else "Anime"}")

        // 1. BUSCAR MAL ID PELO NOME DO ANIME (para AniZip)
        println("\n🔍 Buscando MAL ID para: $cleanTitle")
        val malId = searchMALIdByName(cleanTitle)
        println("📌 MAL ID encontrado: $malId")

        // 2. BUSCAR DADOS DA ANI.ZIP
        var aniZipData: AniZipData? = null
        if (malId != null) {
            println("🔍 Buscando dados da ani.zip para MAL ID: $malId")
            aniZipData = fetchAniZipData(malId)
            if (aniZipData != null) {
                println("✅ Dados obtidos com sucesso!")
                println("   📊 Títulos: ${aniZipData.titles?.size ?: 0}")
                println("   📊 Imagens: ${aniZipData.images?.size ?: 0}")
                println("   📊 Episódios: ${aniZipData.episodes?.size ?: 0}")
            } else {
                println("❌ Não foi possível obter dados da ani.zip")
            }
        } else {
            println("⚠️ Nenhum MAL ID encontrado, pulando ani.zip")
        }

        // 3. BUSCAR NO TMDB (para trailer e detalhes dos episódios)
        println("\n🔍 Buscando no TMDB...")
        val tmdbInfo = searchOnTMDB(cleanTitle, year, !isMovie) // true para séries/animes
        
        if (tmdbInfo == null) {
            println("⚠️ TMDB não retornou informações!")
        } else {
            println("✅ TMDB OK! Título: ${tmdbInfo.title}, Ano: ${tmdbInfo.year}")
            println("✅ Poster URL: ${tmdbInfo.posterUrl}")
            println("✅ Backdrop URL: ${tmdbInfo.backdropUrl}")
            println("✅ Overview: ${tmdbInfo.overview?.take(50)}...")
            println("✅ Atores: ${tmdbInfo.actors?.size ?: 0}")
            println("✅ Trailer: ${tmdbInfo.youtubeTrailer}")
            println("✅ Temporadas/Episódios TMDB: ${tmdbInfo.seasonsEpisodes.size}")
        }

        // 4. EXTRAIR METADADOS DO SITE
        println("\n🔍 Extraindo metadados do site...")
        val siteMetadata = extractSiteMetadata(document)
        
        // 5. EXTRAIR EPISÓDIOS (com dados do TMDB)
        println("\n🔍 Extraindo episódios...")
        val episodes = if (!isMovie) {
            extractEpisodesWithTMDB(document, tmdbInfo)
        } else {
            emptyList()
        }

        // 6. EXTRAIR RECOMENDAÇÕES
        val recommendations = extractRecommendations(document)

        // 7. CRIAR RESPOSTA COM DADOS COMBINADOS
        println("\n🏗️ Criando resposta final...")
        val response = createLoadResponseWithCombinedData(
            url = url,
            cleanTitle = cleanTitle,
            year = year,
            isMovie = isMovie,
            type = type,
            siteMetadata = siteMetadata,
            aniZipData = aniZipData,
            tmdbInfo = tmdbInfo,
            episodes = episodes,
            recommendations = recommendations
        )
        
        println("\n" + "=".repeat(80))
        println("✅ AnimeFire.load() concluído com sucesso!")
        println("=".repeat(80))
        
        return response
    }

    // ============ BUSCA MAL ID ============
    private suspend fun searchMALIdByName(animeName: String): Int? {
        return try {
            // Limpar nome para busca
            val cleanName = animeName
                .replace(Regex("(?i)\\s*-\\s*Todos os Episódios"), "")
                .replace(Regex("(?i)\\s*\\(Dublado\\)"), "")
                .replace(Regex("(?i)\\s*\\(Legendado\\)"), "")
                .trim()
            
            println("🔍 [MAL] Buscando: '$cleanName'")
            
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
                headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json"),
                timeout = 10_000
            )
            
            println("📡 [MAL] Resposta: ${response.code}")
            
            if (response.code == 200) {
                val data = response.parsedSafe<AniListResponse>()
                val malId = data?.data?.Page?.media?.firstOrNull()?.idMal
                println("📌 [MAL] ID encontrado: $malId")
                malId
            } else {
                println("❌ [MAL] Erro HTTP: ${response.code}")
                null
            }
        } catch (e: Exception) {
            println("❌ [MAL] Exception: ${e.message}")
            null
        }
    }

    // ============ BUSCA ANI.ZIP ============
    private suspend fun fetchAniZipData(malId: Int): AniZipData? {
        for (attempt in 1..MAX_TRIES) {
            try {
                println("🔍 [ANIZIP] Tentativa $attempt para MAL ID: $malId")
                
                val response = app.get("https://api.ani.zip/mappings?mal_id=$malId", timeout = 10_000)
                
                println("📡 [ANIZIP] Status: ${response.code}")
                
                if (response.code == 200) {
                    val data = response.parsedSafe<AniZipData>()
                    if (data != null) {
                        println("✅ [ANIZIP] Dados parseados com sucesso!")
                        return data
                    } else {
                        println("❌ [ANIZIP] Falha no parsing JSON")
                    }
                } else if (response.code == 404) {
                    println("❌ [ANIZIP] MAL ID não encontrado")
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

    // ============ METADADOS DO SITE ============
    private data class SiteMetadata(
        val poster: String? = null,
        val plot: String? = null,
        val tags: List<String>? = null,
        val year: Int? = null
    )

    private fun extractSiteMetadata(document: org.jsoup.nodes.Document): SiteMetadata {
        // 1. POSTER
        val posterImg = document.selectFirst(".sub_animepage_img img.transitioning_src")
        val poster = when {
            posterImg?.hasAttr("src") == true -> {
                val src = posterImg.attr("src")
                println("📸 Poster src: $src")
                fixUrl(src)
            }
            posterImg?.hasAttr("data-src") == true -> {
                val dataSrc = posterImg.attr("data-src")
                println("📸 Poster data-src: $dataSrc")
                fixUrl(dataSrc)
            }
            else -> {
                val fallback = document.selectFirst("img[src*='/img/animes/']:not([src*='logo'])")
                    ?.attr("src")
                println("⚠️ Poster fallback: $fallback")
                fallback?.let { fixUrl(it) }
            }
        }

        // 2. SINOPSE
        val plot = document.selectFirst("div.divSinopse span.spanAnimeInfo")
            ?.text()
            ?.trim()
            ?.replace(Regex("^Sinopse:\\s*"), "")
        println("📝 Sinopse extraída: ${plot?.length ?: 0} caracteres")

        // 3. TAGS/GÊNEROS
        val tags = document.select("a.spanAnimeInfo.spanGeneros")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .takeIf { it.isNotEmpty() }?.toList()
        println("🏷️ Tags encontradas: ${tags?.size ?: 0}")

        // 4. ANO
        val year = document.selectFirst("div.animeInfo:contains(Ano:) span.spanAnimeInfo")
            ?.text()
            ?.trim()
            ?.toIntOrNull()
        println("📅 Ano: $year")

        return SiteMetadata(poster, plot, tags, year)
    }

    // ============ TMDB FUNCTIONS (do seu código) ============
    private suspend fun searchOnTMDB(query: String, year: Int?, isTv: Boolean): TMDBInfo? {
        println("🔍 [TMDB] Iniciando busca no TMDB")
        println("🔍 [TMDB] Query: $query")
        println("🔍 [TMDB] Ano: $year")
        println("🔍 [TMDB] Tipo: ${if (isTv) "TV" else "Movie"}")

        return try {
            val type = if (isTv) "tv" else "movie"
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val yearParam = year?.let { "&year=$it" } ?: ""

            val searchUrl = "$TMDB_PROXY_URL/search?query=$encodedQuery&type=$type$yearParam"
            println("🔗 [TMDB] URL da busca: $searchUrl")

            val response = app.get(searchUrl, timeout = 10_000)
            println("📡 [TMDB] Status da resposta: ${response.code}")

            if (response.code != 200) return null

            val searchResult = response.parsedSafe<TMDBSearchResponse>() ?: return null
            println("✅ [TMDB] Parsing OK! Resultados: ${searchResult.results.size}")

            val result = searchResult.results.firstOrNull() ?: return null

            // Buscar detalhes completos
            val details = getTMDBDetails(result.id, isTv) ?: return null

            // Extrair atores
            val allActors = details.credits?.cast?.take(15)?.mapNotNull { actor ->
                if (actor.name.isNotBlank()) {
                    Actor(
                        name = actor.name,
                        image = actor.profile_path?.let { "$tmdbImageUrl/w185$it" }
                    )
                } else null
            }

            // Buscar trailer
            val youtubeTrailer = getHighQualityTrailer(details.videos?.results)

            // Buscar temporadas se for série
            val seasonsEpisodes = if (isTv) {
                println("🔍 [TMDB] Buscando temporadas...")
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
                seasonsEpisodes = seasonsEpisodes
            )
        } catch (e: Exception) {
            println("❌ [TMDB] ERRO na busca do TMDB: ${e.message}")
            null
        }
    }

    private suspend fun getTMDBAllSeasons(seriesId: Int): Map<Int, List<TMDBEpisode>> {
        println("🔍 [TMDB] Buscando todas as temporadas para série ID: $seriesId")

        return try {
            // Primeiro, pegar detalhes da série para saber quantas temporadas
            val seriesDetailsUrl = "$TMDB_PROXY_URL/tv/$seriesId"
            println("🔗 [TMDB] URL detalhes série: $seriesDetailsUrl")

            val seriesResponse = app.get(seriesDetailsUrl, timeout = 10_000)
            println("📡 [TMDB] Status da resposta: ${seriesResponse.code}")

            if (seriesResponse.code != 200) {
                println("❌ [TMDB] Erro HTTP: ${seriesResponse.code}")
                return emptyMap()
            }

            val seriesDetails = seriesResponse.parsedSafe<TMDBTVDetailsResponse>() ?: return emptyMap()

            println("✅ [TMDB] Série OK! Total temporadas: ${seriesDetails.seasons.size}")

            val seasonsEpisodes = mutableMapOf<Int, List<TMDBEpisode>>()

            // Agora buscar cada temporada individualmente
            for (season in seriesDetails.seasons) {
                if (season.season_number > 0) { // Ignorar temporada 0 (especiais)
                    val seasonNumber = season.season_number
                    println("🔍 [TMDB] Buscando temporada $seasonNumber...")

                    val seasonUrl = "$TMDB_PROXY_URL/tv/$seriesId/season/$seasonNumber"
                    println("🔗 [TMDB] URL temporada: $seasonUrl")

                    val seasonResponse = app.get(seasonUrl, timeout = 10_000)
                    println("📡 [TMDB] Status temporada: ${seasonResponse.code}")

                    if (seasonResponse.code == 200) {
                        val seasonData = seasonResponse.parsedSafe<TMDBSeasonResponse>()
                        seasonData?.episodes?.let { episodes ->
                            seasonsEpisodes[seasonNumber] = episodes
                            println("✅ [TMDB] Temporada $seasonNumber: ${episodes.size} episódios")
                        }
                    } else {
                        println("❌ [TMDB] Falha na temporada $seasonNumber")
                    }
                }
            }

            println("✅ [TMDB] Total temporadas com dados: ${seasonsEpisodes.size}")
            seasonsEpisodes
        } catch (e: Exception) {
            println("❌ [TMDB] ERRO ao buscar temporadas: ${e.message}")
            emptyMap()
        }
    }

    private suspend fun getTMDBDetails(id: Int, isTv: Boolean): TMDBDetailsResponse? {
        println("🔍 [TMDB] Buscando detalhes para ID $id")

        return try {
            val type = if (isTv) "tv" else "movie"
            val url = "$TMDB_PROXY_URL/$type/$id"
            println("🔗 [TMDB] URL detalhes: $url")

            val response = app.get(url, timeout = 10_000)
            println("📡 [TMDB] Status: ${response.code}")

            if (response.code != 200) return null

            response.parsedSafe<TMDBDetailsResponse>()
        } catch (e: Exception) {
            println("❌ [TMDB] ERRO detalhes: ${e.message}")
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

    // ============ EPISÓDIOS COM TMDB ============
    private suspend fun extractEpisodesWithTMDB(
        document: org.jsoup.nodes.Document,
        tmdbInfo: TMDBInfo?
    ): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        println("🔍 [EPISODES] Buscando episódios...")
        
        // Tentar múltiplos seletores do AnimeFire
        val selectors = listOf(
            "a.lEp.epT",
            "a.lEp",
            ".divListaEps a",
            "[href*='/video/']",
            "[href*='/episodio/']",
            ".listaEp a"
        )
        
        val episodeElements = selectors.firstNotNullOfOrNull { selector ->
            val elements = document.select(selector)
            if (elements.isNotEmpty()) {
                println("✅ [EPISODES] Seletor '$selector' encontrou ${elements.size} elementos")
                elements
            } else {
                null
            }
        } ?: document.select("a").filter { 
            it.attr("href").contains("video") || it.attr("href").contains("episodio") 
        }
        
        println("📊 [EPISODES] Total encontrados: ${episodeElements.size}")
        
        episodeElements.forEachIndexed { index, element ->
            try {
                val href = element.attr("href")
                if (href.isBlank()) return@forEachIndexed
                
                val text = element.text().trim()
                if (text.isBlank()) return@forEachIndexed
                
                // Extrair número do episódio
                val episodeNumber = extractEpisodeNumber(text)
                val seasonNumber = 1 // Anime geralmente tem só temporada 1
                
                // Buscar dados do TMDB para este episódio
                val tmdbEpisode = findTMDBEpisode(tmdbInfo, seasonNumber, episodeNumber)

                val episode = if (tmdbEpisode != null) {
                    // Episódio com dados do TMDB - ADICIONANDO DURAÇÃO "-min" NA SINOPSE
                    val descriptionWithDuration = buildDescriptionWithDuration(
                        tmdbEpisode.overview,
                        tmdbEpisode.runtime
                    )

                    newEpisode(fixUrl(href)) {
                        this.name = tmdbEpisode.name ?: "Episódio $episodeNumber"
                        this.season = seasonNumber
                        this.episode = episodeNumber
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
                    // Episódio sem dados do TMDB (usar dados do site)
                    val episodeName = element.selectFirst(".ep-name, .title")?.text()?.trim()
                        ?: text.substringAfterLast("-").trim()
                        ?: "Episódio $episodeNumber"

                    newEpisode(fixUrl(href)) {
                        this.name = episodeName
                        this.season = seasonNumber
                        this.episode = episodeNumber
                    }
                }
                
                episodes.add(episode)
                
                if (index < 5) { // Log apenas dos primeiros 5
                    println("   ✅ Ep $episodeNumber: ${episode.name}")
                }
            } catch (e: Exception) {
                println("❌ [EPISODE ERROR] Erro ao extrair episódio ${index + 1}: ${e.message}")
            }
            
            // Delay para não sobrecarregar
            if (index < episodeElements.size - 1) {
                delay(50)
            }
        }
        
        println("\n📊 [EPISODES] Total processados: ${episodes.size}")
        return episodes.sortedBy { it.episode }
    }

    private fun extractEpisodeNumber(text: String): Int {
        val patterns = listOf(
            Regex("Epis[oó]dio\\s*(\\d+)"),
            Regex("Ep\\.?\\s*(\\d+)"),
            Regex("(\\d{1,3})\\s*-"),
            Regex("#(\\d+)"),
            Regex("\\b(\\d{1,4})\\b")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null) {
                val num = match.groupValues[1].toIntOrNull()
                if (num != null) {
                    return num
                }
            }
        }
        
        // Fallback: extrair qualquer número no texto
        val anyNumber = Regex("\\d+").find(text)?.value?.toIntOrNull()
        return anyNumber ?: 1
    }

    private fun findTMDBEpisode(tmdbInfo: TMDBInfo?, season: Int, episode: Int): TMDBEpisode? {
        if (tmdbInfo == null) return null

        val episodes = tmdbInfo.seasonsEpisodes[season]
        if (episodes == null) {
            println("⚠️ [TMDB] Temporada $season não encontrada no TMDB")
            return null
        }

        return episodes.find { it.episode_number == episode }
    }

    // Função para adicionar "-min" no final da sinopse
    private fun buildDescriptionWithDuration(overview: String?, runtime: Int?): String? {
        return when {
            overview != null && runtime != null && runtime > 0 -> {
                // Adiciona "-min" no final da sinopse
                "$overview\n\nDuração: $runtime min"
            }
            overview != null -> {
                // Mantém apenas a sinopse se não houver duração
                overview
            }
            runtime != null && runtime > 0 -> {
                // Se não houver sinopse mas houver duração
                "Duração: $runtime min"
            }
            else -> null
        }
    }

    // ============ CRIAR RESPOSTA COM DADOS COMBINADOS ============
    private suspend fun createLoadResponseWithCombinedData(
        url: String,
        cleanTitle: String,
        year: Int?,
        isMovie: Boolean,
        type: TvType,
        siteMetadata: SiteMetadata,
        aniZipData: AniZipData?,
        tmdbInfo: TMDBInfo?,
        episodes: List<Episode>,
        recommendations: List<SearchResponse>
    ): LoadResponse {
        
        println("\n🏗️ Criando resposta final...")
        
        // DECISÕES FINAIS (prioridade: TMDB > AniZip > Site)
        
        // POSTER: TMDB > AniZip > Site
        val finalPoster = tmdbInfo?.posterUrl ?: 
            aniZipData?.images?.find { it.coverType.equals("Poster", ignoreCase = true) }?.url?.let { fixUrl(it) } ?:
            siteMetadata.poster
        
        // BACKDROP: TMDB > AniZip
        val finalBackdrop = tmdbInfo?.backdropUrl ?: 
            aniZipData?.images?.find { it.coverType.equals("Fanart", ignoreCase = true) }?.url?.let { fixUrl(it) }
        
        // SINOPSE: TMDB > Site > AniZip
        val finalPlot = tmdbInfo?.overview ?: 
            siteMetadata.plot ?:
            aniZipData?.episodes?.values?.firstOrNull()?.overview
        
        // ANO: Site > TMDB > AniZip
        val finalYear = year ?: 
            siteMetadata.year ?:
            tmdbInfo?.year
        
        // TAGS/GÊNEROS: TMDB > Site > AniZip
        val finalTags = tmdbInfo?.genres ?:
            siteMetadata.tags ?: emptyList()
        
        println("📊 [RESPONSE SUMMARY]")
        println("   🖼️  Poster: ${finalPoster ?: "Não encontrado"}")
        println("   🎬 Backdrop: ${finalBackdrop ?: "Não encontrado"}")
        println("   📖 Plot: ${finalPlot?.take(80)}...")
        println("   📅 Ano: $finalYear")
        println("   🏷️  Tags: ${finalTags.take(3).joinToString()}")
        println("   📺 Episódios: ${episodes.size}")
        println("   🎬 Trailer: ${tmdbInfo?.youtubeTrailer ?: "Não encontrado"}")
        println("   🎭 Atores: ${tmdbInfo?.actors?.size ?: 0}")
        
        return if (isMovie) {
            newMovieLoadResponse(cleanTitle, url, type, url) {
                this.year = finalYear
                this.plot = finalPlot
                this.tags = finalTags
                this.posterUrl = finalPoster
                this.backgroundPosterUrl = finalBackdrop
                this.duration = tmdbInfo?.duration
                this.recommendations = recommendations.takeIf { it.isNotEmpty() }
                
                // Adicionar atores do TMDB
                tmdbInfo?.actors?.let { actors ->
                    addActors(actors)
                }
                
                // Adicionar trailer do TMDB
                tmdbInfo?.youtubeTrailer?.let { trailerUrl ->
                    addTrailer(trailerUrl)
                }
            }
        } else {
            newAnimeLoadResponse(cleanTitle, url, type) {
                addEpisodes(DubStatus.Subbed, episodes)
                
                this.year = finalYear
                this.plot = finalPlot
                this.tags = finalTags
                this.posterUrl = finalPoster
                this.backgroundPosterUrl = finalBackdrop
                this.recommendations = recommendations.takeIf { it.isNotEmpty() }
                
                // Adicionar atores do TMDB
                tmdbInfo?.actors?.let { actors ->
                    addActors(actors)
                }
                
                // Adicionar trailer do TMDB
                tmdbInfo?.youtubeTrailer?.let { trailerUrl ->
                    addTrailer(trailerUrl)
                }
            }
        }
    }

    private fun extractRecommendations(document: org.jsoup.nodes.Document): List<SearchResponse> {
        return document.select(".owl-carousel-anime .divArticleLancamentos a.item")
            .mapNotNull { it.toSearchResponse() }
    }

    // ============ LOAD LINKS SIMPLIFICADO ============
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("⚠️ [LOAD LINKS] Ainda não implementado")
        return false
    }

    // ============ CLASSES DE DADOS ============

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListResponse(
        @JsonProperty("data") val data: AniListData? = null
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListData(
        @JsonProperty("Page") val Page: AniListPage? = null
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListPage(
        @JsonProperty("media") val media: List<AniListMedia>? = null
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniListMedia(
        @JsonProperty("idMal") val idMal: Int? = null
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniZipData(
        @JsonProperty("titles") val titles: Map<String, String>? = null,
        @JsonProperty("images") val images: List<AniZipImage>? = null,
        @JsonProperty("episodes") val episodes: Map<String, AniZipEpisode>? = null
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniZipImage(
        @JsonProperty("coverType") val coverType: String? = null,
        @JsonProperty("url") val url: String? = null
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class AniZipEpisode(
        @JsonProperty("episode") val episode: String? = null,
        @JsonProperty("title") val title: Map<String, String>? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("image") val image: String? = null,
        @JsonProperty("runtime") val runtime: Int? = null,
        @JsonProperty("rating") val rating: String? = null,
        @JsonProperty("airDateUtc") val airDateUtc: String? = null
    )

    // ============ CLASSES TMDB (do seu código) ============
    private data class TMDBInfo(
        val id: Int,
        val title: String?,
        val year: Int?,
        val posterUrl: String?,
        val backdropUrl: String?,
        val overview: String?,
        val genres: List<String>?,
        val actors: List<Actor>?,
        val youtubeTrailer: String?,
        val duration: Int?,
        val seasonsEpisodes: Map<Int, List<TMDBEpisode>> = emptyMap()
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class TMDBSearchResponse(
        @JsonProperty("results") val results: List<TMDBResult>
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class TMDBResult(
        @JsonProperty("id") val id: Int,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("release_date") val release_date: String? = null,
        @JsonProperty("first_air_date") val first_air_date: String? = null,
        @JsonProperty("poster_path") val poster_path: String?
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class TMDBTVDetailsResponse(
        @JsonProperty("seasons") val seasons: List<TMDBSeasonInfo>
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class TMDBSeasonInfo(
        @JsonProperty("season_number") val season_number: Int,
        @JsonProperty("episode_count") val episode_count: Int
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class TMDBSeasonResponse(
        @JsonProperty("episodes") val episodes: List<TMDBEpisode>,
        @JsonProperty("air_date") val air_date: String?
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class TMDBEpisode(
        @JsonProperty("episode_number") val episode_number: Int,
        @JsonProperty("name") val name: String,
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("still_path") val still_path: String?,
        @JsonProperty("runtime") val runtime: Int?,
        @JsonProperty("air_date") val air_date: String?
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class TMDBDetailsResponse(
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("backdrop_path") val backdrop_path: String?,
        @JsonProperty("runtime") val runtime: Int?,
        @JsonProperty("genres") val genres: List<TMDBGenre>?,
        @JsonProperty("credits") val credits: TMDBCredits?,
        @JsonProperty("videos") val videos: TMDBVideos?
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class TMDBGenre(
        @JsonProperty("name") val name: String
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class TMDBCredits(
        @JsonProperty("cast") val cast: List<TMDBCast>
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class TMDBCast(
        @JsonProperty("name") val name: String,
        @JsonProperty("character") val character: String?,
        @JsonProperty("profile_path") val profile_path: String?
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class TMDBVideos(
        @JsonProperty("results") val results: List<TMDBVideo>
    )

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class TMDBVideo(
        @JsonProperty("key") val key: String,
        @JsonProperty("site") val site: String,
        @JsonProperty("type") val type: String,
        @JsonProperty("official") val official: Boolean? = false
    )
}
