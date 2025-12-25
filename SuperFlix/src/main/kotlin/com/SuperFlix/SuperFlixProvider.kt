package com.SuperFlix

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class SuperFlixPlugin : Plugin() {
    override fun load(context: Context) {
        // Registra o provider principal
        registerMainAPI(SuperFlix())
        
        // REGISTRA O EXTRACTOR DE YOUTUBE
        registerExtractorAPI(SuperFlixYoutubeExtractor)
        
        println("✅ SuperFlix Plugin carregado com YouTube Extractor")
    }
}
