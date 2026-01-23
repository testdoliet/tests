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

// Cookies persistentes
private val cookies = mapOf(
"dom3ic8zudi28v8lr6fgphwffqoz0j6c" to "33de42d8-3e93-4249-b175-d6bf5346ae91%3A2%3A1",
"pp_main_80d9775bdcedfb8fd29914d950374a08" to "1"
)

// Domínios para extração de vídeo
private val superflixDomains = listOf(
"https://superflixapi.bond",
"https://superflixapi.asia",
"https://superflixapi.top"
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

// Modelos de dados para a API
data class TrendingResponse(
@JsonProperty("results") val results: List<ContentItem>
)

data class GenreResponse(
@JsonProperty("results") val results: List<ContentItem>
)

data class AnimeResponse(
@JsonProperty("results") val results: List<ContentItem>
)

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
// Adicionar delay para evitar rate limiting
kotlinx.coroutines.delay(500)

try {
return block()
} catch (e: Exception) {
if (e.message?.contains("429") == true) {
// Rate limit atingido, esperar mais tempo
kotlinx.coroutines.delay(2000)
return block()
}
throw e
}
}

override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
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
val url = "$mainUrl/api/trending?type=all"
val response = app.get(
url,
headers = headers,
cookies = cookies,
timeout = 30
)

val data = response.parsedSafe<TrendingResponse>() ?: return emptyList()

return data.results.mapNotNull { item ->
try {
val title = item.title ?: item.name ?: item.originalTitle ?: item.originalName ?: return@mapNotNull null
val year = getYearFromDate(item.releaseDate ?: item.firstAirDate)
val poster = item.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
val id = item.id

// Determinar tipo
val type = when (item.mediaType) {
"movie" -> TvType.Movie
"tv" -> TvType.TvSeries
"anime" -> TvType.Anime
else -> when {
title.contains("(Anime)", ignoreCase = true) -> TvType.Anime
item.releaseDate != null -> TvType.Movie
item.firstAirDate != null -> TvType.TvSeries
else -> TvType.Movie
}
}

// Gerar URL no formato correto do site COM TYPE
val slug = generateSlug(title)
val url = when (type) {
TvType.Movie -> "$mainUrl/$slug?id=$id&type=movie"
TvType.TvSeries -> "$mainUrl/$slug?id=$id&type=tv"
TvType.Anime -> "$mainUrl/$slug?id=$id&type=anime"
else -> "$mainUrl/$slug?id=$id&type=movie"
}

when (type) {
TvType.Movie -> newMovieSearchResponse(title, url, TvType.Movie) {
this.posterUrl = poster
this.year = year
}
TvType.TvSeries -> newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
this.posterUrl = poster
this.year = year
}
TvType.Anime -> newAnimeSearchResponse(title, url, TvType.Anime) {
this.posterUrl = poster
this.year = year
}
else -> newMovieSearchResponse(title, url, TvType.Movie) {
this.posterUrl = poster
this.year = year
}
}
} catch (e: Exception) {
null
}
}
}

private suspend fun getAnimes(): List<SearchResponse> {
val url = "$mainUrl/api/list-animes"
val response = app.get(
url,
headers = headers,
cookies = cookies,
timeout = 30
)

val data = response.parsedSafe<AnimeResponse>() ?: return emptyList()

return data.results.mapNotNull { item ->
try {
val title = item.title ?: item.name ?: item.originalTitle ?: item.originalName ?: return@mapNotNull null
val year = getYearFromDate(item.releaseDate ?: item.firstAirDate)
val poster = item.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
val id = item.id

// Gerar URL no formato correto COM TYPE
val slug = generateSlug(title)
val url = "$mainUrl/$slug?id=$id&type=anime"

newAnimeSearchResponse(title, url, TvType.Anime) {
this.posterUrl = poster
this.year = year
}
} catch (e: Exception) {
null
}
}
}

