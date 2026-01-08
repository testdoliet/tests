package com.Doramogo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import org.jsoup.nodes.Element
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class DoramogoPlugin: Plugin() {
    override fun load(context: Context) {
        // All providers should be added in this manner. Please don't edit the providers list directly.
        registerMainAPI(Doramogo())
    }
}

class Doramogo : MainAPI() {
    override var mainUrl = "https://www.doramogo.net"
    override var name = "Doramogo"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)
    override val usesWebView = false

    private val mapper = jacksonObjectMapper()

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Em Destaque",
        "$mainUrl/episodios" to "Episódios Recentes",
        "$mainUrl/dorama?slug=&status=&ano=&classificacao_idade=&idiomar=DUB" to "Doramas Dublados",
        "$mainUrl/dorama?slug=&status=&ano=&classificacao_idade=&idiomar=LEG" to "Doramas Legendados",
        "$mainUrl/genero/dorama-acao" to "Ação",
        "$mainUrl/genero/dorama-aventura" to "Aventura",
        "$mainUrl/genero/dorama-comedia" to "Comédia",
        "$mainUrl/genero/dorama-crime" to "Crime",
        "$mainUrl/genero/dorama-drama" to "Drama",
        "$mainUrl/genero/dorama-familia" to "Família",
        "$mainUrl/genero/dorama-fantasia" to "Fantasia",
        "$mainUrl/genero/dorama-ficcao-cientifica" to "Ficção Científica",
        "$mainUrl/genero/dorama-misterio" to "Mistério",
        "$mainUrl/genero/dorama-reality" to "Reality Shows",
        "$mainUrl/genero/dorama-sci-fi" to "Sci-Fi",
        "$mainUrl/filmes" to "Filmes"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) {
            if (request.data.contains("/dorama?") || request.data.contains("/filmes")) {
                "${request.data}&pagina=$page"
            } else if (request.data.contains("/genero/")) {
                "${request.data}/pagina/$page"
            } else if (request.data.contains("/episodios")) {
                "${request.data}?pagina=$page"
            } else {
                "${request.data}?page=$page"
            }
        } else {
            request.data
        }

        val document = app.get(url).document
        val items = ArrayList<SearchResponse>()

        // Para episódios recentes, usar layout diferente
        if (request.data.contains("/episodios")) {
            // Layout vertical para episódios
            document.select(".episode-card").forEach { card ->
                val aTag = card.selectFirst("a") ?: return@forEach
                
                // Para episódios, o título é o nome do dorama
                val titleElement = card.selectFirst("h3, .episode-title")
                val episodeTitle = titleElement?.text()?.trim() ?: return@forEach
                
                // Extrair nome do dorama (remover " - Episódio XX" se existir)
                val doramaName = episodeTitle.replace(Regex("\\s*-\\s*Episódio\\s*\\d+.*$"), "").trim()
                
                val href = aTag.attr("href")
                
                // Imagem do episódio
                val imgElement = card.selectFirst("img")
                val posterUrl = when {
                    imgElement?.hasAttr("src") == true -> fixUrl(imgElement.attr("src"))
                    imgElement?.hasAttr("data-src") == true -> fixUrl(imgElement.attr("data-src"))
                    else -> null
                }
                
                // Extrair número do episódio
                val episodeMatch = Regex("Episódio\\s*(\\d+)", RegexOption.IGNORE_CASE).find(episodeTitle)
                val episodeNumber = episodeMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                
                // Para episódios, criar um item de TV Series
                items.add(newTvSeriesSearchResponse(doramaName, fixUrl(href), TvType.TvSeries) {
                    this.posterUrl = posterUrl
                    // Adicionar informação do episódio no nome
                    this.name = "$doramaName (Episódio $episodeNumber)"
                })
            }
        } else {
            // Layout normal para filmes/doramas - baseado no HTML fornecido
            document.select("div.archive, .movie-card, .drama-card").forEach { card ->
                val aTag = card.selectFirst("a") ?: return@forEach
                
                // Título está no h2 ou .title
                val title = card.selectFirst("h2, .title")?.text()?.trim() 
                    ?: aTag.attr("title")?.trim()
                    ?: return@forEach
                
                val href = aTag.attr("href")
                
                // Imagem - corrigido para usar data-src e src
                val imgElement = card.selectFirst("img")
                val posterUrl = when {
                    imgElement?.hasAttr("data-src") == true -> fixUrl(imgElement.attr("data-src"))
                    imgElement?.hasAttr("src") == true -> fixUrl(imgElement.attr("src"))
                    else -> null
                }
                
                // Determinar tipo
                val isMovie = href.contains("/filmes/") || request.name.contains("Filmes")
                val type = if (isMovie) TvType.Movie else TvType.TvSeries
                
                val item = if (type == TvType.Movie) {
                    newMovieSearchResponse(title, fixUrl(href), type) {
                        this.posterUrl = posterUrl
                    }
                } else {
                    newTvSeriesSearchResponse(title, fixUrl(href), type) { 
                        this.posterUrl = posterUrl
                    }
                }
                items.add(item)
            }
        }

        // Verificar paginação
        val hasNextPage = document.select("""a[href*="pagina/"], a[href*="?page="], a[href*="&pagina="], 
            .pagination a:contains(›), .pagination a:contains(»), 
            .next-page, .page-next""").isNotEmpty()
        
        return newHomePageResponse(request.name, items.distinctBy { it.url }, hasNextPage)
    }

    private fun getType(url: String): TvType {
        return when {
            url.contains("/series/") || url.contains("/dorama/") -> TvType.TvSeries
            url.contains("/filmes/") -> TvType.Movie
            else -> TvType.TvSeries // padrão para doramogo
        }
    }

    // --- Search ---
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search/?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(searchUrl).document
        
        return document.select("div.archive, .movie-card, .search-result").mapNotNull { card ->
            val aTag = card.selectFirst("a") ?: return@mapNotNull null
            val title = card.selectFirst("h2, h3, .search-title")?.text()?.trim() 
                ?: aTag.attr("title") 
                ?: return@mapNotNull null
            val href = aTag.attr("href")
            
            val imgElement = card.selectFirst("img")
            val posterUrl = when {
                imgElement?.hasAttr("data-src") == true -> fixUrl(imgElement.attr("data-src"))
                imgElement?.hasAttr("src") == true -> fixUrl(imgElement.attr("src"))
                else -> null
            }
            
            val type = getType(href)
            
            if (type == TvType.Movie) {
                newMovieSearchResponse(title, fixUrl(href), type) { 
                    this.posterUrl = posterUrl 
                }
            } else {
                newTvSeriesSearchResponse(title, fixUrl(href), type) { 
                    this.posterUrl = posterUrl 
                }
            }
        }
    }

    // --- Load ---
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        
        // Título - baseado no HTML fornecido
        val fullTitle = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")
            ?: document.selectFirst("title")?.text()?.replace(" - Doramogo", "")?.trim()
            ?: return null
        
        // Limpar título (remover " - Dublado e Legendado" etc)
        val title = fullTitle.replace(Regex("\\s*-\\s*Dublado.*"), "")
            .replace(Regex("\\s*-\\s*Legendado.*"), "")
            .replace(Regex("\\s*-\\s*Online.*"), "")
            .trim()
        
        // Descrição - corrigido para extrair do HTML fornecido
        val description = document.selectFirst("meta[property='og:description']")?.attr("content")
            ?: document.selectFirst("#sinopse-text")?.text()?.trim()
            ?: document.selectFirst(".description, .overview, .sinopse")?.text()?.trim()
        
        // Poster - corrigido para extrair imagem correta
        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")?.let { fixUrl(it) }
            ?: document.selectFirst("#w-55")?.attr("src")?.let { fixUrl(it) }
            ?: document.selectFirst(".thumbnail img")?.attr("src")?.let { fixUrl(it) }
            ?: document.selectFirst("img[alt*='$title']")?.attr("src")?.let { fixUrl(it) }
        
        // Ano - extrair do texto de informações
        val infoText = document.selectFirst(".detail p.text-white")?.text() ?: ""
        val yearMatch = Regex("""/(\d{4})/""").find(infoText)
        val year = yearMatch?.groupValues?.get(1)?.toIntOrNull()
            ?: title.findYear()
            ?: document.selectFirst("meta[property='article:published_time']")?.attr("content")?.substring(0, 4)?.toIntOrNull()
        
        // Gêneros - baseado no HTML fornecido
        val tags = document.select(".gens a").map { it.text().trim() }
            .ifEmpty { document.select(".gens span").map { it.text().trim() } }
        
        // Status, Estúdio, Áudio, Duração - extrair do HTML
        val castsInfo = mutableMapOf<String, String>()
        document.select(".casts div").forEach { div ->
            val text = div.text()
            if (text.contains(":")) {
                val parts = text.split(":", limit = 2)
                if (parts.size == 2) {
                    val key = parts[0].trim().lowercase()
                    val value = parts[1].trim()
                    castsInfo[key] = value
                }
            }
        }
        
        // Avaliação - não encontrada no HTML fornecido
        val rating = null
        
        // Determinar tipo
        val isMovie = url.contains("/filmes/") || document.selectFirst(".type")?.text()?.contains("Filme") == true
        val type = if (isMovie) TvType.Movie else TvType.TvSeries
        
        // Para séries, extrair temporadas e episódios - baseado no HTML fornecido
        if (type == TvType.TvSeries) {
            val seasons = extractSeasons(document, url)
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, seasons.flatMap { it.episodes }) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
                
                // Adicionar informações adicionais
                castsInfo["status"]?.let { this.status = when(it) {
                    "Em Produção" -> ShowStatus.Ongoing
                    "Concluído" -> ShowStatus.Completed
                    else -> ShowStatus.Ongoing
                }}
                
                castsInfo["estúdio"]?.let { this.studio = it }
                castsInfo["áudio"]?.let { 
                    // Extrair qualidade do áudio
                    if (it.contains("Dublado")) {
                        this.dubStatus = true
                    }
                }
            }
        } else {
            // Para filmes
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
                
                castsInfo["duraçã"]?.let { durationText ->
                    this.duration = durationText.parseDuration()
                }
                
                castsInfo["áudio"]?.let { 
                    if (it.contains("Dublado")) {
                        this.dubStatus = true
                    }
                }
            }
        }
    }
    
    private fun extractSeasons(doc: org.jsoup.nodes.Document, baseUrl: String): List<SeasonData> {
        val seasons = mutableListOf<SeasonData>()
        
        // Procurar temporadas baseado no HTML fornecido
        doc.select(".dorama-one-season-block").forEach { seasonBlock ->
            val seasonTitle = seasonBlock.selectFirst(".dorama-one-season-title")?.text()?.trim() ?: "Temporada"
            val seasonNumber = extractSeasonNumber(seasonTitle)
            
            val episodes = mutableListOf<Episode>()
            seasonBlock.select(".dorama-one-episode-item").forEach { episodeItem ->
                val episodeLink = episodeItem.attr("href")?.let { fixUrl(it) } ?: return@forEach
                val episodeTitle = episodeItem.selectFirst(".episode-title")?.text()?.trim() ?: "Episódio"
                
                val episodeNumber = extractEpisodeNumber(episodeTitle)
                
                episodes.add(newEpisode(episodeLink) {
                    this.name = episodeTitle
                    this.season = seasonNumber
                    this.episode = episodeNumber
                    this.posterUrl = null // Página de episódio pode ter imagem diferente
                })
            }
            
            if (episodes.isNotEmpty()) {
                seasons.add(SeasonData(seasonNumber, seasonTitle, episodes))
            }
        }
        
        // Se não encontrou temporadas estruturadas, verificar se há episódios na URL base
        if (seasons.isEmpty()) {
            val episodes = mutableListOf<Episode>()
            doc.select("a[href*='/episodio-'], a[href*='/episode-']").forEach { episodeLink ->
                val episodeUrl = episodeLink.attr("href")?.let { fixUrl(it) } ?: return@forEach
                val episodeTitle = episodeLink.text().trim().ifEmpty { "Episódio" }
                
                val seasonEpisode = extractSeasonEpisodeFromUrl(episodeUrl)
                
                episodes.add(newEpisode(episodeUrl) {
                    this.name = episodeTitle
                    this.season = seasonEpisode.first
                    this.episode = seasonEpisode.second
                })
            }
            
            if (episodes.isNotEmpty()) {
                val episodesBySeason = episodes.groupBy { it.season }
                episodesBySeason.forEach { (seasonNum, seasonEpisodes) ->
                    seasons.add(SeasonData(seasonNum, "Temporada $seasonNum", seasonEpisodes))
                }
            }
        }
        
        return seasons.sortedBy { it.seasonNumber }
    }
    
    private data class SeasonData(
        val seasonNumber: Int,
        val name: String,
        val episodes: List<Episode>
    )
    
    private fun extractSeasonNumber(text: String): Int {
        val pattern = Regex("""(\d+)°\s*Temporada|Temporada\s*(\d+)|Season\s*(\d+)|T\s*(\d+)""", RegexOption.IGNORE_CASE)
        val match = pattern.find(text)
        return match?.groupValues?.get(1)?.toIntOrNull() 
            ?: match?.groupValues?.get(2)?.toIntOrNull()
            ?: match?.groupValues?.get(3)?.toIntOrNull()
            ?: match?.groupValues?.get(4)?.toIntOrNull()
            ?: 1
    }
    
    private fun extractEpisodeNumber(text: String): Int {
        val pattern = Regex("""Episódio\s*(\d+)|Episode\s*(\d+)|E\s*(\d+)|EP\s*(\d+)""", RegexOption.IGNORE_CASE)
        val match = pattern.find(text)
        return match?.groupValues?.get(1)?.toIntOrNull()
            ?: match?.groupValues?.get(2)?.toIntOrNull()
            ?: match?.groupValues?.get(3)?.toIntOrNull()
            ?: match?.groupValues?.get(4)?.toIntOrNull()
            ?: 1
    }
    
    private fun extractSeasonEpisodeFromUrl(url: String): Pair<Int, Int> {
        val seasonPattern = Regex("""temporada[_-](\d+)|season[_-](\d+)|/T(\d+)/""", RegexOption.IGNORE_CASE)
        val episodePattern = Regex("""episodio[_-](\d+)|episode[_-](\d+)|/E(\d+)/""", RegexOption.IGNORE_CASE)
        
        val seasonMatch = seasonPattern.find(url)
        val episodeMatch = episodePattern.find(url)
        
        val season = seasonMatch?.groupValues?.get(1)?.toIntOrNull()
            ?: seasonMatch?.groupValues?.get(2)?.toIntOrNull()
            ?: seasonMatch?.groupValues?.get(3)?.toIntOrNull()
            ?: 1
        
        val episode = episodeMatch?.groupValues?.get(1)?.toIntOrNull()
            ?: episodeMatch?.groupValues?.get(2)?.toIntOrNull()
            ?: episodeMatch?.groupValues?.get(3)?.toIntOrNull()
            ?: 1
        
        return Pair(season, episode)
    }
    
    private fun String.findYear(): Int? {
        val pattern = Regex("""\b(19\d{2}|20\d{2})\b""")
        return pattern.find(this)?.value?.toIntOrNull()
    }
    
    private fun String?.parseDuration(): Int? {
        if (this.isNullOrBlank()) return null
        val pattern = Regex("""(\d+)\s*(min|minutes|minutos|m)""", RegexOption.IGNORE_CASE)
        val match = pattern.find(this)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    // --- Load Links ---
    // --- Load Links ---
override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    // TODO: Implementar extração de links quando tiver acesso à estrutura do player
    // Por enquanto, retorna false para indicar que não há links disponíveis
    
    println("⚠️ Extração de links temporariamente desativada")
    println("📝 URL do episódio: $data")
    
    // Retorna false para não mostrar nenhum link
    return false
    
    /*
    Código comentado para referência futura:
    
    try {
        val document = app.get(data).document
        
        // 1. Tentar encontrar player via iframe
        val iframe = document.selectFirst("iframe")
        if (iframe != null) {
            val iframeSrc = iframe.attr("src")
            if (iframeSrc.isNotBlank()) {
                // O Doramogo parece usar iframes para players externos
                return handleExternalPlayer(fixUrl(iframeSrc), callback)
            }
        }
        
        // 2. Procurar por scripts que contenham links de vídeo
        val scriptSources = extractFromScripts(document)
        if (scriptSources.isNotEmpty()) {
            scriptSources.forEach { videoUrl ->
                val quality = extractQuality(videoUrl)
                callback(newExtractorLink(
                    name,
                    "Doramogo Player",
                    fixUrl(videoUrl),
                    "",
                    quality,
                    false
                ))
            }
            return true
        }
        
        // 3. Verificar se há links diretos em data attributes
        val videoElements = document.select("[data-video-src], [data-src*='.m3u8'], [data-src*='.mp4']")
        videoElements.forEach { element ->
            val videoUrl = element.attr("data-video-src")
                .ifBlank { element.attr("data-src") }
            
            if (videoUrl.isNotBlank() && (videoUrl.contains(".m3u8") || videoUrl.contains(".mp4"))) {
                val quality = extractQuality(videoUrl)
                callback(newExtractorLink(
                    name,
                    "Doramogo",
                    fixUrl(videoUrl),
                    "",
                    quality,
                    false
                ))
            }
        }
        
        return videoElements.isNotEmpty()
        
    } catch (e: Exception) {
        println("❌ Erro ao extrair links: ${e.message}")
        return false
    }
    */
}

