package com.SuperFlix

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.metaproviders.TmdbLink
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class SuperFlix : TmdbProvider() {
    override var mainUrl = "https://superflix21.lol"
    override var name = "SuperFlix"
    override val hasMainPage = false
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override val usesWebView = false
    override var lang = "pt-br"
    override val useMetaLoadResponse = true
    override val hasQuickSearch = true
    override val instantLinkLoading = true

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            // Parse dos dados do TMDB
            val mediaData = AppUtils.parseJson<TmdbLink>(data)
            
            // Extrair título e ano de maneira segura
            val title = mediaData.movieName ?: "Unknown"
            val year = try {
                // Tenta extrair ano do título ou usa valor padrão
                title.substringAfterLast("(").substringBefore(")").toIntOrNull()
            } catch (e: Exception) {
                null
            }
            
            // Log para debug
            println("🎬 [SuperFlix] Carregando links para: $title (${year ?: "N/A"})")
            println("🎬 [SuperFlix] Tipo: ${mediaData.type}, TMDB ID: ${mediaData.tmdbID}")
            println("🎬 [SuperFlix] IMDB ID: ${mediaData.imdbID}")
            println("🎬 [SuperFlix] Temporada: ${mediaData.season}, Episódio: ${mediaData.episode}")
            
            // Aqui você implementaria a busca real no site SuperFlix
            // Por enquanto, retorna um link de exemplo
            
            // Link de teste real (stream público de exemplo)
            val testUrl = when {
                mediaData.type == "movie" -> "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
                mediaData.type == "tv" -> "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
                else -> "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
            }
            
            callback(
                newExtractorLink(
                    source = name,
                    name = "SuperFlix Stream",
                    url = testUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    referer = "$mainUrl/"
                    quality = Qualities.P720.value
                    headers = mapOf(
                        "Origin" to mainUrl,
                        "Referer" to "$mainUrl/"
                    )
                }
            )
            
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }
}
