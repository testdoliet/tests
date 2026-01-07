package com.Nexflix

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class NexflixPlugin : Plugin() {
    override fun load(context: Context) {
        // CORREÇÃO: Registrar a classe principal NexFlix (não NexEmbedExtractor)
        registerMainAPI(NexFlix())
    }
}
