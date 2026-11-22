pluginManagement {
    val flutterSdkPath =
        run {
            val properties = java.util.Properties()
            file("local.properties").inputStream().use { properties.load(it) }
            val flutterSdkPath = properties.getProperty("flutter.sdk")
            require(flutterSdkPath != null) { "flutter.sdk not set in local.properties" }
            flutterSdkPath
        }

    includeBuild("$flutterSdkPath/packages/flutter_tools/gradle")

    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("dev.flutter.flutter-plugin-loader") version "1.0.0"
    id("com.android.application") version "9.1.0" apply false
    id("com.android.library") version "9.1.0" apply false
    id("org.jetbrains.kotlin.android") version "2.4.0" apply false
    id("org.jetbrains.kotlin.jvm") version "2.4.0" apply false
}

rootProject.name = "slatecam"

// Host app
include(":app")

// Bridge: Pigeon + EventChannel contracts. The only module that may talk to Flutter.
include(":bridge")

// Core: no Flutter, no feature dependencies. See docs/adr/0003-modules.md
include(":core:common")
include(":core:profile")   // pure Kotlin/JVM, no android.* imports
include(":core:probe")
include(":core:camera")
include(":core:gpu")
include(":core:codec")
include(":core:storage")

// Features: depend on core + bridge, never on each other.
include(":feature:capture")
include(":feature:playback")
include(":feature:devicereport")
