import net.minecrell.pluginyml.bukkit.BukkitPluginDescription

plugins {
    id("kiln.publish")
    id("kiln.paper-plugin")
    id("kiln.nms")
    id("kiln.test")
}

base {
    archivesName.set("kiln-server")
}

dependencies {
    // bundled into the plugin jar
    implementation(project(":protocol"))
    implementation(project(":server-api"))
    implementation(libs.kotlin.stdlib)
}

paper {
    name = "Kiln"
    main = "dev.kiln.server.KilnPlugin"
    load = BukkitPluginDescription.PluginLoadOrder.STARTUP
    description = "Server runtime of kiln (server-described GUIs, keybinds and Lua extensions for Fabric clients)"
}
