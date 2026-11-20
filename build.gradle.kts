// Conventions live in build-logic (kiln.kotlin, kiln.test, kiln.library, kiln.shadow, kiln.publish, ...).
plugins {
    base
    alias(libs.plugins.loom) apply false
}

subprojects {
    group = rootProject.group
    version = rootProject.version
}

// the Gradle plugin (included build) is tested with the rest
tasks.named("check") {
    dependsOn(gradle.includedBuild("gradle-plugin").task(":check"))
}
