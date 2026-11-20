plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.shadow)
}

base {
    archivesName.set("kiln-server")
}

dependencies {
    compileOnly(libs.paper.api)
    // bundled into the plugin jar
    implementation(project(":protocol"))
    implementation(project(":server-api"))
    implementation(libs.kotlin.stdlib)

    // pure logic tests (layout, conversion, validation); Paper types only through the API jar, no running server
    testImplementation(libs.paper.api)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }
}

tasks.shadowJar {
    archiveClassifier.set("")
    // let Shadow's KotlinModuleMetadataTransformer see every *.kotlin_module instead of dropping duplicates
    filesMatching("META-INF/*.kotlin_module") { duplicatesStrategy = DuplicatesStrategy.INCLUDE }
    // Kotlin is intentionally NOT relocated: server-api exposes Kotlin types (lambdas, Unit, ...) and dependent
    // plugins (depend: [Kiln]) link against kotlin.* from this jar. Only internal libraries would be relocated.
    mergeServiceFiles()
}

tasks.jar {
    archiveClassifier.set("plain")
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}
