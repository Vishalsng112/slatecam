import java.util.Properties

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// The bridge is the only module allowed to touch Flutter. It compiles against
// the embedding jar; the app supplies the real classes at runtime.
val flutterSdkPath: String = Properties().apply {
    file("../local.properties").inputStream().use { load(it) }
}.getProperty("flutter.sdk")

android {
    namespace = "io.github.vishalsng112.slatecam.bridge"
    compileSdk = 37

    defaultConfig {
        minSdk = 33
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    compileOnly(files("$flutterSdkPath/bin/cache/artifacts/engine/android-arm64/flutter.jar"))

    implementation(project(":core:common"))
    implementation(project(":core:profile"))
    implementation(project(":core:camera"))
    implementation(project(":core:gpu"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
