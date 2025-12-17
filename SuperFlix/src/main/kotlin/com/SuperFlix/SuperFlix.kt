package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Element
import com.fasterxml.jackson.annotation.JsonProperty
import java.text.SimpleDateFormat
import java.net.URLEncoder
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

class SuperFlix : MainAPI() {
    override var mainUrl = "https://superflix21.lol"
    override var name = "SuperFlix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override val usesWebView = true

    // ============ TMDB COM BuildConfig ============
    private val tmdbApiKey = BuildConfig.TMDB_API_KEY
    private val tmdbAccessToken = BuildConfig.TMDB_ACCESS_TOKEN
    private val tmdbBaseUrl = "https://api.themoviedb.org/3"
    private val tmdbImageUrl = "https://image.tmdb.org/t/p"

    // ============ FUNÇÕES TMDB ATUALIZADAS ============

    private suspend fun searchOnTMDB(query: String, year: Int?, isTv: Boolean): TMDBInfo? {
        println("🔍 [TMDB DEBUG] Iniciando busca no TMDB (BuildConfig)")
        println("🔍 [TMDB DEBUG] Query: $query")
        println("🔍 [TMDB DEBUG] API Key configurada: ${tmdbApiKey != "dummy_api_key"}")
        println("🔍 [TMDB DEBUG] Access Token configurado: ${tmdbAccessToken != "dummy_access_token"}")

        // Verificar se as chaves estão configuradas
        if (tmdbApiKey == "dummy_api_key" || tmdbAccessToken == "dummy_access_token") {
            println("⚠️ [TMDB DEBUG] Chaves não configuradas - usando proxy como fallback")
            return searchOnTMDBViaProxy(query, year, isTv)
        }

        return try {
            val type = if (isTv) "tv" else "movie"
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            
            // URL de busca DIRETA com API Key
            var searchUrl = "$tmdbBaseUrl/search/$type?query=$encodedQuery&api_key=$tmdbApiKey&language=pt-BR"
            if (year != null) searchUrl += "&year=$year"
            
            println("🔗 [TMDB DEBUG] URL direta: ${searchUrl.take(100)}...")

            val response = app.get(searchUrl, timeout = 10_000)
            println("📡 [TMDB DEBUG] Status da resposta direta: ${response.code}")

            if (response.code != 200) {
                println("❌ [TMDB DEBUG] Erro na busca direta, tentando proxy...")
                return searchOnTMDBViaProxy(query, year, isTv)
            }

            val searchResult = response.parsedSafe<TMDBSearchResponse>() ?: return null
            println("✅ [TMDB DEBUG] Parsing OK! Resultados: ${searchResult.results.size}")

            val result = searchResult.results.firstOrNull() ?: return null

            // Buscar detalhes completos com Access Token
            val details = getTMDBDetailsDirect(result.id, isTv) ?: return null

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
                getTMDBAllSeasonsDirect(result.id)
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
            println("❌ [TMDB DEBUG] ERRO na busca direta TMDB: ${e.message}")
            searchOnTMDBViaProxy(query, year, isTv)
        }
    }

    // Função de fallback para proxy (mantém compatibilidade)
    private suspend fun searchOnTMDBViaProxy(query: String, year: Int?, isTv: Boolean): TMDBInfo? {
        println("🔍 [TMDB DEBUG] Usando proxy como fallback")
        val TMDB_PROXY_URL = "https://lawliet.euluan1912.workers.dev"
        
        return try {
            val type = if (isTv) "tv" else "movie"
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val yearParam = year?.let { "&year=$it" } ?: ""

            val searchUrl = "$TMDB_PROXY_URL/search?query=$encodedQuery&type=$type$yearParam"
            println("🔗 [TMDB DEBUG] URL proxy: $searchUrl")

            val response = app.get(searchUrl, timeout = 10_000)
            println("📡 [TMDB DEBUG] Status proxy: ${response.code}")

            if (response.code != 200) return null

            val searchResult = response.parsedSafe<TMDBSearchResponse>() ?: return null
            println("✅ [TMDB DEBUG] Parsing proxy OK!")

            val result = searchResult.results.firstOrNull() ?: return null

            // Buscar detalhes completos via proxy
            val details = getTMDBDetailsViaProxy(result.id, isTv) ?: return null

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
                getTMDBAllSeasonsViaProxy(result.id)
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
            println("❌ [TMDB DEBUG] ERRO no proxy: ${e.message}")
            null
        }
    }

