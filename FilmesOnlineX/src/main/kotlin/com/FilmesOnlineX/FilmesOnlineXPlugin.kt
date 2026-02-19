package com.FilmesOnlineX

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FilmesOnlineXProvider : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(FilmesOnlineX())
    }
}
