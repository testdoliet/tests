// Esta configuração é para compilação usando a API oficial do Cloudstream
plugins {
    // Aplica o plugin de provedor do Cloudstream
    id("com.lagradost.cloudstream3.plugin")
    // Aplica o plugin Kotlin/JVM
    kotlin("jvm")
}

// Configuração de metadados do provedor
cloudstream {
    version = 1 

    description = "SuperFlix - Filmes e Séries em Português"
    language = "pt-br"
    authors = listOf("euluan1912")
    status = 1 

    // Tipos de conteúdo suportados
    tvTypes = listOf("Movie", "TvSeries") 

    iconUrl = "[https://superflix20.lol/favicon.ico](https://superflix20.lol/favicon.ico)" 

    isCrossPlatform = true
}

// BLOCO CRÍTICO: DEPENDÊNCIAS
dependencies {
    // Dependências principais da API do CloudStream
    implementation("com.lagradost.cloudstream3:cloudstream-api:1.2.0")
    implementation("com.lagradost.cloudstream3:cloudstream-base-api:1.2.0")

    // Dependência para as classes de URL e requisições
    implementation("com.lagradost.cloudstream3:cloudstream-url-api:1.2.0")
}
