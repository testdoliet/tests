package com.SuperFlix

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class SuperFlixProvider : BasePlugin() {
    override fun load() {
        registerMainAPI(SuperFlix())
    }
}
