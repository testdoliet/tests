import java.util.Properties

version = 1

plugins {
    kotlin("android")
    id("com.android.library")
}

android {
    buildFeatures {
        buildConfig = true
    }
    namespace = "com.AnimeFire"
    compileSdk = 33

    defaultConfig {
        minSdk = 24
        targetSdk = 33

        // Ler de múltiplas fontes, na ordem de prioridade:
        // 1. Parâmetros do Gradle (-P)
        // 2. Variáveis de ambiente
        // 3. local.properties
        // 4. Valor dummy como fallback
        
        val tmdbApiKey = project.findProperty("TMDB_API_KEY") as? String
            ?: System.getenv("TMDB_API_KEY")
            ?: getLocalProperty("TMDB_API_KEY")
            ?: "dummy_api_key"
        
        val tmdbAccessToken = project.findProperty("TMDB_ACCESS_TOKEN") as? String
            ?: System.getenv("TMDB_ACCESS_TOKEN")
            ?: getLocalProperty("TMDB_ACCESS_TOKEN")
            ?: "dummy_access_token"
        
        println("🔑 [BUILD] TMDB_API_KEY configurada: ${if (tmdbApiKey == "dummy_api_key") "USANDO DUMMY" else "✅ CONFIGURADA"}")
        println("🔑 [BUILD] TMDB_ACCESS_TOKEN configurada: ${if (tmdbAccessToken == "dummy_access_token") "USANDO DUMMY" else "✅ CONFIGURADA"}")
        
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
    description = "Site de animes em português com conteúdo do AniList e TMDB"
    language = "pt-br"
    authors = listOf("lawlietbr")
    status = 1
    tvTypes = listOf("Animes")
    iconUrl = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAABwAAAAcCAMAAABF0y+mAAAARVBMVEVHcEwcHBwbGxsbGxsbGxsbFxYbLDAbAgAbGxsegZoel7UbEQwcPUYcT10h3/8daX0h2f8h0fwfqswgud8gxu4bGxscHBzAP0bqAAAAF3RSTlMAX+j/2v///+7////////////////JQ/um5bwAAAEsSURBVHgBddAHAsIgEETR6LiMsLAsRe9/U3svPz0vlWVZrfGz9epk+Ntq2eCZBL7iZtk+BGBMr7q+o4YcoMUiv5G1KKDV2zcyZr1s3Kt+IGMlTrG7J76j5EGcEvH5hRxFoQCjnaZ3lOyERgXbqJblDRkHwVSZDKPzPh437Ilg79FytiAIcsYsV5xnnG6FvRNMZ5XyRMnukWpVr2+BFr3iGGT0QS2Wz2gn0HrD5OSwolotkZgtEYxX1GqVsxES3Ef3NAal1CuCPtknASZzm5yD7IIbRovZVACWWlisMybeEJxWa1QAquT0WhtxR81uCdQz5ektWZAHgrm5p5pL7e7NW1Y8ESrdH3VVvCKEOc2zzJQpuOEOuDM1BOWdgN2yx0sieGm/LPvd5me7/XIEXYwWI/5u4OgAAAAASUVORK5CYII="
    isCrossPlatform = true 
}
