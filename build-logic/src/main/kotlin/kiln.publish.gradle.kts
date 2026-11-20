// Maven publication to mavenLocal; -PkilnPublishUrl=<url> (+ kilnPublishUser/kilnPublishPassword) adds a remote repo
plugins {
    `maven-publish`
}

publishing {
    repositories {
        mavenLocal()
        providers.gradleProperty("kilnPublishUrl").orNull?.let { publishUrl ->
            maven(publishUrl) {
                name = "kiln"
                val user = providers.gradleProperty("kilnPublishUser").orNull
                if (user != null) {
                    credentials {
                        username = user
                        password = providers.gradleProperty("kilnPublishPassword").orNull
                    }
                }
            }
        }
    }
}

// one publication per module, artifactId = archivesName (kiln-protocol, kiln-server, ...); the shadow jar when present
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("maven") {
                from(components.findByName("shadow") ?: components["java"])
                artifactId = the<BasePluginExtension>().archivesName.get()
            }
        }
    }
}
