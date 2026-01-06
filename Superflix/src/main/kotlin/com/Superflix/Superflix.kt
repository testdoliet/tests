package com.Superflix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import org.jsoup.nodes.Element
import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

class SuperflixMain : MainAPI() {
    override var mainUrl = "https://superflix1.cloud"
    override var name = "Superflix"
    override val hasMainPage = true
    override var lang = "pt"
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val usesWebView = false

    companion object {
        /** TODAS as categorias reais do site (sem repetir) */
        private val GENRE_URLS = listOf(
            "/category/acao/" to "Ação",
            "/category/action-adventure/" to "Action & Adventure",
            "/category/animacao/" to "Animação",
            "/category/aventura/" to "Aventura",
            "/category/cinema-tv/" to "Cinema TV",
            "/category/comedia/" to "Comédia",
            "/category/crime/" to "Crime",
            "/category/documentario/" to "Documentário",
            "/category/drama/" to "Drama",
            "/category/familia/" to "Família",
            "/category/fantasia/" to "Fantasia",
            "/category/faroeste/" to "Faroeste",
            "/category/ficcao-cientifica/" to "Ficção Científica",
            "/category/guerra/" to "Guerra",
            "/category/historia/" to "História",
            "/category/kids/" to "Kids",
            "/category/misterio/" to "Mistério",
            "/category/musica/" to "Música",
            "/category/news/" to "News",
            "/category/reality/" to "Reality",
            "/category/romance/" to "Romance",
            "/category/sci-fi-fantasy/" to "Sci-Fi & Fantasy",
            "/category/soap/" to "Soap",
            "/category/talk/" to "Talk",
            "/category/terror/" to "Terror",
            "/category/thriller/" to "Thriller",
            "/category/war-politics/" to "War & Politics"
        )

        /** Abas fixas da HOME */
        private val FIXED_TABS = listOf(
            "/" to "Filmes Recomendados",
            "/" to "Séries Recomendadas",
            "/category/lancamentos/" to "Lançamentos"
        )
    }

    override val mainPage = mainPageOf(
        *FIXED_TABS.map { (path, name) -> "$mainUrl$path" to name }.toTypedArray(),
        *GENRE_URLS.map { (path, name) -> "$mainUrl$path" to name }.toTypedArray()
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        // ---------- RECOMENDADOS (HOME, SEM PAGINAÇÃO) ----------
        if (request.name == "Filmes Recomendados" || request.name == "Séries Recomendadas") {

            val document = app.get(mainUrl).document

            val selector = if (request.name == "Filmes Recomendados") {
                "#widget_list_movies_series-6 article.post"
            } else {
                "#widget_list_movies_series-8 article.post"
            }

            val home = document.select(selector)
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }

            return newHomePageResponse(
                request.name,
                home,
                false
            )
        }

        // ---------- GÊNEROS / LANÇAMENTOS (COM PAGINAÇÃO) ----------
        val baseUrl = if (request.data.endsWith("/")) {
            request.data
        } else {
            "${request.data}/"
        }

        val url = if (page > 1) {
            "${baseUrl}page/$page/"
        } else {
            baseUrl
        }

        val document = app.get(url).document

        val home = document.select("article.post")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        // mesma lógica do plugin SuperFlix (mais permissiva)
        val hasNext =
            document.select("a:contains(Próxima)").isNotEmpty() ||
            document.select(".page-numbers a[href*='page']").isNotEmpty() ||
            document.select(".pagination").isNotEmpty()

