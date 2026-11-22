plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

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

// TODO(phase-2): add the Flutter embedding artifact for Pigeon-generated code.
// The Flutter dependency is allowed in this module only (docs/adr/0003-modules.md).
dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:profile"))
}
