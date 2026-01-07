package com.Nexflix

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class NexFlixPlugin: Plugin() {
    override fun load(context: Context) {
        // Registra o provedor principal (Site, Busca, Catálogo)
        registerMainAPI(NexFlix())

        // Registra o extractor (Lógica para tirar o m3u8 do player)
        registerExtractorAPI(NexEmbedExtractor())
    }
}
