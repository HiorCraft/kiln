package dev.kiln.server.runtime

/**
 * What must hold before Kiln starts: it uses server internals of exactly the Minecraft version it was built for, and
 * (embedded) runs on the host's Kotlin, which must be at least the major.minor it was compiled with.
 */
object RuntimeChecks {
    fun problems(build: BuildInfo, serverMinecraft: String, runtimeKotlin: KotlinVersion): List<String> {
        val result = ArrayList<String>()
        if (serverMinecraft != build.minecraft) {
            result += "Kiln is built for Minecraft ${build.minecraft} (server internals), this server runs $serverMinecraft"
        }
        val parts = build.kotlin.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size >= 2 && !runtimeKotlin.isAtLeast(parts[0], parts[1])) {
            result += "Kiln needs Kotlin ${parts[0]}.${parts[1]} or newer at runtime, found $runtimeKotlin"
        }
        return result
    }
}
