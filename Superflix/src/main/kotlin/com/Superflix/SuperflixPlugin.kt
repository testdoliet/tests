package com.Superflix

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SuperflixProvider : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(com.lagradost.cloudstream3.superflix.SuperflixProvider())
    }
}
