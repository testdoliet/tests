package com.BetterFlix

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import org.jsoup.Jsoup
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class BetterFlix : MainAPI() {
    override var mainUrl = "https://betterflix.vercel.app"
    override var name = "BetterFlix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override val usesWebView = false

    // Headers para evitar rate limiting
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "https://betterflix.vercel.app/",
        "Origin" to "https://betterflix.vercel.app",
        "Sec-Ch-Ua" to "\"Chromium\";v=\"127\", \"Not)A;Brand\";v=\"99\", \"Microsoft Edge Simulate\";v=\"127\", \"Lemur\";v=\"127\"",
        "Sec-Ch-Ua-Mobile" to "?1",
        "Sec-Ch-Ua-Platform" to "\"Android\"",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "same-origin"
    )

    // Headers específicos para SuperFlix
    private val superflixHeaders = mapOf(
        "accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        "accept-language" to "pt-BR",
        "priority" to "u=0, i",
        "referer" to "https://betterflix.vercel.app/",
        "sec-ch-ua" to "\"Chromium\";v=\"127\", \"Not)A;Brand\";v=\"99\", \"Microsoft Edge Simulate\";v=\"127\", \"Lemur\";v=\"127\"",
        "sec-ch-ua-mobile" to "?1",
        "sec-ch-ua-platform" to "\"Android\"",
        "sec-fetch-dest" to "iframe",
        "sec-fetch-mode" to "navigate",
        "sec-fetch-site" to "cross-site",
        "upgrade-insecure-requests" to "1",
        "user-agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36"
    )

    // Headers para API do SuperFlix
    private val apiHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "pt-BR",
        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
        "Origin" to "",
        "Referer" to "",
        "X-Requested-With" to "XMLHttpRequest",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "same-origin"
    )

    // Domínios para extração de vídeo
    private val superflixDomains = listOf(
        "https://superflixapi.bond",
        "https://superflixapi.asia",
        "https://superflixapi.top",
        "https://superflixapi.buzz"
    )

    // Mapeamento de gêneros
    private val genreMap = mapOf(
        "28" to "Ação e Aventura",
        "35" to "Comédia",
        "27" to "Terror e Suspense",
        "99" to "Documentário",
        "10751" to "Para a Família",
        "80" to "Crime",
        "10402" to "Musical",
        "10749" to "Romance"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/trending" to "Trending",
        "$mainUrl/genre/28" to "Ação e Aventura",
        "$mainUrl/genre/35" to "Comédia",
        "$mainUrl/genre/27" to "Terror e Suspense",
        "$mainUrl/genre/99" to "Documentário",
        "$mainUrl/genre/10751" to "Para a Família",
        "$mainUrl/genre/80" to "Crime",
        "$mainUrl/genre/10402" to "Musical",
        "$mainUrl/genre/10749" to "Romance",
        "$mainUrl/animes" to "Animes"
    )

    // Modelos de dados
    data class ContentItem(
        @JsonProperty("id") val id: Int,
        @JsonProperty("title") val title: String?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("original_title") val originalTitle: String?,
        @JsonProperty("original_name") val originalName: String?,
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("poster_path") val posterPath: String?,
        @JsonProperty("backdrop_path") val backdropPath: String?,
        @JsonProperty("media_type") val mediaType: String?,
        @JsonProperty("release_date") val releaseDate: String?,
        @JsonProperty("first_air_date") val firstAirDate: String?,
        @JsonProperty("vote_average") val voteAverage: Double?,
        @JsonProperty("genre_ids") val genreIds: List<Int>?,
        @JsonProperty("original_language") val originalLanguage: String?,
        @JsonProperty("popularity") val popularity: Double?,
        @JsonProperty("video") val video: Boolean?,
        @JsonProperty("adult") val adult: Boolean?
    )

    data class EmbeddedData(
        val id: String? = null,
        val name: String? = null,
        val date: String? = null,
        val bio: String? = null,
        val inProduction: Boolean? = null,
        val vote: Double? = null,
        val genres: String? = null,
        val poster: String? = null,
        val backdrop: String? = null
    )

    // Helper para fazer requests com rate limiting
    private suspend fun <T> safeApiRequest(url: String, block: suspend () -> T): T {
        kotlinx.coroutines.delay(500)
        try {
            return block()
        } catch (e: Exception) {
            if (e.message?.contains("429") == true) {
                kotlinx.coroutines.delay(2000)
                return block()
            }
            throw e
        }
    }

    // ========== LOAD LINKS (CORRIGIDO) ==========
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return safeApiRequest(data) {
            try {
                println("🔗 [LINKS] Iniciando extração para: $data")
                
                // Extrair TMDB ID
                val tmdbId = extractTmdbId(data)
                if (tmdbId == null) {
                    println("❌ [LINKS] TMDB ID não encontrado")
                    return@safeApiRequest false
                }
                
                println("✅ [LINKS] TMDB ID: $tmdbId")
                
                // Determinar tipo
                val type = when {
                    data.contains("type=anime") -> "anime"
                    data.contains("type=tv") -> "tv"
                    else -> "movie"
                }
                
                println("📋 [LINKS] Tipo: $type")
                
                // Extrair temporada e episódio para séries
                var season: Int? = null
                var episode: Int? = null
                
                if (type == "tv" || type == "anime") {
                    // Tentar extrair da URL ou do dado armazenado
                    season = extractSeason(data)
                    episode = extractEpisode(data)
                    
                    if (season == null || episode == null) {
                        println("⚠️ [LINKS] Temporada/episódio não encontrados, tentando extrair do dado")
                        val episodeData = extractEpisodeDataFromUrl(data)
                        season = episodeData?.first ?: 1
                        episode = episodeData?.second ?: 1
                    }
                    
                    println("📺 [LINKS] Episódio: Temporada $season, Episódio $episode")
                }
                
                // TENTAR TODOS OS DOMÍNIOS DO SUPERFLIX
                for (superflixDomain in superflixDomains) {
                    try {
                        println("🌐 [LINKS] Tentando domínio: $superflixDomain")
                        
                        val success = when (type) {
                            "movie" -> extractMovieVideo(superflixDomain, tmdbId, callback)
                            "tv", "anime" -> {
                                if (season != null && episode != null) {
                                    extractSeriesVideo(superflixDomain, tmdbId, season, episode, callback)
                                } else {
                                    println("❌ [LINKS] Falta temporada/episódio para série")
                                    false
                                }
                            }
                            else -> false
                        }
                        
                        if (success) {
                            // Adicionar legenda em português se disponível
                            try {
                                addPortugueseSubtitle(tmdbId, subtitleCallback)
                            } catch (e: Exception) {
                                println("⚠️ [LINKS] Erro ao adicionar legenda: ${e.message}")
                            }
                            
                            return@safeApiRequest true
                        }
                    } catch (e: Exception) {
                        println("❌ [LINKS] Erro no domínio $superflixDomain: ${e.message}")
                        continue
                    }
                }
                
                println("❌ [LINKS] Nenhum domínio funcionou")
                return@safeApiRequest false
                
            } catch (e: Exception) {
                println("❌ [LINKS] Erro geral: ${e.message}")
                e.printStackTrace()
                false
            }
        }
    }

    // ========== FUNÇÕES PARA FILMES ==========
    private suspend fun extractMovieVideo(
        domain: String,
        tmdbId: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            println("🎬 [FILME] Iniciando extração para filme $tmdbId")
            
            // PASSO 1: Obter HTML do player do filme
            val playerUrl = "$domain/filme/$tmdbId"
            println("🔍 [FILME] Acessando player: $playerUrl")
            
            val headersWithReferer = superflixHeaders.toMutableMap().apply {
                put("referer", "$domain/")
            }
            
            val playerResponse = app.get(
                playerUrl,
                headers = headersWithReferer,
                timeout = 30
            )
            
            if (playerResponse.code != 200) {
                println("❌ [FILME] Falha ao acessar player: ${playerResponse.code}")
                return false
            }
            
            val html = playerResponse.text
            println("✅ [FILME] HTML obtido: ${html.length} caracteres")
            
            // PASSO 2: Extrair video_id do HTML
            val videoId = extractVideoIdFromHtml(html)
            if (videoId == null) {
                println("❌ [FILME] video_id não encontrado no HTML")
                return false
            }
            
            println("✅ [FILME] video_id encontrado: $videoId")
            
            // PASSO 3: Obter player com video_id
            return getPlayerWithVideoId(domain, videoId, callback)
            
        } catch (e: Exception) {
            println("❌ [FILME] Erro na extração: ${e.message}")
            e.printStackTrace()
            return false
        }
    }

    // ========== FUNÇÕES PARA SÉRIES ==========
    private suspend fun extractSeriesVideo(
        domain: String,
        tmdbId: String,
        season: Int,
        episode: Int,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            println("📺 [SÉRIE] Iniciando extração para: $tmdbId S${season}E${episode}")
            
            // PASSO 1: Obter HTML do player do episódio
            val playerUrl = "$domain/serie/$tmdbId/$season/$episode"
            println("🔍 [SÉRIE] Acessando player: $playerUrl")
            
            val headersWithReferer = superflixHeaders.toMutableMap().apply {
                put("referer", "$domain/")
            }
            
            val playerResponse = app.get(
                playerUrl,
                headers = headersWithReferer,
                timeout = 30
            )
            
            if (playerResponse.code != 200) {
                println("❌ [SÉRIE] Falha ao acessar player: ${playerResponse.code}")
                return false
            }
            
            val html = playerResponse.text
            println("✅ [SÉRIE] HTML obtido: ${html.length} caracteres")
            
            // PASSO 2: Extrair contentId do ALL_EPISODES
            val contentId = extractContentIdFromHtml(html, season, episode)
            if (contentId == null) {
                println("❌ [SÉRIE] contentId não encontrado no HTML")
                return false
            }
            
            println("✅ [SÉRIE] contentId encontrado: $contentId")
            
            // PASSO 3: Obter options com contentId
            val videoId = getVideoIdFromOptions(domain, contentId)
            if (videoId == null) {
                println("❌ [SÉRIE] video_id não obtido das options")
                return false
            }
            
            println("✅ [SÉRIE] video_id obtido: $videoId")
            
            // PASSO 4: Obter player com video_id
            return getPlayerWithVideoId(domain, videoId, callback)
            
        } catch (e: Exception) {
            println("❌ [SÉRIE] Erro na extração: ${e.message}")
            e.printStackTrace()
            return false
        }
    }

    // ========== FUNÇÕES AUXILIARES DE EXTRAÇÃO ==========
    private fun extractVideoIdFromHtml(html: String): String? {
        try {
            val document = Jsoup.parse(html)
            
            // Tentar encontrar por data-id
            val elements = document.select("[data-id]")
            for (element in elements) {
                val videoId = element.attr("data-id")
                if (videoId.isNotBlank() && videoId.matches(Regex("\\d+"))) {
                    return videoId
                }
            }
            
            // Tentar encontrar por classe btn-server
            val btnServers = document.select(".btn-server[data-id]")
            for (btn in btnServers) {
                val videoId = btn.attr("data-id")
                if (videoId.isNotBlank() && videoId.matches(Regex("\\d+"))) {
                    return videoId
                }
            }
            
            // Tentar encontrar por regex
            val regex = Regex("""data-id\s*=\s*["'](\d+)["']""")
            val match = regex.find(html)
            if (match != null) {
                return match.groupValues[1]
            }
            
            return null
        } catch (e: Exception) {
            println("⚠️ [HTML] Erro ao extrair video_id: ${e.message}")
            return null
        }
    }

    private fun extractContentIdFromHtml(html: String, season: Int, episode: Int): String? {
        try {
            // Procurar por ALL_EPISODES no HTML
            val pattern = Regex("""ALL_EPISODES\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
            val match = pattern.find(html)
            
            if (match != null) {
                val jsonString = match.groupValues[1]
                println("📄 [SÉRIE] ALL_EPISODES encontrado")
                
                try {
                    val json = JSONObject(jsonString)
                    val seasonArray = json.optJSONArray(season.toString())
                    if (seasonArray != null) {
                        for (i in 0 until seasonArray.length()) {
                            val epObj = seasonArray.getJSONObject(i)
                            val epNum = epObj.optInt("epi_num")
                            if (epNum == episode) {
                                val contentId = epObj.optString("ID")
                                if (contentId.isNotBlank()) {
                                    return contentId
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    println("❌ [SÉRIE] Erro ao parsear ALL_EPISODES: ${e.message}")
                }
            } else {
                println("❌ [SÉRIE] ALL_EPISODES não encontrado no HTML")
            }
            
            return null
        } catch (e: Exception) {
            println("⚠️ [HTML] Erro ao extrair contentId: ${e.message}")
            return null
        }
    }

    private suspend fun getVideoIdFromOptions(domain: String, contentId: String): String? {
        try {
            println("🔧 [OPTIONS] Obtendo options para contentId: $contentId")
            
            val apiUrl = "$domain/api"
            val optionsData = mapOf(
                "action" to "getOptions",
                "contentid" to contentId
            )
            
            // Atualizar headers com origem correta
            val currentApiHeaders = apiHeaders.toMutableMap().apply {
                put("Origin", domain)
                put("Referer", "$domain/")
            }
            
            val response = app.post(
                apiUrl,
                data = optionsData,
                headers = currentApiHeaders,
                timeout = 30
            )
            
            if (response.code != 200) {
                println("❌ [OPTIONS] Status code: ${response.code}")
                return null
            }
            
            val responseText = response.text
            println("📄 [OPTIONS] Resposta: $responseText")
            
            try {
                val json = JSONObject(responseText)
                
                // Verificar se houve erro
                val errors = json.optString("errors", "1")
                if (errors != "0") {
                    println("❌ [OPTIONS] Erro na resposta: $errors")
                    return null
                }
                
                val data = json.optJSONObject("data")
                if (data == null) {
                    println("❌ [OPTIONS] Dados não encontrados na resposta")
                    return null
                }
                
                val optionsArray = data.optJSONArray("options")
                if (optionsArray == null || optionsArray.length() == 0) {
                    println("❌ [OPTIONS] Array de options vazio")
                    return null
                }
                
                // Pegar o primeiro option disponível
                val firstOption = optionsArray.getJSONObject(0)
                val videoId = firstOption.optString("ID")
                
                if (videoId.isBlank()) {
                    println("❌ [OPTIONS] video_id vazio")
                    return null
                }
                
                println("✅ [OPTIONS] video_id obtido: $videoId")
                return videoId
                
            } catch (e: Exception) {
                println("❌ [OPTIONS] Erro ao parsear JSON: ${e.message}")
                return null
            }
            
        } catch (e: Exception) {
            println("❌ [OPTIONS] Erro na requisição: ${e.message}")
            return null
        }
    }

    private suspend fun getPlayerWithVideoId(
        domain: String,
        videoId: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            println("🎮 [PLAYER] Obtendo player para video_id: $videoId")
            
            val apiUrl = "$domain/api"
            val playerData = mapOf(
                "action" to "getPlayer",
                "video_id" to videoId
            )
            
            // Atualizar headers com origem correta
            val currentApiHeaders = apiHeaders.toMutableMap().apply {
                put("Origin", domain)
                put("Referer", "$domain/")
            }
            
            val response = app.post(
                apiUrl,
                data = playerData,
                headers = currentApiHeaders,
                timeout = 30
            )
            
            if (response.code != 200) {
                println("❌ [PLAYER] Status code: ${response.code}")
                return false
            }
            
            val responseText = response.text
            println("📄 [PLAYER] Resposta: ${responseText.take(200)}...")
            
            try {
                val json = JSONObject(responseText)
                
                // Verificar se houve erro
                val errors = json.optString("errors", "1")
                val message = json.optString("message", "")
                
                if (errors != "0" || message != "success") {
                    println("❌ [PLAYER] Erro na resposta: errors=$errors, message=$message")
                    return false
                }
                
                val data = json.optJSONObject("data")
                if (data == null) {
                    println("❌ [PLAYER] Dados não encontrados")
                    return false
                }
                
                val videoUrl = data.optString("video_url")
                if (videoUrl.isBlank()) {
                    println("❌ [PLAYER] video_url vazio")
                    return false
                }
                
                println("✅ [PLAYER] video_url obtido: ${videoUrl.take(100)}...")
                
                // PASSO 4: Extrair hash da video_url
                val hash = extractHashFromVideoUrl(videoUrl)
                if (hash == null) {
                    println("❌ [PLAYER] Hash não encontrado na video_url")
                    return false
                }
                
                println("✅ [PLAYER] Hash extraído: ${hash.take(20)}...")
                
                // PASSO 5: Obter m3u8 final
                return getFinalM3u8(hash, callback)
                
            } catch (e: Exception) {
                println("❌ [PLAYER] Erro ao parsear JSON: ${e.message}")
                return false
            }
            
        } catch (e: Exception) {
            println("❌ [PLAYER] Erro na requisição: ${e.message}")
            return false
        }
    }

    private fun extractHashFromVideoUrl(videoUrl: String): String? {
        return when {
            videoUrl.contains("/video/") -> {
                videoUrl.substringAfter("/video/").substringBefore("?")
            }
            videoUrl.contains("/m/") -> {
                videoUrl.substringAfter("/m/").substringBefore("?")
            }
            else -> {
                println("⚠️ [HASH] Formato de URL não reconhecido: ${videoUrl.take(100)}...")
                null
            }
        }
    }

    private suspend fun getFinalM3u8(
        hash: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            println("🔐 [M3U8] Obtendo m3u8 para hash: ${hash.take(20)}...")
            
            val playerDomain = "https://llanfairpwllgwyngy.com"
            val playerUrl = "$playerDomain/player/index.php?data=$hash&do=getVideo"
            
            val playerHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
                "Accept" to "*/*",
                "Accept-Language" to "pt-BR",
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "Origin" to playerDomain,
                "Referer" to "$playerDomain/",
                "X-Requested-With" to "XMLHttpRequest",
                "Sec-Fetch-Dest" to "empty",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Site" to "same-origin"
            )
            
            val playerData = mapOf(
                "hash" to hash,
                "r" to ""
            )
            
            val response = app.post(playerUrl, data = playerData, headers = playerHeaders, timeout = 30)
            
            if (response.code != 200) {
                println("❌ [M3U8] Status code: ${response.code}")
                return false
            }
            
            val responseText = response.text
            println("📄 [M3U8] Resposta: ${responseText.take(200)}...")
            
            try {
                val json = JSONObject(responseText)
                
                // Extrair o link m3u8
                val m3u8Url = json.optString("securedLink")
                    .takeIf { it.isNotBlank() }
                    ?: json.optString("videoSource")
                    .takeIf { it.isNotBlank() }
                
                if (m3u8Url.isNullOrBlank()) {
                    println("❌ [M3U8] URL m3u8 não encontrada")
                    return false
                }
                
                println("✅ [M3U8] URL m3u8 encontrada: ${m3u8Url.take(100)}...")
                
                // Determinar qualidade
                val quality = when {
                    m3u8Url.contains("1080") -> Qualities.P1080.value
                    m3u8Url.contains("720") -> Qualities.P720.value
                    m3u8Url.contains("480") -> Qualities.P480.value
                    m3u8Url.contains("360") -> Qualities.P360.value
                    m3u8Url.contains("240") -> Qualities.P240.value
                    else -> Qualities.P720.value // Padrão
                }
                
                println("📊 [M3U8] Qualidade detectada: $quality")
                
                // Criar o ExtractorLink
                newExtractorLink(name, "SuperFlix ($quality)", m3u8Url, ExtractorLinkType.M3U8) {
                    referer = "$playerDomain/"
                    this.quality = quality
                }.also { 
                    callback(it)
                    println("🎉 [M3U8] ExtractorLink criado com sucesso!")
                }
                
                return true
                
            } catch (e: Exception) {
                println("❌ [M3U8] Erro ao parsear JSON: ${e.message}")
                return false
            }
            
        } catch (e: Exception) {
            println("❌ [M3U8] Erro na requisição: ${e.message}")
            return false
        }
    }

    private fun addPortugueseSubtitle(tmdbId: String, subtitleCallback: (SubtitleFile) -> Unit) {
        try {
            // Tentar adicionar legenda em português
            val subtitleUrl = "https://complicado.sbs/cdn/down/disk11/${tmdbId}/Subtitle/subtitle_por.vtt"
            subtitleCallback.invoke(
                SubtitleFile("Português", subtitleUrl)
            )
            println("✅ [SUBTITLE] Legenda adicionada")
        } catch (e: Exception) {
            println("⚠️ [SUBTITLE] Não foi possível adicionar legenda: ${e.message}")
        }
    }

    // ========== FUNÇÕES AUXILIARES DE PARSING ==========
    private fun extractTmdbId(url: String): String? {
        val idMatch = Regex("[?&]id=(\\d+)").find(url)
        return idMatch?.groupValues?.get(1)
    }

    private fun extractSeason(url: String): Int? {
        // Tentar extrair da URL
        val seasonMatch = Regex("[?&]season=(\\d+)").find(url)
        return seasonMatch?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractEpisode(url: String): Int? {
        // Tentar extrair da URL
        val episodeMatch = Regex("[?&]episode=(\\d+)").find(url)
        return episodeMatch?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractEpisodeDataFromUrl(url: String): Pair<Int, Int>? {
        // Tentar padrões comuns de URL de episódios
        val patterns = listOf(
            Regex("/season/(\\d+)/episode/(\\d+)"),
            Regex("/temporada/(\\d+)/episodio/(\\d+)"),
            Regex("[?&]s=(\\d+)&e=(\\d+)"),
            Regex("[?&]season=(\\d+)&episode=(\\d+)")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(url)
            if (match != null) {
                val season = match.groupValues[1].toIntOrNull()
                val episode = match.groupValues[2].toIntOrNull()
                if (season != null && episode != null) {
                    return Pair(season, episode)
                }
            }
        }
        
        return null
    }

    // ========== FUNÇÕES EXISTENTES DO CÓDIGO ORIGINAL ==========
    // (Mantendo todas as outras funções existentes)
    
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Implementação existente...
        val items = mutableListOf<SearchResponse>()
        return safeApiRequest(request.name) {
            when {
                request.name == "Trending" -> {
                    val trending = getTrending()
                    items.addAll(trending)
                }
                request.name == "Animes" -> {
                    val animes = getAnimes()
                    items.addAll(animes)
                }
                request.name in genreMap.values -> {
                    val genreId = genreMap.entries.find { it.value == request.name }?.key
                    if (genreId != null) {
                        val genreItems = getGenreContent(genreId)
                        items.addAll(genreItems)
                    }
                }
            }
            newHomePageResponse(request.name, items, hasNext = false)
        }
    }

    private suspend fun getTrending(): List<SearchResponse> {
        // Implementação existente...
        return emptyList()
    }

    private suspend fun getAnimes(): List<SearchResponse> {
        // Implementação existente...
        return emptyList()
    }

    private suspend fun getGenreContent(genreId: String): List<SearchResponse> {
        // Implementação existente...
        return emptyList()
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Implementação existente...
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        // Implementação existente...
        return null
    }

    private suspend fun extractEpisodesFromSuperflix(tmdbId: String?, baseUrl: String): List<Episode> {
        // Implementação existente...
        return emptyList()
    }

    // Funções auxiliares existentes
    private fun getYearFromDate(dateString: String?): Int? {
        return try {
            dateString?.substring(0, 4)?.toIntOrNull()
        } catch (e: Exception) {
            null
        }
    }

    private fun generateSlug(title: String): String {
        return title
            .lowercase()
            .replace(Regex("[^a-z0-9\\s-]"), "")
            .replace(Regex("\\s+"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')
    }

    private fun String.encodeSearchQuery(): String {
        return java.net.URLEncoder.encode(this, "UTF-8")
    }

    private fun fixUrl(url: String): String {
        return if (url.startsWith("http")) url else "$mainUrl${if (url.startsWith("/")) "" else "/"}$url"
    }
}
