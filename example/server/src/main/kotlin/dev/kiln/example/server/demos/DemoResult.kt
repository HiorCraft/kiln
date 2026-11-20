package dev.kiln.example.server.demos

import dev.kiln.example.server.shop.lua.LuaShopController

/** Answer to a request. [fields] maps form fields to error messages (shown next to the field). */
data class DemoResult(
    val req: Long,
    val action: String,
    val ok: Boolean,
    val message: String? = null,
    val error: String? = null,
    val fields: Map<String, String>? = null,
) {
    companion object {
        const val INVALID = LuaShopController.INVALID
        const val TOO_MANY = LuaShopController.TOO_MANY

        fun ok(req: Long, action: String, message: String? = null) = DemoResult(req, action, ok = true, message = message)

        fun fail(req: Long, action: String, error: String, fields: Map<String, String>? = null) =
            DemoResult(req, action, ok = false, error = error, fields = fields)
    }
}
