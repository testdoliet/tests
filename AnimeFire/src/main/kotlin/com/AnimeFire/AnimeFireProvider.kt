package com.AnimeFire

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimeFireProvider: Plugin() {
    override fun load(context: Context) {
        // Registrar a API principal
        registerMainAPI(AnimeFire())
        
        // Registrar extractor como um ExtractorApi personalizado
        registerExtractorAPI(object : ExtractorApi() {
            override val name = "AnimeFire"
            override val mainUrl = "https://animefire.io"
            
            override suspend fun getUrl(
                url: String,
                referer: String?,
                subtitleCallback: (SubtitleFile) -> Unit,
                callback: (ExtractorLink) -> Unit
            ): Boolean {
                return AnimeFireExtractor.extractVideoLinks(url, mainUrl, name) { link ->
                    callback(link)
                }
            }
        })
    }
}
