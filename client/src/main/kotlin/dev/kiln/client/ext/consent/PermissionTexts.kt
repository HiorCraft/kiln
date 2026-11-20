package dev.kiln.client.ext.consent

import dev.kiln.protocol.ExtensionPermissions

/** Human-readable permission names, in canonical order. */
object PermissionTexts {
    val LABELS: Map<String, String> = linkedMapOf(
        ExtensionPermissions.WIDGETS to "Eigene GUI-Elemente in Server-Menüs",
        ExtensionPermissions.KEYBINDS to "Eigene Tastenbelegungen",
        ExtensionPermissions.HUD to "Anzeigen im HUD",
        ExtensionPermissions.CHANNELS to "Nachrichten mit dem Server austauschen",
        ExtensionPermissions.TOASTS to "Benachrichtigungen anzeigen",
        ExtensionPermissions.SCREENS to "Eigene Menüs öffnen",
        ExtensionPermissions.INVENTORY to "Dein Inventar lesen",
        ExtensionPermissions.RESOURCES to "Ressourcen: Modelle, Texturen, Sounds, Schriften, Shader",
    )

    /** Every known permission (requested -> green check, not requested -> red X). */
    fun rows(requested: Collection<String>): List<PermissionRow> =
        ExtensionPermissions.ALL.map { PermissionRow(it, LABELS[it] ?: it, it in requested) }

    /** Height of the "Benötigt:" line block of an expanded consent entry with [count] dependencies. */
    fun dependencyHeight(count: Int): Int = if (count == 0) 0 else 12 + 11 * count

    /** "Name (id)" of each dependency, using the manifest names where known. */
    fun dependencyLabels(dependencies: List<String>, names: Map<String, String>): List<String> =
        dependencies.map { id -> names[id]?.takeIf { it != id }?.let { "$it ($id)" } ?: id }
}
