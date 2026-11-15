import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Persistent signing key (see ./dw keygen). Lives outside the repo; mounted by ./dw.
val signingProps: Properties? = System.getenv("TICKERS_SIGNING_DIR")
    ?.let { file(it).resolve("keystore.properties") }
    ?.takeIf { it.exists() }
    ?.let { f -> Properties().apply { f.inputStream().use(::load) }.also { it["dir"] = f.parent } }

android {
    namespace = "com.tickers.app"
    compileSdk = 37
    buildToolsVersion = "37.0.0" // preinstalled in the Docker image

    defaultConfig {
        applicationId = "com.tickers.app"
        minSdk = 26
        targetSdk = 37
        // Bumped by ./release.sh. versionCode must only ever increase.
        versionCode = providers.gradleProperty("tickers.versionCode").get().toInt()
        versionName = providers.gradleProperty("tickers.versionName").get()
        // "owner/repo" whose GitHub Releases the in-app updater checks. Blank hides the updater.
        buildConfigField(
            "String", "GITHUB_REPO",
            "\"${providers.gradleProperty("tickers.githubRepo").getOrElse("")}\"",
        )
    }

    signingConfigs {
        if (signingProps != null) {
            create("tickers") {
                storeFile = file(signingProps["dir"] as String).resolve(signingProps["storeFile"] as String)
                storePassword = signingProps["storePassword"] as String
                keyAlias = signingProps["keyAlias"] as String
                keyPassword = signingProps["keyPassword"] as String
            }
        }
    }

    buildTypes {
        // Debug and release share one key so either can be installed over the other.
        val key = signingConfigs.findByName("tickers")
        debug {
            if (key != null) signingConfig = key
        }
        release {
            isMinifyEnabled = false
            signingConfig = key
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// A release signed with anything but the persistent key could never update an install.
val hasSigningKey = signingProps != null
tasks.matching { it.name == "assembleRelease" || it.name == "packageRelease" }.configureEach {
    val keyPresent = hasSigningKey // local copy: the configuration cache can't capture script state
    doFirst {
        check(keyPresent) {
            "Signing key missing: run ./dw keygen (or restore your backup into ~/.config/tickers-signing)"
        }
    }
}

ksp {
    // Exported Room schemas: the reference that hand-written migrations are tested against.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.vico.compose.m3)

    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    implementation(libs.work.runtime)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
