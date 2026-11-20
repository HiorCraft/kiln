// The shadow jar is the main artifact (no classifier); the thin jar is "plain"
plugins {
    java
    id("com.gradleup.shadow")
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    // let Shadow's KotlinModuleMetadataTransformer see every *.kotlin_module instead of dropping duplicates
    filesMatching("META-INF/*.kotlin_module") { duplicatesStrategy = DuplicatesStrategy.INCLUDE }
    mergeServiceFiles()
}

tasks.named<Jar>("jar") {
    archiveClassifier.set("plain")
}

tasks.named("assemble") {
    dependsOn("shadowJar")
}
