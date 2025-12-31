package com.SuperFlix

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class HypeflixProvider : Plugin() {
    override fun load(context: Context) {

        registerMainAPI(Hypeflix())
    }
}
