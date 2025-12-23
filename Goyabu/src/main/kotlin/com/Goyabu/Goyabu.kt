package com.Goyabu

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Goyabu : MainAPI() {
    override var mainUrl = "https://goyabu.io"
    override var name = "Goyabu"
    override val hasMainPage = false
    override var lang = "pt-br"
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Anime)

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 2) return emptyList()
        
        val searchUrl = "$mainUrl/?s=${query.replace(" ", "+")}"
        val document = app.get(searchUrl).document
        
        return document.select("a[href*='/anime/']").mapNotNull { element ->
            val href = element.attr("href") ?: return@mapNotNull null
            val title = element.selectFirst(".title, h3")?.text()?.trim() ?: return@mapNotNull null
            val poster = element.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
            
            newAnimeSearchResponse(title, fixUrl(href)) {
                this.posterUrl = poster
                this.type = TvType.Anime
            }
        }.distinctBy { it.url }.take(15)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        // Metadados
        val title = document.selectFirst("h1")?.text()?.trim() ?: "Sem Título"
        val poster = document.selectFirst(".streamer-poster img")?.attr("src")?.let { fixUrl(it) }
        val synopsis = document.selectFirst(".streamer-sinopse")?.text()?.trim() ?: ""
        
        // Extrair episódios do JavaScript
        val episodes = mutableListOf<Episode>()
        
        // Buscar scripts com episódios
        document.select("script").forEach { script ->
            val content = script.html()
            if (content.contains("allEpisodes")) {
                // Extrair objetos do array - usando padrão mais simples
                val regex = Regex("""\{"id":"(\d+)","episodio":"(\d+)".*?\}""")
                val matches = regex.findAll(content)
                
                matches.forEach { match ->
                    val id = match.groupValues.getOrNull(1)
                    val epNum = match.groupValues.getOrNull(2)?.toIntOrNull() ?: 1
                    
                    if (id != null) {
                        episodes.add(
                            Episode(
                                data = "$mainUrl/$id",
                                name = "Episódio $epNum",
                                episode = epNum,
                                season = 1
                            )
                        )
                    }
                }
                
                // Se não encontrou com o padrão acima, tentar outro
                if (episodes.isEmpty()) {
                    val altRegex = Regex("""id[":]+(\d+).*?episodio[":]+(\d+)""")
                    val altMatches = altRegex.findAll(content)
                    
                    altMatches.forEach { match ->
                        val id = match.groupValues.getOrNull(1)
                        val epNum = match.groupValues.getOrNull(2)?.toIntOrNull() ?: 1
                        
                        if (id != null) {
                            episodes.add(
                                Episode(
                                    data = "$mainUrl/$id",
                                    name = "Episódio $epNum",
                                    episode = epNum,
                                    season = 1
                                )
                            )
                        }
                    }
                }
            }
        }
        
        // Se não encontrou no JS, tentar links numéricos
        if (episodes.isEmpty()) {
            document.select("a[href^='/']").forEach { link ->
                val href = link.attr("href")
                if (href.matches(Regex("""^/\d+/?$"""))) {
                    val epNum = href.replace("/", "").toIntOrNull() ?: return@forEach
                    episodes.add(
                        Episode(
                            data = "$mainUrl$href",
                            name = "Episódio $epNum",
                            episode = epNum,
                            season = 1
                        )
                    )
                }
            }
        }
        
        // Se ainda não encontrou, criar fallback
        if (episodes.isEmpty()) {
            // Criar alguns episódios fallback
            for (i in 1..12) {
                episodes.add(
                    Episode(
                        data = "$url/$i",
                        name = "Episódio $i",
                        episode = i,
                        season = 1
                    )
                )
            }
        }
        
        // Ordenar por número
        val sortedEpisodes = episodes.sortedBy { it.episode }
        
        // Criar resposta
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = synopsis
            
            // Determinar se é dublado ou legendado
            val isDubbed = title.contains("dublado", ignoreCase = true)
            val dubStatus = if (isDubbed) DubStatus.Dubbed else DubStatus.Subbed
            
            // Adicionar episódios
            sortedEpisodes.forEach { episode ->
                addEpisode(
                    Episode(
                        data = episode.data,
                        name = episode.name,
                        season = episode.season,
                        episode = episode.episode,
                        posterUrl = poster
                    ),
                    dubStatus
                )
            }
        }
    }
}
