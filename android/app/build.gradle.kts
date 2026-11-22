plugins {
    id("com.android.application")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "io.github.vishalsng112.slatecam"
    compileSdk = 37

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "io.github.vishalsng112.slatecam"
        minSdk = 33
        targetSdk = 37
        // Version code and name come from pubspec.yaml. CI overrides the code from git tags.
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    buildTypes {
        release {
            // TODO(phase-9): replace with the project release key. See docs/adr/0001-identity.md.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

flutter {
    source = "../.."
}

dependencies {
    implementation(project(":bridge"))
    implementation(project(":core:common"))
    implementation(project(":feature:capture"))
    implementation(project(":feature:playback"))
    implementation(project(":feature:devicereport"))
}
