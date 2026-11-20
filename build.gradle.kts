// Conventions live in build-logic (kiln.kotlin, kiln.test, kiln.library, kiln.shadow, kiln.publish, ...).
plugins {
    alias(libs.plugins.loom) apply false
}

subprojects {
    group = rootProject.group
    version = rootProject.version
}
