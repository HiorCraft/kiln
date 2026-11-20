package dev.kiln.client.ext.consent

/** One permission line of an expanded consent entry. */
data class PermissionRow(val permission: String, val label: String, val requested: Boolean)
