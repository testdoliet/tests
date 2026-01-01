package com.CineAgora

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

object CineAgoraExtractor {
    private const val TAG = "CineAgoraExtractor"
    private const val BASE_PLAYER = "https://watch.brplayer.cc"
    private const val REFERER_CINEAGORA = "https://cineagora.net/"
    
    /**
     * Função principal chamada pelo provider.
     * Detecta se é filme ou série e extrai os links HLS.
     */
    suspend fun extractVideoLinks(
        url: String,        // URL completa da página no CineAgora (filme ou série)
        name: String,      // Nome do filme/série
        isSeries: Boolean,  // true se for série, false se for filme
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[$TAG] Extraindo links para URL: $url")
        println("[$TAG] É série: $isSeries, Nome: $name")
        
        return if (isSeries) {
            extractSeriesEpisodes(url, name, callback)
        } else {
            extractMovieLink(url, name, callback)
        }
    }

    // ========================
    // 1. EXTRAÇÃO PARA FILMES
    // ========================

    private suspend fun extractMovieLink(
        movieUrl: String,
        title: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            println("[$TAG] Extraindo link do filme: $movieUrl")
            val doc = app.get(movieUrl).document

            // Procura o iframe ou link do player brplayer.cc
            println("[$TAG] Procurando iframe do player...")
            val playerUrl = doc.selectFirst("iframe[src*=\"watch.brplayer.cc\"]")?.attr("src")
            
            if (playerUrl == null) {
                println("[$TAG] Iframe não encontrado, procurando alternativas...")
                // Tenta encontrar em links
                val link = doc.select("a[href*=\"watch.brplayer.cc\"]").firstOrNull()?.attr("href")
                if (link != null) {
                    println("[$TAG] Link encontrado: $link")
                    return extractHlsFromPlayerPage(link, title, callback)
                }
                
                // Tenta encontrar em scripts
                val scripts = doc.select("script")
                for (script in scripts) {
                    val scriptContent = script.html()
                    if (scriptContent.contains("watch.brplayer.cc")) {
                        println("[$TAG] Script contém brplayer.cc, analisando...")
                        val regex = Regex("""(https?://watch\.brplayer\.cc/watch/[A-Z0-9]+)""")
                        val match = regex.find(scriptContent)
                        if (match != null) {
                            val foundUrl = match.groupValues[1]
                            println("[$TAG] URL encontrada em script: $foundUrl")
                            return extractHlsFromPlayerPage(foundUrl, title, callback)
                        }
                    }
                }
                
                println("[$TAG] Nenhum player encontrado para filme: $movieUrl")
                return false
            }

            println("[$TAG] Player URL do filme encontrada: $playerUrl")
            return extractHlsFromPlayerPage(playerUrl, title, callback)
        } catch (e: Exception) {
            println("[$TAG] Erro ao extrair link do filme: ${e.message}")
            e.printStackTrace()
            return false
        }
    }

    // ==========================
    // 2. EXTRAÇÃO PARA SÉRIES
    // ==========================

    private suspend fun extractSeriesEpisodes(
        seriesUrl: String,
        seriesTitle: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            println("[$TAG] Extraindo episódios da série: $seriesUrl")
            
            // Extrai o slug da série (ex: "pluribus", "dona-de-mim")
            val slug = extractSlugFromUrl(seriesUrl)
            println("[$TAG] Slug extraído: $slug")
            
            val apiUrl = "$BASE_PLAYER/fetch_series_data.php?seriesSlug=$slug"
            println("[$TAG] Buscando episódios na API: $apiUrl")

            val response = app.get(apiUrl, referer = "$BASE_PLAYER/tv/$slug")
            println("[$TAG] Resposta da API: ${response.code}")
            
            val json = tryParseJson<Map<String, Any>>(response.text)
            if (json == null) {
                println("[$TAG] JSON da API inválido ou vazio")
                return false
            }
            
            val seasons = json["seasons"] as? Map<String, List<Map<String, Any>>>
            if (seasons == null) {
                println("[$TAG] Nenhuma temporada encontrada na API")
                return false
            }
            
            println("[$TAG] Encontradas ${seasons.size} temporadas")

            var success = false
            var totalEpisodes = 0

            seasons.forEach { (seasonNum, episodes) ->
                println("[$TAG] Temporada $seasonNum com ${episodes.size} episódios")
                
                episodes.forEach { ep ->
                    val epNum = ep["episode_number"]?.toString()?.padStart(2, '0') ?: "??"
                    val videoSlug = ep["video_slug"]?.toString()
                    
                    if (videoSlug != null) {
                        totalEpisodes++
                        val episodeTitle = "$seriesTitle S${seasonNum.padStart(2,'0')}E$epNum"
                        val playerUrl = "$BASE_PLAYER/watch/$videoSlug"
                        
                        println("[$TAG] Processando episódio $epNum: $playerUrl")
                        
                        if (extractHlsFromPlayerPage(playerUrl, episodeTitle, callback)) {
                            success = true
                        }
                    }
                }
            }
            
            println("[$TAG] Processados $totalEpisodes episódios no total")
            return success
            
        } catch (e: Exception) {
            println("[$TAG] Erro ao extrair episódios: ${e.message}")
            e.printStackTrace()
            return false
        }
    }
    
    private fun extractSlugFromUrl(url: String): String {
        // Exemplos:
        // https://cineagora.net/series-online/pluribus-2023.html → pluribus
        // https://cineagora.net/series-online/dona-de-mim.html → dona-de-mim
        
        val filename = url.substringAfterLast("/").substringBefore(".")
        // Remove o ano se existir (ex: pluribus-2023 → pluribus)
        return filename.replace(Regex("-\\d{4}$"), "")
    }

    // =============================================
    // 3. FUNÇÃO COMUM: Extrai HLS de uma página /watch/XXXX
    // =============================================

    private suspend fun extractHlsFromPlayerPage(
        playerUrl: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            println("[$TAG] Extraindo HLS de: $playerUrl")
            
            val html = app.get(playerUrl, referer = REFERER_CINEAGORA).text
            println("[$TAG] Página do player carregada (${html.length} chars)")

            // Extrai uid, md5 e id do objeto JavaScript "var video = {...}"
            val uid = Regex("\"uid\"\\s*:\\s*\"(\\d+)\"").find(html)?.groupValues?.get(1)
            val md5 = Regex("\"md5\"\\s*:\\s*\"([a-f0-9]{32})\"").find(html)?.groupValues?.get(1)
            val videoId = Regex("\"id\"\\s*:\\s*\"(\\d+)\"").find(html)?.groupValues?.get(1)

            println("[$TAG] Valores extraídos - UID: '$uid', MD5: '$md5', ID: '$videoId'")
            
            if (uid == null || md5 == null || videoId == null) {
                println("[$TAG] Falha ao extrair uid/md5/id")
                return false
            }

            val masterUrl = "$BASE_PLAYER/m3u8/$uid/$md5/master.txt?s=1&id=$videoId&cache=1"
            println("[$TAG] Master URL gerada: $masterUrl")

            val headers = mapOf(
                "Referer" to playerUrl,
                "Origin" to BASE_PLAYER,
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36"
            )

            // Gera links com qualidades automáticas (720p, 480p, 240p)
            println("[$TAG] Gerando links M3U8...")
            val links = M3u8Helper.generateM3u8(
                source = "CineAgora",
                streamUrl = masterUrl,
                referer = playerUrl,
                headers = headers
            )

            if (links.isNotEmpty()) {
                println("[$TAG] ${links.size} links M3U8 gerados com sucesso!")
                links.forEach { link ->
                    println("[$TAG] Link: ${link.url} (Qualidade: ${link.quality})")
                    callback(link)
                }
                return true
            }

            println("[$TAG] M3u8Helper não gerou links, usando fallback...")
            // Fallback: link único
            val fallbackLink = newExtractorLink(
                source = "CineAgora",
                name = name,
                url = masterUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = playerUrl
                this.quality = Qualities.Unknown.value
                this.headers = headers
            }
            
            callback(fallbackLink)
            println("[$TAG] Link fallback criado com sucesso")
            return true

        } catch (e: Exception) {
            println("[$TAG] Erro ao extrair HLS: ${e.message}")
            e.printStackTrace()
            return false
        }
    }
}
