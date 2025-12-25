package com.SuperFlix

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class SuperFlixProvider : Plugin() {
    override fun load(context: Context) {
        // Registra o provider principal
        registerMainAPI(SuperFlix())
        
        // REGISTRA O EXTRACTOR (agora é uma classe que extende ExtractorApi)
        registerExtractorAPI(SuperFlixYoutubeExtractor())
        
        println("✅ SuperFlix Provider carregado com YouTube Extractor")
    }
}
