package dev.kiln.server.runtime.extension

/**
 * Resolves the delivery (= load) order of extension packages: every dependency before its dependents, otherwise
 * registration order (stable topological sort, Kahn). Pure function.
 */
object ExtensionDependencies {
    fun resolve(packages: List<ExtensionPackage>): DependencyResolution {
        val problems = unmetDependencies(packages)
        if (problems.isNotEmpty()) return DependencyResolution(emptyList(), problems)
        val order = loadOrder(packages)
        if (order.size < packages.size) {
            val stuck = packages.filter { it !in order }
            return DependencyResolution(emptyList(), listOf("Dependency cycle between extensions: ${cycle(stuck)}"))
        }
        return DependencyResolution(order, emptyList())
    }

    /** Missing dependencies and version mismatches. */
    private fun unmetDependencies(packages: List<ExtensionPackage>): List<String> {
        val byId = packages.associateBy { it.id }
        val problems = ArrayList<String>()
        for (pkg in packages) {
            for (dep in pkg.dependencies) {
                val target = byId[dep.id]
                when {
                    target == null -> problems += "Extension '${pkg.id}' needs '${dep.id}', which is not registered"
                    dep.range != null && !dep.range.matches(target.version) ->
                        problems += "Extension '${pkg.id}' needs '${dep.id}' ${dep.range}, but version ${target.version} is registered"
                }
            }
        }
        return problems
    }

    /** Packages in load order; shorter than [packages] if a cycle leaves some unplaced. */
    private fun loadOrder(packages: List<ExtensionPackage>): List<ExtensionPackage> {
        val order = ArrayList<ExtensionPackage>(packages.size)
        val done = HashSet<String>()
        while (order.size < packages.size) {
            // earliest registered package whose dependencies are all placed
            val next = packages.firstOrNull { it.id !in done && it.dependencies.all { d -> d.id in done } } ?: break
            order += next
            done += next.id
        }
        return order
    }

    /** One cycle among [stuck] packages as `a -> b -> a` (for the error message). */
    private fun cycle(stuck: List<ExtensionPackage>): String {
        val byId = stuck.associateBy { it.id }
        var current = stuck.first()
        val path = ArrayList<String>()
        while (current.id !in path) {
            path += current.id
            current = current.dependencies.firstNotNullOfOrNull { byId[it.id] } ?: break
        }
        val start = path.indexOf(current.id).coerceAtLeast(0)
        return (path.drop(start) + current.id).joinToString(" -> ")
    }
}