private suspend fun getGenreContent(genreId: String): List<SearchResponse> {
val url = "$mainUrl/api/preview-genre?id=$genreId"
val response = app.get(
url,
headers = headers,
cookies = cookies,
timeout = 30
)

val data = response.parsedSafe<GenreResponse>() ?: return emptyList()

return data.results.mapNotNull { item ->
try {
val title = item.title ?: item.name ?: item.originalTitle ?: item.originalName ?: return@mapNotNull null
val year = getYearFromDate(item.releaseDate ?: item.firstAirDate)
val poster = item.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
val id = item.id

// Determinar tipo
val type = when (item.mediaType) {
"movie" -> TvType.Movie
"tv" -> TvType.TvSeries
else -> when {
title.contains("(Anime)", ignoreCase = true) -> TvType.Anime
item.releaseDate != null -> TvType.Movie
item.firstAirDate != null -> TvType.TvSeries
else -> TvType.Movie
}
}

// Gerar URL no formato correto COM TYPE
val slug = generateSlug(title)
val url = when (type) {
TvType.Movie -> "$mainUrl/$slug?id=$id&type=movie"
TvType.TvSeries -> "$mainUrl/$slug?id=$id&type=tv"
TvType.Anime -> "$mainUrl/$slug?id=$id&type=anime"
else -> "$mainUrl/$slug?id=$id&type=movie"
}

when (type) {
TvType.Movie -> newMovieSearchResponse(title, url, TvType.Movie) {
this.posterUrl = poster
this.year = year
}
TvType.TvSeries -> newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
this.posterUrl = poster
this.year = year
}
TvType.Anime -> newAnimeSearchResponse(title, url, TvType.Anime) {
this.posterUrl = poster
this.year = year
}
else -> newMovieSearchResponse(title, url, TvType.Movie) {
this.posterUrl = poster
this.year = year
}
}
} catch (e: Exception) {
null
}
}
}

private fun getYearFromDate(dateString: String?): Int? {
return try {
dateString?.substring(0, 4)?.toIntOrNull()
} catch (e: Exception) {
null
}
}

// Função para extrair ano do documento
private fun extractYear(document: org.jsoup.nodes.Document): Int? {
// Tenta extrair do título
val title = document.selectFirst("h1, .title")?.text() ?: ""
val yearMatch = Regex("\\((\\d{4})\\)").find(title)
if (yearMatch != null) {
return yearMatch.groupValues[1].toIntOrNull()
}

// Tenta extrair de metadados
document.select("div.bg-gray-800\\/50, .info-grid, .metadata").forEach { div ->
val label = div.selectFirst("p.text-gray-400, .label, .info-label")?.text()
if (label?.contains("Ano") == true || label?.contains("Year") == true) {
val yearText = div.selectFirst("p.text-white, .value, .info-value")?.text()
return yearText?.toIntOrNull()
}
}

return null
}

// Função para gerar slug a partir do título
private fun generateSlug(title: String): String {
return title
.lowercase()
.replace(Regex("[^a-z0-9\\s-]"), "")
.replace(Regex("\\s+"), "-")
.replace(Regex("-+"), "-")
.trim('-')
}

