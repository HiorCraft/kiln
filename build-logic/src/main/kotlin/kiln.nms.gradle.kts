import io.papermc.paperweight.userdev.ReobfArtifactConfiguration

// Server internals (NMS, Mojang names) through paperweight-userdev; the jar stays Mojang-mapped (no reobf)
plugins {
    java
    id("io.papermc.paperweight.userdev")
}

val libs = the<VersionCatalogsExtension>().named("libs")

dependencies {
    paperweight.paperDevBundle(libs.findVersion("paper").get().requiredVersion)
}

paperweight {
    reobfArtifactConfiguration = ReobfArtifactConfiguration.MOJANG_PRODUCTION
}
