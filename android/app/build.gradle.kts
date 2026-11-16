import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Ключ API Яндекс Расписаний лежит в local.properties (строка yandexRaspKey=...),
// этот файл не хранится в git. Без ключа приложение собирается, но электрички не грузит.
val yandexRaspKey: String = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}.getProperty("yandexRaspKey", "").trim()

require(yandexRaspKey.all { it.isLetterOrDigit() || it == '-' }) {
    "yandexRaspKey в local.properties должен состоять из букв, цифр и дефисов"
}

android {
    namespace = "io.github.phtdl3.schedulewidget"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.phtdl3.schedulewidget"
        // Android 12: телефон один и новый, поддержка старых версий не нужна.
        minSdk = 31
        // Android 16 — версия на телефоне, для неё поведение проверено.
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"

        buildConfigField("String", "YANDEX_RASP_KEY", "\"$yandexRaspKey\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.work.runtime.ktx)
}
