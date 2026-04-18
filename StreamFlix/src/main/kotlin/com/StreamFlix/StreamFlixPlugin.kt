package com.StreamFlix

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class StreamFlixProvider : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(StreamFlix())
        
        this.openSettings = { ctx ->
            val activity = ctx as AppCompatActivity
            val frag = StreamFlixSettingsFragment()
            frag.show(activity.supportFragmentManager, "StreamFlixSettings")
        }
    }

    companion object {
        // Categorias disponíveis com seus IDs
        val ALL_MOVIE_CATEGORIES = mapOf(
            "73" to "4K",
            "69" to "AÇÃO",
            "70" to "COMÉDIA",
            "74" to "DRAMA",
            "72" to "TERROR",
            "98" to "FICÇÃO CIENTÍFICA"
        )

        val ALL_SERIES_CATEGORIES = mapOf(
            "107" to "Netflix",
            "118" to "HBO Max",
            "109" to "Apple TV",
            "112" to "Crunchyroll",
            "126" to "Doramas"
        )

        // Funções para salvar/ler categorias ativas
        fun getActiveMovieCategories(): Map<String, String> {
            val activeIds = getKey("STREAMFLIX_ACTIVE_MOVIES")?.split(",")?.toSet() ?: ALL_MOVIE_CATEGORIES.keys
            return ALL_MOVIE_CATEGORIES.filterKeys { activeIds.contains(it) }
        }

        fun getActiveSeriesCategories(): Map<String, String> {
            val activeIds = getKey("STREAMFLIX_ACTIVE_SERIES")?.split(",")?.toSet() ?: ALL_SERIES_CATEGORIES.keys
            return ALL_SERIES_CATEGORIES.filterKeys { activeIds.contains(it) }
        }

        fun saveActiveMovieCategories(ids: Set<String>) {
            setKey("STREAMFLIX_ACTIVE_MOVIES", ids.joinToString(","))
        }

        fun saveActiveSeriesCategories(ids: Set<String>) {
            setKey("STREAMFLIX_ACTIVE_SERIES", ids.joinToString(","))
        }
    }
}
