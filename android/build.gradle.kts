import org.gradle.api.artifacts.ProjectDependency

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

val newBuildDir: Directory =
    rootProject.layout.buildDirectory
        .dir("../../build")
        .get()
rootProject.layout.buildDirectory.value(newBuildDir)

subprojects {
    val newSubprojectBuildDir: Directory = newBuildDir.dir(project.name)
    project.layout.buildDirectory.value(newSubprojectBuildDir)
}
subprojects {
    project.evaluationDependsOn(":app")
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}

// --- Module graph enforcement (docs/adr/0003-modules.md) -------------------
// Dependencies point downward only: app -> feature -> core.
// Any project dependency not listed here fails the build.
val moduleAllowlist: Map<String, Set<String>> = mapOf(
    ":core:common" to emptySet(),
    ":core:profile" to emptySet(),
    ":core:probe" to setOf(":core:common"),
    ":core:camera" to setOf(":core:common"),
    ":core:gpu" to setOf(":core:common"),
    ":core:codec" to setOf(":core:common"),
    ":core:storage" to setOf(":core:common"),
    ":bridge" to setOf(":core:common", ":core:profile"),
    ":feature:capture" to setOf(
        ":core:common",
        ":core:profile",
        ":core:probe",
        ":core:camera",
        ":core:gpu",
        ":core:codec",
        ":core:storage",
        ":bridge",
    ),
    ":feature:playback" to setOf(
        ":core:common",
        ":core:profile",
        ":core:storage",
        ":bridge",
    ),
    ":feature:devicereport" to setOf(
        ":core:common",
        ":core:probe",
        ":bridge",
    ),
    ":app" to setOf(
        ":core:common",
        ":bridge",
        ":feature:capture",
        ":feature:playback",
        ":feature:devicereport",
    ),
)

val checkModuleGraph = tasks.register("checkModuleGraph") {
    group = "verification"
    description = "Fails when a module depends on something outside its allowlist."
    doLast {
        val violations = mutableListOf<String>()
        for (module in subprojects) {
            // Skip intermediate projects Gradle creates for nested paths (":feature")
            // and anything that is not a real module.
            if (!module.buildFile.exists()) continue
            val allowed = moduleAllowlist[module.path]
            if (allowed == null) {
                violations += "${module.path} -> (module is not in the allowlist)"
                continue
            }
            for (configuration in module.configurations) {
                for (dependency in configuration.dependencies
                    .withType(ProjectDependency::class.java)) {
                    // AGP wires a module against itself for test classpaths.
                    if (dependency.path == module.path) continue
                    if (dependency.path !in allowed) {
                        violations += "${module.path} -> ${dependency.path}" +
                            " (${configuration.name})"
                    }
                }
            }
        }
        if (violations.isNotEmpty()) {
            val unique = violations.distinct().sorted().joinToString("\n  ")
            throw GradleException(
                "Forbidden module dependencies. See docs/adr/0003-modules.md:\n  $unique",
            )
        }
    }
}

gradle.projectsEvaluated {
    project(":app").tasks.matching { it.name == "preBuild" }.configureEach {
        dependsOn(checkModuleGraph)
    }
}
