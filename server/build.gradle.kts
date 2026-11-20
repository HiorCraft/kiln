plugins {
    id("kiln.kotlin")
    id("kiln.test")
    id("kiln.shadow")
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
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }
}

