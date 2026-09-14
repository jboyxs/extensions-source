import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "DogeManga"
    versionCode = 1
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        name = "漫畫狗"
        baseUrl = "https://dogemanga.com"
        lang = "zh"
    }

    deeplink {
        path("/m/..*")
        path("/p/..*")
    }
}
