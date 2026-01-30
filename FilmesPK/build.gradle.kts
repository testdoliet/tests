import java.util.Properties

plugins {
    kotlin("android")
    id("com.android.library")
}

android {
   buildFeatures {
        buildConfig = true
    }
    namespace = "com.FilmesPK"
    compileSdk = 33

    defaultConfig {
        minSdk = 24
        targetSdk = 33

        val tmdbApiKey = project.findProperty("TMDB_API_KEY") as? String
            ?: System.getenv("TMDB_API_KEY")
            ?: getLocalProperty("TMDB_API_KEY")
            ?: "dummy_api_key"

        val tmdbAccessToken = project.findProperty("TMDB_ACCESS_TOKEN") as? String
            ?: System.getenv("TMDB_ACCESS_TOKEN")
            ?: getLocalProperty("TMDB_ACCESS_TOKEN")
            ?: "dummy_access_token"

        buildConfigField("String", "TMDB_API_KEY", "\"$tmdbApiKey\"")
        buildConfigField("String", "TMDB_ACCESS_TOKEN", "\"$tmdbAccessToken\"")
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

fun getLocalProperty(key: String): String? {
    val localProperties = Properties()
    val localPropertiesFile = project.rootProject.file("local.properties")

    return if (localPropertiesFile.exists()) {
        localProperties.load(localPropertiesFile.inputStream())
        localProperties.getProperty(key)
    } else {
        null
    }
}

cloudstream {
    version = 1
    description = "Filmes PK. Site criado com o objetivo de reunir informações sobre séries e filmes de TV gratuitamente sem intuito de fins lucrativos. Rave PK"
    language = "pt-br"
    authors = listOf("lawlietbr")
    status = 1
    tvTypes = listOf("Movies", "Series", "Animes")
    iconUrl = "https://blogger.googleusercontent.com/img/a/AVvXsEgNF0FEoFIlzsxkDmJIqwACYHLFKf0M9XoinXL7dxraDkF8IO5hTgVrZid-odbrtRWS_4P2bdgXx-050Wy2MrvN3CCAdWGvUOd__wIj1feE-m0daGOr9VUReDdNAjeIViNEiW3F4-sUpb6OVfa6nsSDm3g3uj6dCpaStQH2lSKQ_4YI7IOOt_f6H1LrGwE=s817"
    isCrossPlatform = true
    requiresResources = true
}

dependencies {
    val cloudstream by configurations
    cloudstream("com.lagradost:cloudstream3:pre-release")
}