// Funções auxiliares para extração de links (mantidas para referência futura)
/*
private suspend fun handleExternalPlayer(playerUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
    try {
        val playerDoc = app.get(playerUrl).document
        
        // Verificar se é um player comum
        val videoSources = findVideoSources(playerDoc)
        if (videoSources.isNotEmpty()) {
            videoSources.forEach { videoUrl ->
                val quality = extractQuality(videoUrl)
                callback(newExtractorLink(
                    name,
                    "External Player",
                    fixUrl(videoUrl),
                    "",
                    quality,
                    false
                ))
            }
            return true
        }
        
        // Tentar extrair de scripts no player
        val scriptSources = extractFromScripts(playerDoc)
        scriptSources.forEach { videoUrl ->
            val quality = extractQuality(videoUrl)
            callback(newExtractorLink(
                name,
                "External Player",
                fixUrl(videoUrl),
                "",
                quality,
                false
            ))
        }
        
        return scriptSources.isNotEmpty()
        
    } catch (e: Exception) {
        println("❌ Erro no player externo: ${e.message}")
        return false
    }
}

private fun findVideoSources(doc: org.jsoup.nodes.Document): List<String> {
    val sources = mutableListOf<String>()
    
    // Procurar video tags
    doc.select("video source").forEach { source ->
        val src = source.attr("src")
        if (src.isNotBlank()) {
            sources.add(fixUrl(src))
        }
    }
    
    // Procurar em divs com classes de player
    doc.select("""[class*="player"], [id*="player"]""").forEach { player ->
        player.select("iframe, source").forEach { 
            it.attr("src")?.takeIf { src -> src.isNotBlank() }?.let { src ->
                sources.add(fixUrl(src))
            }
        }
    }
    
    return sources.distinct()
}

private fun extractFromScripts(doc: org.jsoup.nodes.Document): List<String> {
    val sources = mutableListOf<String>()
    
    doc.select("script").forEach { script ->
        val scriptText = script.html()
        if (scriptText.contains("m3u8") || scriptText.contains("mp4") || scriptText.contains("video")) {
            // Padrões comuns
            val patterns = listOf(
                Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']"""),
                Regex("""["'](https?://[^"']+\.mp4[^"']*)["']"""),
                Regex("""src\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']"""),
                Regex("""file\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']"""),
                Regex("""videoUrl\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']"""),
                Regex("""streamUrl\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']"""),
                Regex("""url\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""")
            )
            
            patterns.forEach { pattern ->
                pattern.findAll(scriptText).forEach { match ->
                    val url = match.groupValues[1]
                    if (url.isNotBlank()) {
                        sources.add(url)
                    }
                }
            }
        }
    }
    
    return sources.distinct()
}

private fun extractQuality(url: String): Int {
    return when {
        url.contains("1080") || url.contains("fullhd") -> 1080
        url.contains("720") || url.contains("hd") -> 720
        url.contains("480") || url.contains("sd") -> 480
        url.contains("360") -> 360
        url.contains("240") -> 240
        else -> 720 // padrão HD
    }
}
*/
