package com.SuperFlix

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class SuperFlixProvider : Plugin() {
        registerMainAPI(SuperFlix())
        registerExtractorAPI(YouTubeTrailerExtractor())
    }
}
        
