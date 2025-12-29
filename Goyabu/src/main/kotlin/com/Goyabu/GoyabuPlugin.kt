package com.Goyabu

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class GoyabuProviderPlugin: Plugin() {
    override fun load(context: Context) {
        
        registerMainAPI(Goyabu)())
    }
}
