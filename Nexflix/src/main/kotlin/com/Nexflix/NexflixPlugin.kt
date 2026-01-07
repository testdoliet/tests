package com.Nexflix

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class NexFlixPlugin: Plugin() {
    override fun load(context: Context) {
        
        registerMainAPI(NexFlix())
    }
}
