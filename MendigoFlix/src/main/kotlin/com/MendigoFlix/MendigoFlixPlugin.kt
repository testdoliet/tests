package com.MendigoFlix

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MendigoFlixProvider : Plugin() {
    override fun load(context: Context) {
        // Registra o provider principal (sua classe SuperFlix que estende MainAPI)
        registerMainAPI(MendigoFlix())

        // Registra o extractor de YouTube
        registerExtractorAPI(YouTubeTrailerExtractor())  // ← NOME EXATO DA CLASSE DO EXTRACTOR

        println("✅ SuperFlix Provider carregado com YouTube Extractor")
    }
}