override suspend fun search(query: String): List<SearchResponse> {
return safeApiRequest(query) {
// Primeiro tentar a API de busca do site
try {
val encodedQuery = query.encodeSearchQuery()
val url = "$mainUrl/api/search?query=$encodedQuery"

val response = app.get(
url,
headers = headers,
cookies = cookies,
timeout = 30
)

val data = response.parsedSafe<SearchResponseData>() ?: return@safeApiRequest emptyList()

return@safeApiRequest data.results.mapNotNull { item ->
try {
val title = item.title ?: item.name ?: item.originalTitle ?: item.originalName ?: return@mapNotNull null
val year = getYearFromDate(item.releaseDate ?: item.firstAirDate)
val poster = item.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
val id = item.id

val type = when (item.mediaType) {
"movie" -> TvType.Movie
"tv" -> TvType.TvSeries
"anime" -> TvType.Anime
else -> when {
title.contains("(Anime)", ignoreCase = true) -> TvType.Anime
item.releaseDate != null -> TvType.Movie
item.firstAirDate != null -> TvType.TvSeries
else -> TvType.Movie
}
}

// Gerar URL no formato correto COM TYPE
val slug = generateSlug(title)
val url = when (type) {
TvType.Movie -> "$mainUrl/$slug?id=$id&type=movie"
TvType.TvSeries -> "$mainUrl/$slug?id=$id&type=tv"
TvType.Anime -> "$mainUrl/$slug?id=$id&type=anime"
else -> "$mainUrl/$slug?id=$id&type=movie"
}

when (type) {
TvType.Movie -> newMovieSearchResponse(title, url, TvType.Movie) {
this.posterUrl = poster
this.year = year
}
TvType.TvSeries -> newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
this.posterUrl = poster
this.year = year
}
TvType.Anime -> newAnimeSearchResponse(title, url, TvType.Anime) {
this.posterUrl = poster
this.year = year
}
else -> newMovieSearchResponse(title, url, TvType.Movie) {
this.posterUrl = poster
this.year = year
}
}
} catch (e: Exception) {
null
}
}
} catch (e: Exception) {
// Fallback para busca HTML
fallbackSearch(query)
}
}
}

// Fallback caso a API de busca não esteja disponível
private suspend fun fallbackSearch(query: String): List<SearchResponse> {
val searchUrl = "$mainUrl/search?q=${query.encodeSearchQuery()}"
val document = app.get(searchUrl, headers = headers, cookies = cookies).document

return document.select("a[href*='?id=']").mapNotNull { element ->
try {
val href = element.attr("href") ?: return@mapNotNull null
if (href.startsWith("/canal")) return@mapNotNull null

val imgElement = element.selectFirst("img")
val title = imgElement?.attr("alt") ?: 
element.selectFirst(".text-white")?.text() ?:
return@mapNotNull null

val poster = imgElement?.attr("src")?.let { fixUrl(it) }
val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()

// Determinar tipo pela URL
val isSeries = href.contains("type=tv") || href.contains("/tv")
val isMovie = href.contains("type=movie") || href.contains("/movie")
val isAnime = title.contains("(Anime)", ignoreCase = true) || href.contains("type=anime")

// Corrigir URL para incluir type se necessário
var finalUrl = fixUrl(href)
if (!finalUrl.contains("type=")) {
when {
isAnime -> finalUrl += "&type=anime"
isSeries -> finalUrl += "&type=tv"
isMovie -> finalUrl += "&type=movie"
}
}

when {
isAnime -> newAnimeSearchResponse(cleanTitle, finalUrl, TvType.Anime) {
this.posterUrl = poster
this.year = year
}
isSeries -> newTvSeriesSearchResponse(cleanTitle, finalUrl, TvType.TvSeries) {
this.posterUrl = poster
this.year = year
}
isMovie -> newMovieSearchResponse(cleanTitle, finalUrl, TvType.Movie) {
this.posterUrl = poster
this.year = year
}
else -> null
}
} catch (e: Exception) {
null
}
}
}

data class SearchResponseData(
@JsonProperty("results") val results: List<ContentItem>
)

