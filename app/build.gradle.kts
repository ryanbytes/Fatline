plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val ciRunNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
// GITHUB_RUN_NUMBER is scoped to each workflow. Android CI run #266 and the
// first release run #1 produced codes 10266 and 10001 respectively, preventing
// users from updating in place. GITHUB_RUN_ID is globally ordered across jobs
// and workflows; divide it to stay comfortably inside Android's versionCode range.
val ciVersionCode = System.getenv("GITHUB_RUN_ID")?.toLongOrNull()?.let { runId ->
    val code = runId / 1_000L
    require(code in 1L..2_100_000_000L) { "GitHub run ID exceeds Android versionCode range" }
    code.toInt()
}
val releaseVersionName = System.getenv("FATLINE_VERSION_NAME")?.trim()?.takeIf { it.isNotEmpty() }
val ciKeystorePath = System.getenv("FATLINE_CI_KEYSTORE")

android {
    namespace = "dev.scanrelay.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.scanrelay.app"
        minSdk = 26
        targetSdk = 36
        versionCode = ciVersionCode ?: 2
        versionName = releaseVersionName ?: ciRunNumber?.let { "0.2.0-ci.$it" } ?: "0.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    if (!ciKeystorePath.isNullOrBlank()) {
        signingConfigs {
            create("ciDebug") {
                storeFile = file(ciKeystorePath)
                storePassword = System.getenv("FATLINE_CI_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("FATLINE_CI_KEY_ALIAS")
                keyPassword = System.getenv("FATLINE_CI_KEY_PASSWORD")
            }
        }
        buildTypes {
            getByName("debug") {
                signingConfig = signingConfigs.getByName("ciDebug")
            }
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    // Compose 1.12 requires compileSdk 37. The stable Android SDK available to
    // CI is API 36, so pin the June BOM (core Compose 1.11.3).
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.media3:media3-exoplayer:1.11.0")
    implementation("androidx.media3:media3-session:1.11.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation(platform("com.squareup.okhttp3:okhttp-bom:5.3.0"))
    implementation("com.squareup.okhttp3:okhttp")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
