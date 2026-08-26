buildscript {
    dependencies {
        // AGP 9 uses built-in Kotlin. Pin the Kotlin Gradle Plugin consumed by AGP
        // so the Compose compiler plugin can use the same Kotlin version.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.21")
    }
}

plugins {
    id("com.android.application") version "9.2.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
}