// ========== LOAD() ==========
override suspend fun load(url: String): LoadResponse? {
return safeApiRequest(url) {
try {
// 1. CARREGAR PÁGINA DE DETALHES DO BETTERFLIX
val response = app.get(url, headers = headers, cookies = cookies, timeout = 30)
if (response.code >= 400) return@safeApiRequest null

val document = response.document
val html = response.text

// 2. EXTRAIR DADOS DO OBJETO JSON EMBUTIDO (dadosMulti)
val embeddedData = extractEmbeddedData(html)
if (embeddedData == null) {
return@safeApiRequest null
}

// 3. DETERMINAR TIPO
val tmdbId = embeddedData.id ?: extractTmdbIdFromUrl(url)
val isSeries = url.contains("type=tv")
val isAnime = url.contains("type=anime")
val isMovie = !isSeries && !isAnime

// 4. SE FOR SÉRIE/ANIME, EXTRAIR EPISÓDIOS DO SUPERFLIX
if (isSeries || isAnime) {
val type = if (isAnime) TvType.Anime else TvType.TvSeries
val episodes = extractEpisodesFromSuperflix(tmdbId, url)

println("✅ [EPISODIOS] Total extraído: ${episodes.size} episódios")

newTvSeriesLoadResponse(embeddedData.name ?: "Sem título", url, type, episodes) {
this.posterUrl = embeddedData.poster?.let { fixUrl(it) }
this.backgroundPosterUrl = embeddedData.backdrop?.let { fixUrl(it) }
this.year = embeddedData.date?.substring(0, 4)?.toIntOrNull()
this.plot = embeddedData.bio
this.tags = embeddedData.genres?.split(",")?.map { it.trim() } ?: emptyList()
}
} else {
// PARA FILMES
newMovieLoadResponse(embeddedData.name ?: "Sem título", url, TvType.Movie, url) {
this.posterUrl = embeddedData.poster?.let { fixUrl(it) }
this.backgroundPosterUrl = embeddedData.backdrop?.let { fixUrl(it) }
this.year = embeddedData.date?.substring(0, 4)?.toIntOrNull()
this.plot = embeddedData.bio
this.tags = embeddedData.genres?.split(",")?.map { it.trim() } ?: emptyList()
}
}
} catch (e: Exception) {
null
}
}
}

// ========== FUNÇÕES AUXILIARES DO LOAD ==========

data class EpisodeData(
val ID: Int,
val title: String,
val sinopse: String,
val item: Int,
val thumb_url: String?,
val air_date: String?,
val duration: Int,
val epi_num: Int,
val season: Int
)

// ========== LOGS DETALHADOS NA EXTRAÇÃO DE EPISÓDIOS ==========

