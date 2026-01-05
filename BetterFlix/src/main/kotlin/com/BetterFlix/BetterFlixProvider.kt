package com.BetterFlix

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class BetterFlixProvider : Plugin() {
    override fun load(context: Context) {
        // Registrar apenas a API principal
        registerMainAPI(BetterFlix())
    }
}
