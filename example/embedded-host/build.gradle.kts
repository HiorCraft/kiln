// Test host for kiln-server-embedded: a plugin that shades Kiln and Kotlin the way Surf-API does and provides "Kiln"
plugins {
    id("kiln.paper-plugin")
}

base {
    archivesName.set("kiln-embedded-host")
}

evaluationDependsOn(":server:runtime")
val embeddedKiln = project(":server:runtime").tasks.named("embeddedJar")

dependencies {
    compileOnly(libs.paper.api)
    // shaded: the embedded Kiln jar (runtime, API, protocol) and the Kotlin runtime the host provides
    implementation(files(embeddedKiln))
    implementation(libs.kotlin.stdlib)
}

paper {
    name = "KilnEmbeddedHost"
    main = "dev.kiln.example.host.HostPlugin"
    bootstrapper = "dev.kiln.example.host.HostBootstrap"
    description = "Test host that shades Kiln (kiln-server-embedded)"
    // plugins that depend on "Kiln" load against this one
    provides = listOf("Kiln")
}
