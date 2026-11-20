// Maven publication to mavenLocal and the SLNE releases repository (credentials from the environment variables
// SLNE_RELEASES_REPO_USERNAME / SLNE_RELEASES_REPO_PASSWORD); -PkilnPublishUrl=<url> (+ kilnPublishUser/
// kilnPublishPassword) adds another remote repo
plugins {
    `maven-publish`
}

publishing {
    repositories {
        mavenLocal()
        maven("https://reposilite.slne.dev/releases") {
            name = "slneReleases"
            credentials {
                username = providers.environmentVariable("SLNE_RELEASES_REPO_USERNAME").orNull
                password = providers.environmentVariable("SLNE_RELEASES_REPO_PASSWORD").orNull
            }
        }
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
