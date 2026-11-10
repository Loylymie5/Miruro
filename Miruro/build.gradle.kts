dependencies {
    cloudstream("com.lagradost:cloudstream3:pre-release")
    implementation(kotlin("stdlib"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

version = 1

cloudstream {
    description = "Standalone Miruro anime catalogue and source extension."
    authors = listOf("OpenAI")
    status = 3
    tvTypes = listOf("Anime")
    language = "en"
    iconUrl = "https://www.miruro.cx/favicon.ico"
}

android { buildFeatures { buildConfig = true } }
