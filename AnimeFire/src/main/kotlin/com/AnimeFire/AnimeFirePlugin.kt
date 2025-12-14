package com.AnimeFire

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimeFirePlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnimeFire())
    }
}
