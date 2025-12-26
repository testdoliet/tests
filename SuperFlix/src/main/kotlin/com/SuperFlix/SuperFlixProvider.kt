package com.SuperFlix

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class SuperFlixProvider : Plugin() {
    override fun load() {
        registerMainAPI(SuperFlix())
        registerExtractorAPI(YouTubeTrailerExtractor())  // ← NOME EXATO DA CLASSE
    }
}