    private suspend fun getTMDBDetailsDirect(id: Int, isTv: Boolean): TMDBDetailsResponse? {
        println("🔍 [TMDB DEBUG] Buscando detalhes DIRETOS para ID $id")
        
        return try {
            val type = if (isTv) "tv" else "movie"
            // Usar Access Token para detalhes
            val url = "$tmdbBaseUrl/$type/$id?append_to_response=credits,videos&language=pt-BR"
            
            val headers = mapOf(
                "Authorization" to "Bearer $tmdbAccessToken",
                "accept" to "application/json"
            )
            
            println("🔗 [TMDB DEBUG] URL detalhes diretos: $url")
            
            val response = app.get(url, headers = headers, timeout = 10_000)
            println("📡 [TMDB DEBUG] Status detalhes diretos: ${response.code}")

            if (response.code != 200) {
                println("❌ [TMDB DEBUG] Erro detalhes diretos: ${response.code}")
                return null
            }

            response.parsedSafe<TMDBDetailsResponse>()
        } catch (e: Exception) {
            println("❌ [TMDB DEBUG] ERRO detalhes diretos: ${e.message}")
            null
        }
    }

    private suspend fun getTMDBAllSeasonsDirect(seriesId: Int): Map<Int, List<TMDBEpisode>> {
        println("🔍 [TMDB DEBUG] Buscando temporadas DIRETAS para série ID: $seriesId")
        
        val seasonsEpisodes = mutableMapOf<Int, List<TMDBEpisode>>()
        
        try {
            // Headers com Access Token
            val headers = mapOf(
                "Authorization" to "Bearer $tmdbAccessToken",
                "accept" to "application/json"
            )
            
            // Primeiro pegar detalhes da série
            val seriesUrl = "$tmdbBaseUrl/tv/$seriesId?language=pt-BR"
            println("🔗 [TMDB DEBUG] URL série direta: $seriesUrl")
            
            val seriesResponse = app.get(seriesUrl, headers = headers, timeout = 10_000)
            println("📡 [TMDB DEBUG] Status série: ${seriesResponse.code}")

            if (seriesResponse.code == 200) {
                val seriesDetails = seriesResponse.parsedSafe<TMDBTVDetailsResponse>()
                seriesDetails?.seasons?.forEach { season ->
                    if (season.season_number > 0) {
                        val seasonNumber = season.season_number
                        println("🔍 [TMDB DEBUG] Buscando temporada direta $seasonNumber...")

                        val seasonUrl = "$tmdbBaseUrl/tv/$seriesId/season/$seasonNumber?language=pt-BR"
                        val seasonResponse = app.get(seasonUrl, headers = headers, timeout = 10_000)
                        
                        if (seasonResponse.code == 200) {
                            val seasonData = seasonResponse.parsedSafe<TMDBSeasonResponse>()
                            seasonData?.episodes?.let { episodes ->
                                seasonsEpisodes[seasonNumber] = episodes
                                println("✅ [TMDB DEBUG] Temporada direta $seasonNumber: ${episodes.size} episódios")
                            }
                        } else {
                            println("❌ [TMDB DEBUG] Falha temporada direta $seasonNumber")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("❌ [TMDB DEBUG] ERRO temporadas diretas: ${e.message}")
        }
        
        return seasonsEpisodes
    }

    // ============ FUNÇÕES PROXY (MANTIDAS PARA COMPATIBILIDADE) ============

    private suspend fun getTMDBDetailsViaProxy(id: Int, isTv: Boolean): TMDBDetailsResponse? {
        val TMDB_PROXY_URL = "https://lawliet.euluan1912.workers.dev"
        
        println("🔍 [TMDB DEBUG] Buscando detalhes via proxy para ID $id")

        return try {
            val type = if (isTv) "tv" else "movie"
            val url = "$TMDB_PROXY_URL/$type/$id"
            println("🔗 [TMDB DEBUG] URL detalhes proxy: $url")

            val response = app.get(url, timeout = 10_000)
            println("📡 [TMDB DEBUG] Status proxy: ${response.code}")

            if (response.code != 200) return null

            response.parsedSafe<TMDBDetailsResponse>()
        } catch (e: Exception) {
            println("❌ [TMDB DEBUG] ERRO detalhes proxy: ${e.message}")
            null
        }
    }

    private suspend fun getTMDBAllSeasonsViaProxy(seriesId: Int): Map<Int, List<TMDBEpisode>> {
        val TMDB_PROXY_URL = "https://lawliet.euluan1912.workers.dev"
        
        println("🔍 [TMDB DEBUG] Buscando temporadas via proxy para série ID: $seriesId")

        return try {
            // Primeiro, pegar detalhes da série
            val seriesDetailsUrl = "$TMDB_PROXY_URL/tv/$seriesId"
            println("🔗 [TMDB DEBUG] URL série proxy: $seriesDetailsUrl")

            val seriesResponse = app.get(seriesDetailsUrl, timeout = 10_000)
            println("📡 [TMDB DEBUG] Status série proxy: ${seriesResponse.code}")

            if (seriesResponse.code != 200) {
                println("❌ [TMDB DEBUG] Erro HTTP série proxy: ${seriesResponse.code}")
                return emptyMap()
            }

            val seriesDetails = seriesResponse.parsedSafe<TMDBTVDetailsResponse>() ?: return emptyMap()

            println("✅ [TMDB DEBUG] Série proxy OK! Total temporadas: ${seriesDetails.seasons.size}")

            val seasonsEpisodes = mutableMapOf<Int, List<TMDBEpisode>>()

            // Agora buscar cada temporada individualmente
            for (season in seriesDetails.seasons) {
                if (season.season_number > 0) {
                    val seasonNumber = season.season_number
                    println("🔍 [TMDB DEBUG] Buscando temporada proxy $seasonNumber...")

                    val seasonUrl = "$TMDB_PROXY_URL/tv/$seriesId/season/$seasonNumber"
                    println("🔗 [TMDB DEBUG] URL temporada proxy: $seasonUrl")

                    val seasonResponse = app.get(seasonUrl, timeout = 10_000)
                    println("📡 [TMDB DEBUG] Status temporada proxy: ${seasonResponse.code}")

                    if (seasonResponse.code == 200) {
                        val seasonData = seasonResponse.parsedSafe<TMDBSeasonResponse>()
                        seasonData?.episodes?.let { episodes ->
                            seasonsEpisodes[seasonNumber] = episodes
                            println("✅ [TMDB DEBUG] Temporada proxy $seasonNumber: ${episodes.size} episódios")
                        }
                    } else {
                        println("❌ [TMDB DEBUG] Falha temporada proxy $seasonNumber")
                    }
                }
            }

            println("✅ [TMDB DEBUG] Total temporadas proxy com dados: ${seasonsEpisodes.size}")
            seasonsEpisodes
        } catch (e: Exception) {
            println("❌ [TMDB DEBUG] ERRO temporadas proxy: ${e.message}")
            emptyMap()
        }
    }

    // ============ RESTO DO CÓDIGO PERMANECE EXATAMENTE IGUAL ============
    
    // ... [TODAS AS OUTRAS FUNÇÕES PERMANECEM EXATAMENTE IGUAIS] ...
    // extractRecommendationsFromSite, createLoadResponseFromSite, createLoadResponseWithTMDB, etc.
    
    // SÓ COPIE DAQUI PARA BAIXO DO SEU CÓDIGO ATUAL
    
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

        println("🔍 [DEBUG] Buscando no TMDB...")
        val tmdbInfo = if (isAnime || isSerie) {
            searchOnTMDB(cleanTitle, year, true)
        } else {
            searchOnTMDB(cleanTitle, year, false)
        }

        if (tmdbInfo == null) {
            println("⚠️ [DEBUG] TMDB não retornou informações!")
        } else {
            println("✅ [DEBUG] TMDB OK! Título: ${tmdbInfo.title}, Ano: ${tmdbInfo.year}")
            println("✅ [DEBUG] Poster URL: ${tmdbInfo.posterUrl}")
            println("✅ [DEBUG] Backdrop URL: ${tmdbInfo.backdropUrl}")
            println("✅ [DEBUG] Overview: ${tmdbInfo.overview?.take(50)}...")
            println("✅ [DEBUG] Atores: ${tmdbInfo.actors?.size ?: 0}")
            println("✅ [DEBUG] Trailer: ${tmdbInfo.youtubeTrailer}")
            println("✅ [DEBUG] Temporadas/Episódios TMDB: ${tmdbInfo.seasonsEpisodes.size}")
        }

        val siteRecommendations = extractRecommendationsFromSite(document)

        return if (tmdbInfo != null) {
            println("✅ [DEBUG] Criando resposta COM dados do TMDB")
            createLoadResponseWithTMDB(tmdbInfo, url, document, isAnime, isSerie, siteRecommendations)
        } else {
            println("⚠️ [DEBUG] Criando resposta APENAS com dados do site")
            createLoadResponseFromSite(document, url, cleanTitle, year, isAnime, isSerie)
        }
    }

    // ... [CONTINUE COM TODO O RESTO DO SEU CÓDIGO ATUAL] ...
    // NÃO MODIFIQUE NADA DEPOIS DESTE PONTO
    // SÓ AS FUNÇÕES TMDB ACIMA FORAM MODIFICADAS
}
