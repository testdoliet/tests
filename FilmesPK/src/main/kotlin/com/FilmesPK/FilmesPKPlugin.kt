package com.FilmesPK

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FilmesPKPlugin : Plugin() {
    override fun load(context: Context) { 
        registerMainAPI(FilmesPK())
    }
}
