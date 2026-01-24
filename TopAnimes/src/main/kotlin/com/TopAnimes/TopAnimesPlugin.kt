package com.TopAnimes

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudStreamPlugin
class TopAnimesPlugin: Plugin() {
    override fun load(context: Context) {
        
        registerMainAPI(TopAnimes())
    }
}