        return newHomePageResponse(
            request.name,
            home,
            hasNext
        )
    }
    
    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst("h2.entry-title")?.text() ?: return null
        val href = selectFirst("a.lnk-blk")?.attr("href") ?: return null
        val posterUrl = selectFirst("img")?.attr("src")?.let { fixUrl(it) }
        val year = selectFirst("span.year")?.text()?.toIntOrNull()

        return when {
            href.contains("/filme/") ->
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = posterUrl
                    this.year = year
                }

            href.contains("/serie/") ->
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = posterUrl
                    this.year = year
                }

            else -> null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${query.replace(" ", "+")}"
        val document = app.get(searchUrl).document

        return document.select("article.post")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")?.text()
            ?: throw ErrorLoadingException("Título não encontrado")

        val poster = document.selectFirst("div.post-thumbnail img")
            ?.attr("src")?.let { fixUrl(it) }

        // BANNER REAL - extraído das divs no final do HTML
        val banner = document.selectFirst("div.bghd img.TPostBg, div.bgft img.TPostBg")
            ?.attr("src")?.let { fixUrl(it) }

        // ANO - usando o método original do código anterior
        val year = document.selectFirst("span.year")?.text()?.toIntOrNull()

        // DURAÇÃO - como no exemplo do SuperFlix
        val duration = document.selectFirst("span:containsOwn(min.)")?.text()
            ?.replace(" min.", "")?.trim()?.toIntOrNull()

        // DESCRIÇÃO
        val description = document.selectFirst("div.description p")?.text()

        // TAGS - usando o método original do código anterior
        val genres = document.select("span.genres a").map { it.text() }.filter { it.isNotBlank() }

        // DIRETOR
        val director = document.selectFirst("p:contains(Diretor)")?.ownText()?.trim()
            ?: document.select("p").firstOrNull { it.text().contains("Diretor") }?.text()
                ?.replace("Diretor", "")?.trim()

        // ELENCO
        val actors = mutableListOf<String>()
        
        // Adiciona o diretor como primeiro item do elenco (se existir)
        director?.let { actors.add("Diretor: $it") }
        
        // Adiciona o restante do elenco
        document.selectFirst("p:contains(Elenco)")?.ownText()?.trim()
            ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
            ?.let { actors.addAll(it) }

        // DETERMINAR SE É FILME OU SÉRIE
        return if (url.contains("/filme/")) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = banner
                this.year = year
                this.duration = duration  // Duração para filmes
                this.plot = description
                this.tags = genres  // Tags como estava no código original
                addActors(actors)
            }
        } else {
            // Extrair episódios do iframe
            val episodes = extractEpisodes(document, url)

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = banner
                this.year = year
                this.plot = description
                this.tags = genres  // Tags como estava no código original
                addActors(actors)
            }
        }
    }

    private suspend fun extractEpisodes(document: org.jsoup.nodes.Document, seriesUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        try {
            // Encontrar o iframe que contém os episódios
            val iframeSrc = document.selectFirst("div#adangle-pop-iframe-container iframe")?.attr("src")
                ?: document.selectFirst("iframe[src*='assistirseriesonline']")?.attr("src")
            
            if (iframeSrc != null) {
                // Fazer requisição para o iframe
                val iframeDoc = app.get(fixUrl(iframeSrc)).document
                
                // Extrair episódios do iframe
                // Você precisa verificar como os episódios são estruturados no iframe
                // Aqui estou usando um padrão comum, mas pode precisar de ajustes
                iframeDoc.select("a[href*='episodio'], .episode-item, .episode-link").forEachIndexed { index, element ->
                    try {
                        val episodeUrl = element.attr("href")?.let { fixUrl(it) }
                        if (episodeUrl != null) {
                            val episodeNumber = extractEpisodeNumber(element, index + 1)
                            val episodeName = element.text().trim().ifBlank { "Episódio $episodeNumber" }
                            
                            episodes.add(
                                newEpisode(episodeUrl) {
                                    this.name = episodeName
                                    this.season = 1
                                    this.episode = episodeNumber
                                }
                            )
                        }
                    } catch (e: Exception) {
                        // Ignorar erros em episódios individuais
                    }
                }
                
                // Se não encontrou episódios no padrão acima, tentar outro padrão
                if (episodes.isEmpty()) {
                    iframeDoc.select("button[data-url], [data-episode]").forEachIndexed { index, element ->
                        try {
                            val episodeUrl = element.attr("data-url") ?: element.attr("href")
                            if (episodeUrl != null) {
                                val fixedUrl = fixUrl(episodeUrl)
                                val episodeNumber = element.attr("data-episode").toIntOrNull() ?: (index + 1)
                                val episodeName = element.text().trim().ifBlank { "Episódio $episodeNumber" }
                                
                                episodes.add(
                                    newEpisode(fixedUrl) {
                                        this.name = episodeName
                                        this.season = 1
                                        this.episode = episodeNumber
                                    }
                                )
                            }
                        } catch (e: Exception) {
                            // Ignorar erros
                        }
                    }
                }
            }
            
            // Se ainda não encontrou episódios, criar um placeholder
            if (episodes.isEmpty()) {
                episodes.add(
                    newEpisode(seriesUrl) {
                        this.name = "Assistir Série"
                        this.season = 1
                        this.episode = 1
                    }
                )
            }
        } catch (e: Exception) {
            // Em caso de erro, criar um episódio placeholder
            episodes.add(
                newEpisode(seriesUrl) {
                    this.name = "Assistir Série"
                    this.season = 1
                    this.episode = 1
                }
            )
        }
        
        return episodes
    }
    
    private fun extractEpisodeNumber(element: Element, default: Int): Int {
        return element.attr("data-episode").toIntOrNull()
            ?: element.selectFirst(".episode-number, .num-episode")?.text()?.toIntOrNull()
            ?: Regex("Ep\\.?\\s*(\\d+)").find(element.text())?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("Epis[oó]dio\\s*(\\d+)").find(element.text())?.groupValues?.get(1)?.toIntOrNull()
            ?: default
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return false
    }
}

@CloudstreamPlugin
class SuperflixPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(SuperflixMain())
    }
}
