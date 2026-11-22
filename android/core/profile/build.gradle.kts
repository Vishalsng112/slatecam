// Pure Kotlin/JVM on purpose: colour science must be testable without a device.
// Do not add the Android plugin or any android.* import here. See docs/adr/0003-modules.md.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}