// EXTRAIR EPISÓDIOS DO SUPERFLIX COM LOGS DETALHADOS
private suspend fun extractEpisodesFromSuperflix(tmdbId: String?, baseUrl: String): List<Episode> {
println("🔍 [EPISODIOS] ===== INICIANDO EXTRAÇÃO DE EPISÓDIOS =====")
println("🔍 [EPISODIOS] TMDB ID: $tmdbId")

val episodes = mutableListOf<Episode>()

if (tmdbId == null) {
println("❌ [EPISODIOS] TMDB ID não encontrado")
return episodes
}

    // Headers específicos para o SuperFlix (baseado no seu curl)
    val superflixHeaders = mapOf(
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

    try {
        // TENTAR DIFERENTES DOMÍNIOS DO SUPERFLIX
        val superflixDomains = listOf(
            "https://superflixapi.buzz",
            "https://superflixapi.bond",
            "https://superflixapi.asia",
            "https://superflixapi.top"
        )
        
        for (domain in superflixDomains) {
            try {
                // Primeiro precisamos descobrir quantas temporadas existem
                println("🔍 [EPISODIOS] Tentando domínio: $domain")

                // URL para obter dados da série (temporada 1 episódio 1)
                val serieUrl = "$domain/serie/$tmdbId/1/1"
                println("🔍 [EPISODIOS] URL da série: $serieUrl")
                
                val response = app.get(
                    serieUrl,
                    headers = superflixHeaders,
                    timeout = 30
                )
                
                println("✅ [EPISODIOS] Status: ${response.code}")
                
                if (response.code == 200) {
val html = response.text
                    println("✅ [EPISODIOS] HTML recebido: ${html.length} chars")

                    // ANALISAR O HTML PARA ENCONTRAR DADOS
val document = Jsoup.parse(html)
                    
                    // MÉTODO 1: Procurar por scripts com dados
val scripts = document.select("script")
                    println("🔍 [EPISODIOS] Encontrados ${scripts.size} scripts")

for (script in scripts) {
val scriptText = script.html()
                        
                        // Procurar por dados de episódios/temporadas
if (scriptText.contains("ALL_EPISODES") || 
scriptText.contains("episodes") ||
                            scriptText.contains("seasons") ||
                            scriptText.contains("temporadas")) {
                            
                            println("✅ [EPISODIOS] Encontrou script com dados!")
                            println("🔍 [EPISODIOS] Script preview: ${scriptText.take(500)}")
                            
                            // Extrair dados do script
                            val episodeData = extractEpisodeDataFromScript(scriptText, tmdbId)
                            episodes.addAll(episodeData)
                            
                            if (episodes.isNotEmpty()) {
                                println("✅ [EPISODIOS] Extraiu ${episodes.size} episódios do script")
                                return episodes
                            }
                        }
                    }
                    
                    // MÉTODO 2: Procurar por elementos HTML com dados
                    val dataElements = document.select("[data-episodes], [data-seasons], .episodes-list, .seasons-list")
                    if (dataElements.isNotEmpty()) {
                        println("✅ [EPISODIOS] Encontrou elementos com dados: ${dataElements.size}")
                        // Extrair dados dos elementos...
                    }
                    
                    // MÉTODO 3: Tentar API do SuperFlix (se existir)
                    try {
                        val apiUrl = "$domain/api/serie/$tmdbId"
                        println("🔍 [EPISODIOS] Tentando API: $apiUrl")
                        
                        val apiResponse = app.get(apiUrl, headers = superflixHeaders, timeout = 30)
                        if (apiResponse.code == 200) {
                            val apiText = apiResponse.text
                            println("✅ [EPISODIOS] API respondeu: ${apiText.length} chars")
                            
                            if (apiText.contains("{") && apiText.contains("}")) {
                                try {
                                    val json = JSONObject(apiText)
                                    println("✅ [EPISODIOS] JSON parseado com sucesso!")
                                    // Processar JSON...
                                } catch (e: Exception) {
                                    println("❌ [EPISODIOS] Não é JSON válido")
                                }
                            }
}
                    } catch (e: Exception) {
                        println("⚠️ [EPISODIOS] API não disponível: ${e.message}")
}
}
                
            } catch (e: Exception) {
                println("❌ [EPISODIOS] Erro no domínio $domain: ${e.message}")
                continue
}
        }
        
    } catch (e: Exception) {
        println("❌ [EPISODIOS] Erro geral: ${e.message}")
        e.printStackTrace()
    }
    
    println("✅ [EPISODIOS] Total extraído: ${episodes.size} episódios")
    return episodes
}

private fun extractEpisodeDataFromScript(scriptText: String, tmdbId: String): List<Episode> {
    val episodes = mutableListOf<Episode>()
    
    try {
        // PADRÃO 1: ALL_EPISODES = {...}
        val pattern1 = Regex("""ALL_EPISODES\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
        val match1 = pattern1.find(scriptText)
        
        if (match1 != null) {
            println("✅ [EPISODIOS] Encontrou ALL_EPISODES no script")
            val jsonStr = match1.groupValues[1]

            try {
                val json = JSONObject(jsonStr)
                val keys = json.keys()
                
                while (keys.hasNext()) {
                    val seasonKey = keys.next()
                    val seasonNum = seasonKey.toIntOrNull() ?: 1
                    
                    val episodesArray = json.getJSONArray(seasonKey)
                    for (i in 0 until episodesArray.length()) {
                        try {
                            val epObj = episodesArray.getJSONObject(i)
                            
                            val epNumber = epObj.optInt("epi_num", i + 1)
                            val title = epObj.optString("title", "Episódio $epNumber")
                            val description = epObj.optString("sinopse", "").takeIf { it.isNotBlank() }
                            val thumbUrl = epObj.optString("thumb_url").takeIf { 
                                it != "null" && it.isNotBlank() 
                            }?.let {
                                if (it.startsWith("/")) "https://image.tmdb.org/t/p/w300$it" else it
                            }
                            
                            // Criar URL do episódio
                            val episodeUrl = "https://superflixapi.buzz/serie/$tmdbId/$seasonNum/$epNumber"
                            
                            episodes.add(
                                newEpisode(episodeUrl) {
                                    this.name = title
                                    this.season = seasonNum
                                    this.episode = epNumber
                                    this.description = description
                                    this.posterUrl = thumbUrl
                                }
                            )
                            
                            println("📺 [EPISODIOS] Adicionado: S${seasonNum}E${epNumber} - $title")
                            
                        } catch (e: Exception) {
                            println("❌ [EPISODIOS] Erro ao processar episódio $i: ${e.message}")
                        }
                    }
                }
                
            } catch (e: Exception) {
                println("❌ [EPISODIOS] Erro ao parsear JSON: ${e.message}")
            }
}
        
    } catch (e: Exception) {
        println("❌ [EPISODIOS] Erro na extração do script: ${e.message}")
}

return episodes
}
// Função de extração com logs detalhados
private fun extractAllEpisodesDataWithLogs(html: String): Map<String, List<EpisodeData>>? {
println("🔍 [EPISODIOS-PARSER] Iniciando extração de dados...")

try {
// VERSÃO 1: Padrão mais comum
val pattern1 = Regex("""var\s+ALL_EPISODES\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
println("🔍 [EPISODIOS-PARSER] Tentando padrão 1: var ALL_EPISODES = {...};")

val match1 = pattern1.find(html)
if (match1 != null) {
println("✅ [EPISODIOS-PARSER] Padrão 1 encontrou match!")
val jsonString = match1.groupValues[1].trim()
println("🔍 [EPISODIOS-PARSER] JSON extraído (primeiros 300 chars):")
println(jsonString.take(300))
return parseEpisodesJsonWithLogs(jsonString)
}

// VERSÃO 2: Padrão alternativo
val pattern2 = Regex("""ALL_EPISODES\s*=\s*(\{.*?\})\s*;""", RegexOption.DOT_MATCHES_ALL)
println("🔍 [EPISODIOS-PARSER] Tentando padrão 2: ALL_EPISODES = {...};")

val match2 = pattern2.find(html)
if (match2 != null) {
println("✅ [EPISODIOS-PARSER] Padrão 2 encontrou match!")
val jsonString = match2.groupValues[1].trim()
println("🔍 [EPISODIOS-PARSER] JSON extraído (primeiros 300 chars):")
println(jsonString.take(300))
return parseEpisodesJsonWithLogs(jsonString)
}

// VERSÃO 3: Procura direta pelo JSON
println("🔍 [EPISODIOS-PARSER] Tentando busca direta por JSON...")
val jsonStart = html.indexOf("""{"1":""")
if (jsonStart != -1) {
println("✅ [EPISODIOS-PARSER] Encontrou início do JSON na posição $jsonStart")

// Encontrar fim do objeto
var braceCount = 0
var i = jsonStart
var foundEnd = false

while (i < html.length) {
when (html[i]) {
'{' -> braceCount++
'}' -> {
braceCount--
if (braceCount == 0) {
foundEnd = true
break
}
}
}
i++
}

if (foundEnd) {
val jsonString = html.substring(jsonStart, i + 1)
println("✅ [EPISODIOS-PARSER] JSON extraído (${jsonString.length} chars)")
println("🔍 [EPISODIOS-PARSER] JSON (primeiros 300 chars):")
println(jsonString.take(300))
return parseEpisodesJsonWithLogs(jsonString)
}
}

println("❌ [EPISODIOS-PARSER] Nenhum padrão encontrou ALL_EPISODES")
return null

} catch (e: Exception) {
println("❌ [EPISODIOS-PARSER] Erro na extração: ${e.message}")
e.printStackTrace()
return null
}
}

private fun parseEpisodesJsonWithLogs(jsonString: String): Map<String, List<EpisodeData>>? {
println("🔍 [EPISODIOS-PARSER] Parseando JSON...")

try {
val jsonObject = JSONObject(jsonString)
val result = mutableMapOf<String, List<EpisodeData>>()

val seasonKeys = jsonObject.keys().asSequence().toList()
println("✅ [EPISODIOS-PARSER] Temporadas encontradas: ${seasonKeys.joinToString(", ")}")

seasonKeys.forEach { seasonKey ->
println("🔍 [EPISODIOS-PARSER] Processando temporada: $seasonKey")
val episodesArray = jsonObject.getJSONArray(seasonKey)
println("🔍 [EPISODIOS-PARSER] Temporada $seasonKey tem ${episodesArray.length()} episódios")

val episodesList = mutableListOf<EpisodeData>()

for (i in 0 until episodesArray.length()) {
try {
val episodeObj = episodesArray.getJSONObject(i)

// Log dos campos para debug
if (i == 0) { // Mostrar apenas para o primeiro episódio de cada temporada
println("🔍 [EPISODIOS-PARSER] Campos do episódio 1:")
episodeObj.keys().forEach { key ->
println("   - $key: ${episodeObj.opt(key)}")
}
}

episodesList.add(
EpisodeData(
ID = episodeObj.optInt("ID"),
title = episodeObj.optString("title"),
sinopse = episodeObj.optString("sinopse"),
item = episodeObj.optInt("item"),
thumb_url = episodeObj.optString("thumb_url").takeIf { 
it != "null" && it.isNotBlank() && it != "null" 
},
air_date = episodeObj.optString("air_date").takeIf { 
it != "null" && it.isNotBlank() && it != "null" 
},
duration = episodeObj.optInt("duration"),
epi_num = episodeObj.optInt("epi_num"),
season = episodeObj.optInt("season")
)
)

if (i < 3) { // Log dos primeiros 3 episódios
println("✅ [EPISODIOS-PARSER] Episódio ${i+1}: ${episodeObj.optString("title")}")
}

} catch (e: Exception) {
println("❌ [EPISODIOS-PARSER] Erro ao parsear episódio $i: ${e.message}")
}
}

result[seasonKey] = episodesList
println("✅ [EPISODIOS-PARSER] Temporada $seasonKey processada: ${episodesList.size} episódios")
}

println("✅ [EPISODIOS-PARSER] Parse concluído: ${result.size} temporadas no total")
return result

} catch (e: Exception) {
println("❌ [EPISODIOS-PARSER] Erro ao parsear JSON: ${e.message}")
e.printStackTrace()
return null
}
}

// ========== FUNÇÕES ORIGINAIS (SEM LOGS) ==========

private fun extractEmbeddedData(html: String): EmbeddedData? {
try {
val pattern = Regex("""const dadosMulti\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
val match = pattern.find(html)

if (match != null) {
val jsonString = match.groupValues[1]
return AppUtils.tryParseJson<EmbeddedData>(jsonString)
}

return null
} catch (e: Exception) {
return null
}
}

private fun extractTmdbIdFromUrl(url: String): String? {
val idMatch = Regex("[?&]id=(\\d+)").find(url)
return idMatch?.groupValues?.get(1)
}

// ========== LOAD LINKS (ORIGINAL SEM LOGS) ==========
override suspend fun loadLinks(
data: String,
isCasting: Boolean,
subtitleCallback: (SubtitleFile) -> Unit,
callback: (ExtractorLink) -> Unit
): Boolean {
return safeApiRequest(data) {
try {
// Extrair TMDB ID da URL
val tmdbId = extractTmdbId(data)

if (tmdbId == null) {
return@safeApiRequest false
}

// Determinar tipo da URL para usar na extração
val type = when {
data.contains("type=anime") -> "anime"
data.contains("type=tv") -> "tv"
else -> "movie"
}

// TENTAR TODOS OS DOMÍNIOS DO SUPERFLIX
for (superflixDomain in superflixDomains) {
try {
val success = extractVideoFromSuperflix(superflixDomain, tmdbId, type, callback)
if (success) {
// Adicionar legenda em português se disponível
try {
val subtitleUrl = "https://complicado.sbs/cdn/down/disk11/${tmdbId.substring(0, 32)}/Subtitle/subtitle_por.vtt"
subtitleCallback.invoke(
SubtitleFile("Português", subtitleUrl)
)
} catch (e: Exception) {
// Ignorar erro de legenda
}

return@safeApiRequest true
}
} catch (e: Exception) {
// Tentar próximo domínio
continue
}
}

return@safeApiRequest false
} catch (e: Exception) {
false
}
}
}

private fun extractTmdbId(url: String): String? {
val idMatch = Regex("[?&]id=(\\d+)").find(url)
return idMatch?.groupValues?.get(1)
}

private suspend fun extractVideoFromSuperflix(
domain: String,
tmdbId: String,
type: String,
callback: (ExtractorLink) -> Unit
): Boolean {
try {
// Lista de video_ids possíveis
val possibleVideoIds = listOf("303309", "351944")

for (videoId in possibleVideoIds) {
// PASSO 1: Obter o video_url da API do SuperFlix
val apiUrl = "$domain/api"

val apiHeaders = mapOf(
"User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
"Accept" to "application/json, text/plain, */*",
"Accept-Language" to "pt-BR",
"Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
"Origin" to domain,
"Referer" to "$domain/filme/$tmdbId",
"X-Requested-With" to "XMLHttpRequest",
"Sec-Fetch-Dest" to "empty",
"Sec-Fetch-Mode" to "cors",
"Sec-Fetch-Site" to "same-origin"
)

val apiData = mapOf(
"action" to "getPlayer",
"video_id" to videoId
)

try {
val apiResponse = app.post(apiUrl, data = apiData, headers = apiHeaders, timeout = 30)

if (apiResponse.code >= 400) {
continue
}

val apiJson = JSONObject(apiResponse.text)

// Verificar corretamente o status
val errors = apiJson.optString("errors", "1")
val message = apiJson.optString("message", "")

if (errors == "1" || message != "success") {
continue
}

val videoUrl = apiJson.optJSONObject("data")?.optString("video_url")
if (videoUrl.isNullOrEmpty()) {
continue
}

// PASSO 2: Extrair o hash/token da URL
val hash = extractHashFromVideoUrl(videoUrl)
if (hash == null) {
continue
}

// PASSO 3: Fazer a requisição para obter o m3u8
val playerResult = requestPlayerHash(hash, callback)
if (playerResult) {
return true
}

} catch (e: Exception) {
continue
}
}

return false

} catch (e: Exception) {
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
else -> null
}
}

private suspend fun requestPlayerHash(
hash: String,
callback: (ExtractorLink) -> Unit
): Boolean {
try {
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

val playerResponse = app.post(playerUrl, data = playerData, headers = playerHeaders, timeout = 30)

if (playerResponse.code >= 400) {
return false
}

val playerJson = JSONObject(playerResponse.text)

// Extrair o link m3u8
val m3u8Url = playerJson.optString("securedLink")
.takeIf { it.isNotBlank() }
?: playerJson.optString("videoSource")
.takeIf { it.isNotBlank() }

if (m3u8Url.isNullOrBlank()) {
return false
}

// Determinar qualidade
val quality = when {
m3u8Url.contains("1080") -> Qualities.P1080.value
m3u8Url.contains("720") -> Qualities.P720.value
m3u8Url.contains("480") -> Qualities.P480.value
m3u8Url.contains("360") -> Qualities.P360.value
else -> Qualities.P720.value
}

// Criar o ExtractorLink
newExtractorLink(name, "SuperFlix ($quality)", m3u8Url, ExtractorLinkType.M3U8) {
referer = "$playerDomain/"
this.quality = quality
}.also { callback(it) }

return true

} catch (e: Exception) {
return false
}
}

// Função de extensão para codificar query
private fun String.encodeSearchQuery(): String {
return java.net.URLEncoder.encode(this, "UTF-8")
}
}
